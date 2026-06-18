package eu.kanade.tachiyomi.extension.ar.procomic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import tachiyomi.decoder.ImageDecoder
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

class ProComic : HttpSource() {

    override val name = "ProComic"
    override val baseUrl = "https://procomic.pro"
    override val lang = "ar"
    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    companion object {
        private const val SCRAMBLED_SCHEME = "https://procomic.pro/__scrambled__/?map="
        private const val MAX_SAFE_HEIGHT = 6000
        private const val DEFAULT_SPLIT = 3
    }

    private fun String.toAbsoluteUrl(cdnBase: String): String {
        return when {
            this.startsWith("http") -> this
            this.startsWith("eyJ2IjoxLCJpdiI6I") -> "$cdnBase/i/$this"
            this.startsWith("/") -> "$cdnBase$this"
            else -> "$cdnBase/$this"
        }
    }

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .rateLimit(2, 1)
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()

            if (url.startsWith(SCRAMBLED_SCHEME) || url.contains("?map=")) {
                val encoded = url.substringAfter("?map=")
                val mapJson = String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP))
                val lazyMap = json.decodeFromString<LazyScrambledMap>(mapJson)

                val mergedBytes = resolveAndReconstructPage(lazyMap)
                    ?: return@addInterceptor Response.Builder()
                        .request(request).protocol(Protocol.HTTP_1_1)
                        .code(500).message("Error Reconstructing Image")
                        .body("".toResponseBody(null)).build()

                return@addInterceptor Response.Builder()
                    .request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(mergedBytes.toResponseBody("image/jpeg".toMediaType()))
                    .build()
            }

            var response = chain.proceed(request)
            var tryCount = 0
            while (response.code in listOf(502, 503, 429) && tryCount < 4) {
                tryCount++
                response.close()
                Thread.sleep(2000L * tryCount)
                response = chain.proceed(request)
            }

            val isImageEndpoint = response.isSuccessful &&
                request.method == "GET" &&
                url.contains("/i/") &&
                url.contains("procomic")

            if (isImageEndpoint) {
                val responseBody = response.body ?: return@addInterceptor response
                val bytes = responseBody.bytes()
                if (bytes.size > 5 &&
                    bytes[0] == 'd'.code.toByte() &&
                    bytes[1] == 'a'.code.toByte() &&
                    bytes[2] == 't'.code.toByte() &&
                    bytes[3] == 'a'.code.toByte() &&
                    bytes[4] == ':'.code.toByte()
                ) {
                    val bodyString = String(bytes)
                    val base64Data = bodyString.substringAfter("base64,")
                    val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val mimeType = bodyString.substringAfter("data:").substringBefore(";").toMediaType()
                    return@addInterceptor response.newBuilder()
                        .body(decodedBytes.toResponseBody(mimeType))
                        .build()
                } else {
                    return@addInterceptor response.newBuilder()
                        .body(bytes.toResponseBody(responseBody.contentType()))
                        .build()
                }
            }
            response
        }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept-Language", "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7")
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

    override fun popularMangaRequest(page: Int) = GET(
        "$baseUrl/api/public/content/latest-updates?limit=30&category=comics&page=$page",
        headers,
    )

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<LatestUpdatesResponse>()
        val mangas = data.data.filter { it.type != "novel" }.map { it.toSManga() }
        return MangasPage(mangas, mangas.size >= 30)
    }

    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = GET(
        "$baseUrl/api/public/content/latest-updates?limit=30&category=comics&page=$page" +
            (if (query.isNotBlank()) "&q=$query" else ""),
        headers,
    )

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val p = manga.url.split("/")
        return GET("$baseUrl/api/public/${p[0]}/${p[1]}", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        return try {
            val data = response.parseAs<SeriesDetailResponse>()
            val parts = response.request.url.pathSegments
            val idx = parts.indexOf("public")
            val seriesType = parts.getOrElse(idx + 1) { "manga" }
            SManga.create().apply {
                url = "$seriesType/${parts.getOrElse(idx + 2) { "0" }}/${data.slug ?: ""}"
                title = data.title ?: ""
                thumbnail_url = data.coverImageApp?.card?.mobile ?: data.coverImageApp?.desktop ?: data.coverImage
                author = data.author
                artist = data.artist
                description = data.synopsis ?: data.description
                status = when (data.status?.lowercase()) {
                    "ongoing", "مستمر" -> SManga.ONGOING
                    "completed", "مكتمل" -> SManga.COMPLETED
                    "hiatus" -> SManga.ON_HIATUS
                    else -> SManga.UNKNOWN
                }
            }
        } catch (e: Exception) { SManga.create() }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val p = manga.url.split("/")
        return GET(
            "$baseUrl/api/public/${p[0]}/${p[1]}/chapters?page=1&limit=500&order=desc",
            headers,
        )
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val parts = response.request.url.pathSegments
        val idx = parts.indexOf("public")
        val seriesType = parts.getOrElse(idx + 1) { "manga" }
        val seriesId = parts.getOrElse(idx + 2) { "0" }

        return response.parseAs<ChaptersResponse>().data.map { ch ->
            SChapter.create().apply {
                url = "$seriesType/$seriesId/${ch.id}/${ch.chapterNumber}"
                name = "الفصل ${ch.chapterNumber}" + (if (!ch.title.isNullOrBlank()) " - ${ch.title}" else "")
                date_upload = runCatching { dateFormat.parse(ch.publishedAt ?: "")?.time }.getOrNull() ?: 0L
                chapter_number = ch.chapterNumber.toFloatOrNull() ?: 0f
                scanlator = if (ch.lockedByCoins == true) "🔒 مدفوع" else null
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.split("/")
        val seriesType = parts.getOrElse(0) { "manga" }
        val seriesId = parts.getOrElse(1) { "0" }
        val chapterId = parts.getOrElse(2) { "0" }
        val chapterNum = parts.getOrElse(3) { "1" }
        val dynamicReferer = "$baseUrl/series/$seriesType/$seriesId/reading/$chapterId/$chapterNum"
        return GET(
            "$baseUrl/api/public/$seriesType/$seriesId/chapters?page=1&limit=500&order=desc&_cid=$chapterId",
            headers.newBuilder()
                .set("Accept", "application/json")
                .set("Referer", dynamicReferer)
                .build(),
        )
    }

    override fun pageListParse(response: Response): List<Page> {
        val chapterId = response.request.url.queryParameter("_cid") ?: return emptyList()
        val parts = response.request.url.pathSegments
        val idx = parts.indexOf("public")
        val seriesType = parts.getOrElse(idx + 1) { "manga" }
        val seriesId = parts.getOrElse(idx + 2) { "0" }

        val dynamicReferer = "$baseUrl/series/$seriesType/$seriesId/reading/$chapterId/1"
        val apiHeaders = headers.newBuilder()
            .set("Accept", "application/json")
            .set("Referer", dynamicReferer)
            .build()

        val pages = mutableListOf<Page>()

        var cachedSessionKey: String? = null
        var sessionKeyAttempted = false
        val getSessionKey: () -> String? = {
            if (!sessionKeyAttempted) {
                sessionKeyAttempted = true
                try {
                    val req = client.newCall(
                        GET("$baseUrl/chapter-map-session-key/$chapterId?legacy=1", apiHeaders),
                    ).execute()
                    if (req.isSuccessful) {
                        cachedSessionKey = req.parseAs<SessionKeyResponse>().data?.key
                    }
                } catch (_: Exception) {}
            }
            cachedSessionKey
        }

        var cdnPath = "cdn1"
        var metadataImages = emptyList<String>()
        val staticMaps = mutableListOf<DeferredPageMap>()
        val jwtTokens = mutableListOf<String>()

        val chapterData = try { response.parseAs<ChaptersResponse>() } catch (_: Exception) { ChaptersResponse() }
        for (ch in chapterData.data) {
            if (ch.id.toString() == chapterId) {
                cdnPath = ch.cdnPath ?: "cdn1"
                metadataImages = ch.metadata?.images ?: emptyList()
                ch.metadata?.maps?.forEach { map ->
                    when {
                        map.token.isJwt() -> jwtTokens.add(map.token)
                        map.token.isNotBlank() -> staticMaps.add(map)
                        map.pieces.isNotEmpty() -> staticMaps.add(map)
                    }
                }
                break
            }
        }

        val cdnBase = "https://$cdnPath.procomic.pro"
        val seenMapTokens = mutableSetOf<String>()
        val seenUrls = mutableSetOf<String>()

        metadataImages.forEach { imgPath ->
            val fullUrl = imgPath.toAbsoluteUrl(cdnBase)
            if (seenUrls.add(fullUrl)) pages.add(Page(pages.size, imageUrl = fullUrl))
        }

        staticMaps.forEach { map ->
            enqueueMap(map, chapterId, cdnBase, dynamicReferer, apiHeaders, getSessionKey,
                pages, seenUrls, seenMapTokens)
        }

        for (jwtToken in jwtTokens) {
            try {
                val newPages = fetchDeferredPages(
                    chapterId, jwtToken, cdnBase, dynamicReferer, apiHeaders,
                    getSessionKey, seenUrls, seenMapTokens,
                )
                pages.addAll(newPages)
            } catch (_: Exception) {}
        }

        return pages
    }

    private fun fetchDeferredPages(
        chapterId: String,
        jwtToken: String,
        cdnBase: String,
        dynamicReferer: String,
        apiHeaders: Headers,
        getSessionKey: () -> String?,
        seenUrls: MutableSet<String>,
        seenMapTokens: MutableSet<String>,
    ): List<Page> {
        val pages = mutableListOf<Page>()
        val jwtSplit = jwtSplitValue(jwtToken)

        for (s in 0..jwtSplit) {
            try {
                val resp = client.newCall(
                    GET(
                        "$baseUrl/chapter-deferred-media/$chapterId?token=$jwtToken&split=$s",
                        apiHeaders,
                    ),
                ).execute()
                if (!resp.isSuccessful) continue

                val parsed = resp.parseAs<ChapterDeferredResponse>()
                if (!parsed.success || parsed.data == null) continue

                parsed.data.images.forEach { url ->
                    val fullUrl = url.toAbsoluteUrl(cdnBase)
                    if (seenUrls.add(fullUrl)) pages.add(Page(pages.size, imageUrl = fullUrl))
                }

                parsed.data.maps.forEach { map ->
                    enqueueMap(map, chapterId, cdnBase, dynamicReferer, apiHeaders,
                        getSessionKey, pages, seenUrls, seenMapTokens)
                }
            } catch (_: Exception) {
                continue
            }
        }
        return pages
    }

    private fun enqueueMap(
        map: DeferredPageMap,
        chapterId: String,
        cdnBase: String,
        dynamicReferer: String,
        apiHeaders: Headers,
        getSessionKey: () -> String?,
        pages: MutableList<Page>,
        seenUrls: MutableSet<String>,
        seenMapTokens: MutableSet<String>,
    ) {
        when {
            map.pieces.isNotEmpty() -> {
                val firstPiece = map.pieces.first().toAbsoluteUrl(cdnBase)
                if (!seenUrls.add(firstPiece)) return
                buildScrambledPages(
                    dim = map.dim,
                    mode = map.mode,
                    pieces = map.pieces.map { it.toAbsoluteUrl(cdnBase) },
                    order = map.order,
                    signedToken = map.token,
                    chapterId = null,
                    mapToken = null,
                    cdnBase = cdnBase,
                    dynamicReferer = dynamicReferer,
                    pages = pages,
                )
            }
            map.token.isNotBlank() && !map.token.isJwt() -> {
                if (!seenMapTokens.add(map.token)) return
                buildLazyScrambledPage(
                    chapterId = chapterId,
                    mapToken = map.token,
                    cdnBase = cdnBase,
                    dynamicReferer = dynamicReferer,
                    pages = pages,
                )
            }
            map.token.isNotBlank() -> {
                if (!seenMapTokens.add(map.token)) return
                val sk = getSessionKey()
                val resolved = if (sk != null) decryptMap(map.token, sk) else null
                if (resolved != null && resolved.pieces.isNotEmpty()) {
                    buildScrambledPages(
                        dim = resolved.dim,
                        mode = resolved.mode,
                        pieces = resolved.pieces.map { it.toAbsoluteUrl(cdnBase) },
                        order = resolved.order,
                        signedToken = resolved.token,
                        chapterId = null,
                        mapToken = null,
                        cdnBase = cdnBase,
                        dynamicReferer = dynamicReferer,
                        pages = pages,
                    )
                } else {
                    buildLazyScrambledPage(
                        chapterId = chapterId,
                        mapToken = map.token,
                        cdnBase = cdnBase,
                        dynamicReferer = dynamicReferer,
                        pages = pages,
                    )
                }
            }
        }
    }

    private fun buildLazyScrambledPage(
        chapterId: String,
        mapToken: String,
        cdnBase: String,
        dynamicReferer: String,
        pages: MutableList<Page>,
    ) {
        val lazyMap = LazyScrambledMap(
            chapterId = chapterId,
            mapToken = mapToken,
            cdnBase = cdnBase,
            dynamicReferer = dynamicReferer,
        )
        val encoded = Base64.encodeToString(
            json.encodeToString(lazyMap).toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP,
        )
        pages.add(Page(pages.size, imageUrl = "$SCRAMBLED_SCHEME$encoded"))
    }

    private fun buildScrambledPages(
        dim: List<Int>,
        mode: String,
        pieces: List<String>,
        order: List<Int>,
        signedToken: String,
        chapterId: String?,
        mapToken: String?,
        cdnBase: String,
        dynamicReferer: String,
        pages: MutableList<Page>,
    ) {
        val estimatedTotalH = dim.getOrNull(1)?.takeIf { it > 0 } ?: 10000
        val parts = if (estimatedTotalH > MAX_SAFE_HEIGHT) {
            ceil(estimatedTotalH.toDouble() / MAX_SAFE_HEIGHT).toInt()
        } else {
            1
        }

        for (p in 0 until parts) {
            val lazyMap = LazyScrambledMap(
                chapterId = chapterId,
                mapToken = mapToken,
                cdnBase = cdnBase,
                dynamicReferer = dynamicReferer,
                dim = dim,
                mode = mode,
                pieces = pieces,
                order = order,
                signedToken = signedToken,
                splitPart = p,
                totalParts = parts,
            )
            val encoded = Base64.encodeToString(
                json.encodeToString(lazyMap).toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP,
            )
            pages.add(Page(pages.size, imageUrl = "$SCRAMBLED_SCHEME$encoded"))
        }
    }

    private fun resolveAndReconstructPage(lazyMap: LazyScrambledMap): ByteArray? {
        var finalMap: LazyScrambledMap = lazyMap

        if (finalMap.pieces.isEmpty() && !finalMap.chapterId.isNullOrBlank() && !finalMap.mapToken.isNullOrBlank()) {
            var proxySuccess = false
            var attempt = 0
            val apiHeaders = headers.newBuilder()
                .set("Accept", "application/json")
                .set("Referer", finalMap.dynamicReferer)
                .set("Origin", baseUrl)
                .build()

            while (!proxySuccess && attempt < 4) {
                attempt++
                try {
                    val fallbackMap = DeferredPageMap(token = finalMap.mapToken!!)
                    val bodyStr = json.encodeToString(fallbackMap)
                    val body = bodyStr.toRequestBody("application/json".toMediaType())
                    val proxyReq = POST("$baseUrl/chapter-map-proxy-plan/${finalMap.chapterId}", apiHeaders, body)
                    
                    client.newCall(proxyReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val parsed = resp.parseAs<ProxyPlanResponse>()
                            val resolved = parsed.data?.map
                            if (resolved != null && resolved.pieces.isNotEmpty()) {
                                finalMap = finalMap.copy(
                                    dim = resolved.dim,
                                    mode = resolved.mode,
                                    pieces = resolved.pieces.map { it.toAbsoluteUrl(finalMap.cdnBase) },
                                    order = resolved.order,
                                    signedToken = resolved.token,
                                )
                                proxySuccess = true
                            }
                        }
                    }
                } catch (_: Exception) {
                    Thread.sleep(1500L * attempt)
                }
            }
            if (!proxySuccess) return null
        }

        if (finalMap.pieces.isEmpty()) return null

        val (cols, rows) = parseMode(finalMap.mode ?: "", finalMap.pieces.size)
        val bitmaps = arrayOfNulls<Bitmap>(finalMap.pieces.size)

        for (targetIdx in finalMap.pieces.indices) {
            val srcIdx = if (finalMap.order.size == finalMap.pieces.size) finalMap.order[targetIdx] else targetIdx
            val basePieceUrl = finalMap.pieces.getOrNull(srcIdx) ?: continue

            val pieceUrl = if (!finalMap.signedToken.isNullOrBlank() && !basePieceUrl.contains("/i/eyJ")) {
                if (basePieceUrl.contains("?")) "$basePieceUrl&token=${finalMap.signedToken}"
                else "$basePieceUrl?token=${finalMap.signedToken}"
            } else {
                basePieceUrl
            }

            var pieceDownloaded = false
            var pAttempt = 0
            
            val pieceHeaders = headers.newBuilder()
                .set("Referer", finalMap.dynamicReferer)
                .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .build()

            while (!pieceDownloaded && pAttempt < 5) {
                pAttempt++
                try {
                    val req = Request.Builder().url(pieceUrl).headers(pieceHeaders).build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val bodyBytes = resp.body.bytes()
                            val isBase64Text = bodyBytes.size > 5 &&
                                bodyBytes[0] == 'd'.code.toByte() &&
                                bodyBytes[1] == 'a'.code.toByte() &&
                                bodyBytes[2] == 't'.code.toByte() &&
                                bodyBytes[3] == 'a'.code.toByte() &&
                                bodyBytes[4] == ':'.code.toByte()

                            val finalBytes = if (isBase64Text) {
                                val base64Data = String(bodyBytes).substringAfter("base64,")
                                Base64.decode(base64Data, Base64.DEFAULT)
                            } else {
                                bodyBytes
                            }
                            bitmaps[targetIdx] = decodeAvif(finalBytes)
                            pieceDownloaded = bitmaps[targetIdx] != null
                        } else if (resp.code in listOf(502, 503, 429)) {
                            Thread.sleep(1500L * pAttempt)
                        } else {
                            break 
                        }
                    }
                } catch (_: Exception) {
                    Thread.sleep(1500L * pAttempt)
                }
            }
        }

        return try {
            val validBitmaps = bitmaps.filterNotNull()
            if (validBitmaps.isEmpty()) return null

            val calcTotalW: Int
            val calcTotalH: Int

            val mapDim = finalMap.dim
            when {
                cols == 1 -> {
                    calcTotalW = mapDim.getOrNull(0)?.takeIf { it > 0 } ?: validBitmaps.maxOf { it.width }
                    calcTotalH = mapDim.getOrNull(1)?.takeIf { it > 0 } ?: validBitmaps.sumOf { it.height }
                }
                rows == 1 -> {
                    calcTotalW = mapDim.getOrNull(0)?.takeIf { it > 0 } ?: validBitmaps.sumOf { it.width }
                    calcTotalH = mapDim.getOrNull(1)?.takeIf { it > 0 } ?: validBitmaps.maxOf { it.height }
                }
                else -> {
                    val firstBmp = validBitmaps.first()
                    calcTotalW = mapDim.getOrNull(0)?.takeIf { it > 0 } ?: (firstBmp.width * cols)
                    calcTotalH = mapDim.getOrNull(1)?.takeIf { it > 0 } ?: (firstBmp.height * rows)
                }
            }

            val totalParts = finalMap.totalParts ?: 1
            val splitPart = finalMap.splitPart ?: 0
            val partH = calcTotalH / totalParts
            val actualPartH = if (splitPart == totalParts - 1) calcTotalH - (partH * splitPart) else partH

            if (calcTotalW <= 0 || actualPartH <= 0) return null

            val result = try {
                Bitmap.createBitmap(calcTotalW, actualPartH, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                Bitmap.createBitmap(calcTotalW, actualPartH, Bitmap.Config.RGB_565)
            }
            val canvas = Canvas(result)
            canvas.translate(0f, -(splitPart * partH).toFloat())

            when {
                cols == 1 -> {
                    var currentY = 0f
                    for (bmp in bitmaps) {
                        if (bmp != null) {
                            canvas.drawBitmap(bmp, 0f, currentY, null)
                            currentY += bmp.height
                            bmp.recycle()
                        }
                    }
                }
                rows == 1 -> {
                    var currentX = 0f
                    for (bmp in bitmaps) {
                        if (bmp != null) {
                            canvas.drawBitmap(bmp, currentX, 0f, null)
                            currentX += bmp.width
                            bmp.recycle()
                        }
                    }
                }
                else -> {
                    val tileW = validBitmaps.first().width
                    val tileH = validBitmaps.first().height
                    for (targetIdx in bitmaps.indices) {
                        val bmp = bitmaps[targetIdx] ?: continue
                        val col = targetIdx % cols
                        val row = targetIdx / cols
                        canvas.drawBitmap(bmp, (col * tileW).toFloat(), (row * tileH).toFloat(), null)
                        bmp.recycle()
                    }
                }
            }

            val out = ByteArrayOutputStream()
            result.compress(Bitmap.CompressFormat.JPEG, 85, out)
            result.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    private fun decryptMap(tokenStr: String, sessionKeyBase64: String): DeferredPageMap? {
        return try {
            val tokenJsonStr = String(Base64.decode(tokenStr, Base64.URL_SAFE or Base64.DEFAULT))
            val tokenData = json.decodeFromString<EncryptedToken>(tokenJsonStr)

            val keyBytes = Base64.decode(sessionKeyBase64, Base64.URL_SAFE)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val ivBytes = Base64.decode(tokenData.iv, Base64.URL_SAFE)
            val tagBytes = Base64.decode(tokenData.tag, Base64.URL_SAFE)
            val cipherTextBytes = Base64.decode(tokenData.data, Base64.URL_SAFE)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, ivBytes))
            val decryptedBytes = cipher.doFinal(cipherTextBytes + tagBytes)
            json.decodeFromString<DeferredPageMap>(String(decryptedBytes))
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeAvif(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val decoder = ImageDecoder.newInstance(bytes.inputStream())
        return if (decoder != null) {
            try { decoder.decode() } catch (_: Exception) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } finally { decoder.recycle() }
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    private fun parseMode(mode: String, pieceCount: Int): Pair<Int, Int> = when {
        mode.startsWith("grid_") -> {
            val clean = mode.removePrefix("grid_")
            val p = if (clean.contains("x")) clean.split("x") else clean.split("_")
            Pair(p.getOrNull(0)?.toIntOrNull() ?: 1, p.getOrNull(1)?.toIntOrNull() ?: 1)
        }
        mode.startsWith("vertical_") -> Pair(mode.removePrefix("vertical_").toIntOrNull() ?: pieceCount, 1)
        mode.startsWith("horizontal_") -> Pair(1, mode.removePrefix("horizontal_").toIntOrNull() ?: pieceCount)
        else -> Pair(1, pieceCount)
    }

    private fun String.isJwt(): Boolean = startsWith("eyJhbGci") && count { it == '.' } == 2

    private fun jwtSplitValue(jwtToken: String): Int {
        return try {
            val payloadSegment = jwtToken.split(".").getOrNull(1) ?: return DEFAULT_SPLIT
            val padded = payloadSegment.padEnd(payloadSegment.length + (4 - payloadSegment.length % 4) % 4, '=')
            val payloadJson = String(Base64.decode(padded, Base64.URL_SAFE))
            json.decodeFromString<JwtPayload>(payloadJson).split
        } catch (e: Exception) {
            DEFAULT_SPLIT
        }
    }

    override fun imageUrlParse(response: Response) = ""

    private inline fun <reified T> Response.parseAs(): T = json.decodeFromStream(body.byteStream())
}

@Serializable
data class LazyScrambledMap(
    val chapterId: String? = null,
    val mapToken: String? = null,
    val cdnBase: String,
    val dynamicReferer: String,
    val dim: List<Int> = emptyList(),
    val mode: String? = null,
    val pieces: List<String> = emptyList(),
    val order: List<Int> = emptyList(),
    val signedToken: String? = null,
    val splitPart: Int? = null,
    val totalParts: Int? = null,
)

@Serializable
data class JwtPayload(val split: Int = DEFAULT_SPLIT, val cid: Int = 0, val p: String = "")

@Serializable
data class SessionKeyResponse(val success: Boolean = false, val data: SessionKeyData? = null)

@Serializable
data class SessionKeyData(val key: String = "")

@Serializable
data class EncryptedToken(val v: Int = 3, val m: String = "", val cid: Int = 0, val iv: String = "", val tag: String = "", val data: String = "")

@Serializable
data class LatestUpdatesResponse(val success: Boolean = false, val data: List<SeriesDto> = emptyList())

@Serializable
data class SeriesDto(
    @SerialName("mangaId") val id: Int = 0,
    @SerialName("mangaSlug") val slug: String = "",
    @SerialName("mangaTitle") val title: String = "",
    val coverImage: String? = null,
    val type: String = "manga",
    val coverImageApp: CoverImageApp? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = "$type/$id/$slug"
        title = this@SeriesDto.title
        thumbnail_url = coverImageApp?.card?.mobile ?: coverImageApp?.desktop ?: coverImage
    }
}

@Serializable
data class CoverImageApp(val desktop: String? = null, val card: CardImages? = null)

@Serializable
data class CardImages(val mobile: String? = null, val desktop: String? = null)

@Serializable
data class SeriesDetailResponse(
    val id: Int = 0, val title: String? = null, val slug: String? = null, val coverImage: String? = null,
    val coverImageApp: CoverImageApp? = null, val author: String? = null, val artist: String? = null,
    val description: String? = null, val synopsis: String? = null, val status: String? = null,
)

@Serializable
data class ChaptersResponse(val data: List<ChapterDto> = emptyList(), val total: Int = 0)

@Serializable
data class ChapterDto(
    val id: Int = 0, @SerialName("chapter_number") val chapterNumber: String = "0", val title: String? = null,
    @SerialName("published_at") val publishedAt: String? = null, val lockedByCoins: Boolean? = null,
    @SerialName("cdn_path") val cdnPath: String? = null, val metadata: ChapterMetadataDto? = null,
)

@Serializable
data class ChapterMetadataDto(val images: List<String> = emptyList(), val maps: List<DeferredPageMap> = emptyList())

@Serializable
data class ChapterDeferredResponse(val success: Boolean = false, val data: ChapterDeferredData? = null)

@Serializable
data class ChapterDeferredData(val chapterId: Int = 0, val splitIndex: Int = 0, val images: List<String> = emptyList(), val maps: List<DeferredPageMap> = emptyList())

@Serializable
data class DeferredPageMap(val dim: List<Int> = emptyList(), val mode: String = "", val pieces: List<String> = emptyList(), val order: List<Int> = emptyList(), val token: String = "", val method: String = "")

@Serializable
data class ProxyPlanResponse(val success: Boolean = false, val data: ProxyPlanData? = null)

@Serializable
data class ProxyPlanData(val map: DeferredPageMap? = null)
