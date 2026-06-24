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

    // مخصص لتشفير الـ request bodies — يتجاهل القيم الفارغة (null)
    private val jsonRequest = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = false
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    companion object {
        // تم تغيير البنية لمنع إرسال روابط طويلة عبر الشبكة
        private const val SCRAMBLED_SCHEME = "https://procomic.pro/__scrambled_asset__/"
        private const val MAX_SAFE_HEIGHT = 6000

        // cache مشتركة: cleanUrl → ScrambledMap — تُملأ في processMap وتُقرأ في الـ interceptor
        private val scrambledMapCache = java.util.concurrent.ConcurrentHashMap<String, ScrambledMap>()
    }

    private fun String.toAbsoluteUrl(cdnBase: String): String {
        return when {
            this.startsWith("http") -> this
            this.startsWith("eyJ2IjoxLCJpdiI6I") -> "$cdnBase/i/$this"
            this.startsWith("/") -> "$cdnBase$this"
            else -> "$cdnBase/$this"
        }
    }

    // client منفصل لتحميل القطع — بدون interceptor لتجنب الـ recursive call ولا rateLimit لتجنب الـ deadlock
    private val pieceClient: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    // الـ Interceptor الآن يقرأ خريطة التفكيك محلياً من كائن الصفحة لمنع الـ 502 تماماً
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .rateLimit(2, 1)
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()

            if (url.startsWith(SCRAMBLED_SCHEME)) {
                // قراءة الخريطة من الـ cache بالـ URL النظيف (بدون fragment)
                val cleanUrl = url.substringBefore("#")
                val pageMap = scrambledMapCache[cleanUrl]
                    ?: return@addInterceptor Response.Builder()
                        .request(request).protocol(Protocol.HTTP_1_1)
                        .code(400).message("Missing Map Metadata")
                        .body("".toResponseBody(null)).build()

                val mergedBytes = reconstructPage(pageMap)
                    ?: return@addInterceptor Response.Builder()
                        .request(request).protocol(Protocol.HTTP_1_1)
                        .code(500).message("Merge Failure")
                        .body("".toResponseBody(null)).build()

                return@addInterceptor Response.Builder()
                    .request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(mergedBytes.toResponseBody("image/jpeg".toMediaType()))
                    .build()
            }

            val response = chain.proceed(request)

            val isPotentialBase64Image = response.isSuccessful && request.method == "GET" &&
                url.contains("/i/") && url.contains("procomic")

            if (isPotentialBase64Image) {
                val responseBody = response.body
                if (responseBody != null) {
                    val bytes = responseBody.bytes()
                    val isBase64Text = bytes.size > 20 &&
                        bytes[0] == 'd'.code.toByte() &&
                        bytes[1] == 'a'.code.toByte() &&
                        bytes[2] == 't'.code.toByte() &&
                        bytes[3] == 'a'.code.toByte() &&
                        bytes[4] == ':'.code.toByte()

                    if (isBase64Text) {
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
            }
            response
        }
        .build()

    // تخصيص دالة طلب الصورة لحقن خريطة الدمج محلياً داخل الـ Tag الخاص بـ OkHttp دون إرسالها للسيرفر
    override fun imageUrlParse(response: Response): String = ""

    override fun imageRequest(page: Page): Request {
        val request = super.imageRequest(page)
        val fragmentData = page.url.substringAfter("#", "")
        if (fragmentData.isNotBlank()) {
            try {
                val mapJson = String(Base64.decode(fragmentData, Base64.URL_SAFE or Base64.NO_WRAP))
                val pageMap = json.decodeFromString<ScrambledMap>(mapJson)
                // نربط الخريطة كـ Tag محلي بالطلب لكي تلتقطها دالة الـ Interceptor
                return request.newBuilder().tag(ScrambledMap::class.java, pageMap).build()
            } catch (e: Exception) {}
        }
        return request
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept-Language", "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

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
            SManga.create().apply {
                url = "${parts.getOrElse(idx + 1) { "manga" }}/${parts.getOrElse(idx + 2) { "0" }}/${data.slug ?: ""}"
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
            "$baseUrl/api/public/${p[0]}/${p[1]}/chapters?page=1&limit=600&order=desc",
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

        val url = "$baseUrl/api/public/$seriesType/$seriesId/chapters".toHttpUrl()
            .newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("limit", "500")
            .addQueryParameter("order", "desc")
            .addQueryParameter("_cid", chapterId)
            .build()

        return GET(url, headers.newBuilder().set("Accept", "application/json").build())
    }

    override fun pageListParse(response: Response): List<Page> {
        val chapterId = response.request.url.queryParameter("_cid") ?: return emptyList()
        val seriesType = response.request.url.pathSegments.let { parts ->
            val idx = parts.indexOf("public")
            parts.getOrElse(idx + 1) { "manga" }
        }
        val seriesId = response.request.url.pathSegments.let { parts ->
            val idx = parts.indexOf("public")
            parts.getOrElse(idx + 2) { "0" }
        }

        val apiHeaders = headers.newBuilder().set("Accept", "application/json").build()
        val pages = mutableListOf<Page>()
        val seenUrls = mutableSetOf<String>()

        var cdnPath = "cdn1"
        var metadataImages = emptyList<String>()
        val mapsList = mutableListOf<DeferredPageMap>()
        var found = false

        var cachedSessionKey: String? = null
        var sessionKeyAttempted = false
        val getSessionKey = {
            if (!sessionKeyAttempted) {
                sessionKeyAttempted = true
                try {
                    val req = client.newCall(
                        GET("$baseUrl/chapter-map-session-key/$chapterId?legacy=1", apiHeaders),
                    ).execute()
                    if (req.isSuccessful) {
                        cachedSessionKey = req.parseAs<SessionKeyResponse>().data?.key
                    }
                } catch (e: Exception) { }
            }
            cachedSessionKey
        }

        val currentData = try { response.parseAs<ChaptersResponse>() } catch (e: Exception) { ChaptersResponse() }
        for (ch in currentData.data) {
            if (ch.id.toString() == chapterId) {
                cdnPath = ch.cdnPath ?: "cdn1"
                metadataImages = ch.metadata?.images ?: emptyList()
                ch.metadata?.maps?.let { mapsList.addAll(it) }
                found = true
                break
            }
        }

        if (!found) {
            var pg = 2
            outer@ while (pg <= 10) {
                try {
                    val resp = client.newCall(
                        GET(
                            "$baseUrl/api/public/$seriesType/$seriesId/chapters?limit=600&page=$pg&order=desc",
                            apiHeaders,
                        ),
                    ).execute()
                    if (!resp.isSuccessful) break
                    val data = resp.parseAs<ChaptersResponse>()
                    if (data.data.isEmpty()) break
                    for (ch in data.data) {
                        if (ch.id.toString() == chapterId) {
                            cdnPath = ch.cdnPath ?: "cdn1"
                            metadataImages = ch.metadata?.images ?: emptyList()
                            ch.metadata?.maps?.let { mapsList.addAll(it) }
                            break@outer
                        }
                    }
                } catch (e: Exception) { break }
                pg++
            }
        }

        val cdnBase = "https://$cdnPath.procomic.pro"

        metadataImages.forEach { imgPath ->
            val fullUrl = imgPath.toAbsoluteUrl(cdnBase)
            if (seenUrls.add(fullUrl)) pages.add(Page(pages.size, imageUrl = fullUrl))
        }

        mapsList.forEach { map ->
            when {
                // JWT tokens من metadata منتهية الصلاحية دائماً — نتجاهلها ونستخدم proxy-plan مباشرة
                map.token.isNotBlank() && map.pieces.isEmpty() -> {
                    val resolved = resolveMapViaProxy(map, chapterId, apiHeaders)
                        ?: resolveMap(map, chapterId, apiHeaders, getSessionKey)
                    if (resolved != null && resolved.pieces.isNotEmpty()) {
                        val absolutePieces = resolved.pieces.map { it.toAbsoluteUrl(cdnBase) }
                        processMap(resolved.dim, resolved.mode, absolutePieces, resolved.order, resolved.rects, resolved.token, pages, seenUrls, chapterId)
                    }
                }
                map.pieces.isNotEmpty() -> {
                    val absolutePieces = map.pieces.map { it.toAbsoluteUrl(cdnBase) }
                    processMap(map.dim, map.mode, absolutePieces, map.order, map.rects, map.token, pages, seenUrls, chapterId)
                }
            }
        }

        // لم نعد نحتاج fetchDeferredPages لأن proxy-plan يتولى كل شيء مباشرة

        return pages
    }

    // دالة مخصصة لحل browser_session tokens عبر proxy-plan مباشرة بدون الحاجة لمفتاح الجلسة
    private fun resolveMapViaProxy(
        map: DeferredPageMap,
        chapterId: String,
        apiHeaders: Headers,
    ): DeferredPageMap? {
        return try {
            val requestBody = ProxyPlanRequestBody(
                token = map.token.ifBlank { null },
                method = map.method.ifBlank { null },
                dim = map.dim.ifEmpty { null },
                mode = map.mode.ifBlank { null },
                order = map.order.ifEmpty { null },
            )
            val bodyStr = jsonRequest.encodeToString(requestBody)
            val body = bodyStr.toRequestBody("application/json".toMediaType())
            val reqHeaders = apiHeaders.newBuilder()
                .set("Origin", baseUrl)
                .set("Referer", "$baseUrl/")
                .set("Content-Type", "application/json")
                .set("Accept", "application/json")
                .build()
            val proxyReq = POST(
                "$baseUrl/chapter-map-proxy-plan/$chapterId",
                reqHeaders,
                body,
            )
            val proxyResp = client.newCall(proxyReq).execute()
            if (!proxyResp.isSuccessful) {
                throw Exception("proxy-plan فشل: HTTP ${proxyResp.code} — body: ${proxyResp.body.string().take(300)}")
            }
            val result = proxyResp.parseAs<ProxyPlanResponse>()
            if (!result.success) {
                throw Exception("proxy-plan أرجع success=false — body: ${bodyStr.take(200)}")
            }
            result.data?.map
        } catch (e: Exception) {
            throw Exception("resolveMapViaProxy فشل للفصل $chapterId: ${e.message}")
        }
    }

    private fun resolveMap(
        map: DeferredPageMap,
        chapterId: String,
        apiHeaders: Headers,
        getSessionKey: () -> String?,
    ): DeferredPageMap? {
        if (map.pieces.isNotEmpty()) return map

        if (map.token.isNotBlank()) {
            var dec: DeferredPageMap? = null

            val sk = getSessionKey()
            if (sk != null) dec = decryptMap(map.token, sk)

            if (dec == null || dec.pieces.isEmpty()) {
                try {
                    val requestBody = ProxyPlanRequestBody(
                        token = map.token.ifBlank { null },
                        method = map.method.ifBlank { null },
                        dim = map.dim.ifEmpty { null },
                        mode = map.mode.ifBlank { null },
                        order = map.order.ifEmpty { null },
                    )
                    val bodyStr = jsonRequest.encodeToString(requestBody)
                    val body = bodyStr.toRequestBody("application/json".toMediaType())
                    val proxyReq = POST(
                        "$baseUrl/chapter-map-proxy-plan/$chapterId",
                        apiHeaders.newBuilder()
                            .set("Origin", baseUrl)
                            .set("Referer", "$baseUrl/")
                            .set("Content-Type", "application/json")
                            .set("Accept", "application/json")
                            .build(),
                        body,
                    )
                    val proxyResp = client.newCall(proxyReq).execute()
                    if (proxyResp.isSuccessful) dec = proxyResp.parseAs<ProxyPlanResponse>().data?.map
                } catch (e: Exception) { }
            }
            return dec
        }
        return null
    }

    // هنا تكمن معالجة المشكلة: إبقاء الرابط الخارجي قصيراً وحفظ الـ Map كـ Hash محلي متصل بالصفحة
    private fun processMap(
        dim: List<Int>,
        mode: String,
        pieces: List<String>,
        order: List<Int>,
        rects: List<RectDto>,
        signedToken: String,
        pages: MutableList<Page>,
        seenUrls: MutableSet<String>,
        chapterId: String,
    ) {
        if (pieces.isEmpty() || !seenUrls.add(pieces.first())) return

        val estimatedTotalH = dim.getOrNull(1)?.takeIf { it > 0 } ?: 10000
        val parts = if (estimatedTotalH > MAX_SAFE_HEIGHT) {
            ceil(estimatedTotalH.toDouble() / MAX_SAFE_HEIGHT).toInt()
        } else {
            1
        }

        for (p in 0 until parts) {
            val mapData = ScrambledMap(
                dim = dim,
                mode = mode,
                pieces = pieces,
                order = order,
                rects = rects,
                signedToken = signedToken,
                splitPart = p,
                totalParts = parts,
            )
            val encoded = Base64.encodeToString(
                json.encodeToString(mapData).toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP,
            )
            // url يحمل الـ fragment (للتوافق) — imageUrl نظيف لأن OkHttp يحذف الـ fragment
            val cleanUrl = "$SCRAMBLED_SCHEME${chapterId}_${pages.size}_part_$p.jpg"
            val urlWithFragment = "$cleanUrl#$encoded"
            scrambledMapCache[cleanUrl] = mapData
            pages.add(Page(pages.size, url = urlWithFragment, imageUrl = cleanUrl))
        }
    }

    private fun reconstructPage(map: ScrambledMap): ByteArray? {
        if (map.pieces.isEmpty()) return null

        val useRects = map.rects.size == map.pieces.size
        val (cols, rows) = if (!useRects) parseMode(map.mode, map.pieces.size) else Pair(1, 1)
        val bitmaps = arrayOfNulls<Bitmap>(map.pieces.size)

        for (targetIdx in 0 until map.pieces.size) {
            val srcIdx = if (map.order.size == map.pieces.size) map.order[targetIdx] else targetIdx
            val basePieceUrl = map.pieces.getOrNull(srcIdx) ?: continue

            val pieceUrl = if (map.signedToken.isNotBlank() && !basePieceUrl.contains("/i/eyJ2IjoxLCJpdiI6IJ")) {
                if (basePieceUrl.contains("?")) "$basePieceUrl&token=${map.signedToken}"
                else "$basePieceUrl?token=${map.signedToken}"
            } else {
                basePieceUrl
            }

            val req = Request.Builder()
                .url(pieceUrl)
                .header("Referer", "$baseUrl/")
                .header("Accept", "image/avif,image/webp,image/jpeg,*/*")
                .header("User-Agent", headers["User-Agent"] ?: "Mozilla/5.0")
                .build()

            try {
                pieceClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bodyBytes = resp.body.bytes()
                        val isBase64Text = bodyBytes.size > 20 &&
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
                    }
                }
            } catch (e: Exception) { }
        }

        return try {
            val validBitmaps = bitmaps.filterNotNull()
            if (validBitmaps.isEmpty()) return null

            val totalW = map.dim.getOrNull(0)?.takeIf { it > 0 }
                ?: if (useRects) map.rects.maxOf { it.left + it.width } else validBitmaps.maxOf { it.width }
            val totalH = map.dim.getOrNull(1)?.takeIf { it > 0 }
                ?: if (useRects) map.rects.maxOf { it.top + it.height } else validBitmaps.sumOf { it.height }

            val totalParts = map.totalParts ?: 1
            val splitPart = map.splitPart ?: 0
            val partH = totalH / totalParts
            val actualPartH = if (splitPart == totalParts - 1) totalH - (partH * splitPart) else partH

            if (totalW <= 0 || actualPartH <= 0) return null

            val result = try {
                Bitmap.createBitmap(totalW, actualPartH, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                Bitmap.createBitmap(totalW, actualPartH, Bitmap.Config.RGB_565)
            }
            val canvas = Canvas(result)
            canvas.translate(0f, -(splitPart * partH).toFloat())

            if (useRects) {
                // استخدام الـ rects للموضع الدقيق لكل قطعة
                for (targetIdx in bitmaps.indices) {
                    val bmp = bitmaps[targetIdx] ?: continue
                    val rect = map.rects[targetIdx]
                    canvas.drawBitmap(bmp, rect.left.toFloat(), rect.top.toFloat(), null)
                    bmp.recycle()
                }
            } else {
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
            // browser_session tokens لا يمكن فك تشفيرها محلياً - تحتاج proxy-plan
            if (tokenData.method == "browser_session" || tokenData.m == "browser_session") return null

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
            try { decoder.decode() } catch (e: Exception) {
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

    private inline fun <reified T> Response.parseAs(): T =
        json.decodeFromStream(body.byteStream())
}

@Serializable
data class SessionKeyResponse(
    val success: Boolean = false,
    val data: SessionKeyData? = null,
)

@Serializable
data class SessionKeyData(val key: String = "")

@Serializable
data class EncryptedToken(
    val v: Int = 3,
    val m: String = "",
    val method: String = "",
    val cid: Int = 0,
    val iv: String = "",
    val tag: String = "",
    val data: String = "",
)

@Serializable
data class RectDto(
    val left: Int = 0,
    val top: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class ScrambledMap(
    val dim: List<Int> = emptyList(),
    val mode: String = "",
    val pieces: List<String> = emptyList(),
    val order: List<Int> = emptyList(),
    val rects: List<RectDto> = emptyList(),
    val signedToken: String = "",
    val splitPart: Int? = null,
    val totalParts: Int? = null,
)

@Serializable
data class LatestUpdatesResponse(
    val success: Boolean = false,
    val data: List<SeriesDto> = emptyList(),
)

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
    val id: Int = 0,
    val title: String? = null,
    val slug: String? = null,
    val coverImage: String? = null,
    val coverImageApp: CoverImageApp? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val synopsis: String? = null,
    val status: String? = null,
)

@Serializable
data class ChaptersResponse(
    val data: List<ChapterDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class ChapterDto(
    val id: Int = 0,
    @SerialName("chapter_number") val chapterNumber: String = "0",
    val title: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val lockedByCoins: Boolean? = null,
    @SerialName("cdn_path") val cdnPath: String? = null,
    val metadata: ChapterMetadataDto? = null,
)

@Serializable
data class ChapterMetadataDto(
    val images: List<String> = emptyList(),
    val maps: List<DeferredPageMap> = emptyList(),
)

@Serializable
data class DeferredPageMap(
    val dim: List<Int> = emptyList(),
    val mode: String = "",
    val pieces: List<String> = emptyList(),
    val order: List<Int> = emptyList(),
    val rects: List<RectDto> = emptyList(),
    val token: String = "",
    val method: String = "",
)

@Serializable
data class ProxyPlanResponse(
    val success: Boolean = false,
    val data: ProxyPlanData? = null,
)

@Serializable
data class ProxyPlanData(
    val map: DeferredPageMap? = null,
)

// كائن مخصص للـ request body — يحذف الحقول الفارغة تلقائياً
@Serializable
data class ProxyPlanRequestBody(
    val token: String? = null,
    val method: String? = null,
    val dim: List<Int>? = null,
    val mode: String? = null,
    val order: List<Int>? = null,
)
