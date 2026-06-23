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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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

    // كاش محلي مؤقت لمنع تكرار طلب الأي بي آي بكثافة أثناء التمرير السريع
    private var cachedLayoutChapterId: String = ""
    private var cachedLayoutTime: Long = 0L
    private var cachedLayout: List<LayoutItem> = emptyList()

    companion object {
        private const val SCRAMBLED_SCHEME = "https://procomic.pro/__scrambled_asset__/"
        private const val MAX_SAFE_HEIGHT = 6000
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
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .rateLimit(2, 1)
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()

            // اعتراض روابط الصفحات المفرومة وجلب بياناتها وتوكناتها فوراً "طازجة"
            if (url.startsWith(SCRAMBLED_SCHEME)) {
                val cleanPath = url.substringAfter(SCRAMBLED_SCHEME)
                val parts = cleanPath.split("/")
                val chapterId = parts.getOrNull(0) ?: ""
                val seriesType = parts.getOrNull(1) ?: "manga"
                val seriesId = parts.getOrNull(2) ?: "0"
                val pageIndex = parts.getOrNull(3)?.substringBefore("#")?.toIntOrNull() ?: 0

                val freshLayout = getPageLayout(chapterId, seriesType, seriesId)
                val targetItem = freshLayout.getOrNull(pageIndex)
                    ?: return@addInterceptor Response.Builder()
                        .request(request).protocol(Protocol.HTTP_1_1)
                        .code(404).message("Page Not Found")
                        .body("".toResponseBody(null)).build()

                if (!targetItem.isScrambled || targetItem.mapData == null) {
                    return@addInterceptor Response.Builder()
                        .request(request).protocol(Protocol.HTTP_1_1)
                        .code(400).message("Not a scrambled page")
                        .body("".toResponseBody(null)).build()
                }

                val mergedBytes = reconstructPageWithFreshMap(
                    targetItem.mapData, 
                    targetItem.cdnBase, 
                    targetItem.splitPart, 
                    targetItem.totalParts
                ) ?: return@addInterceptor Response.Builder()
                    .request(request).protocol(Protocol.HTTP_1_1)
                    .code(500).message("Merge Failure")
                    .body("".toResponseBody(null)).build()

                return@addInterceptor Response.Builder()
                    .request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(mergedBytes.toResponseBody("image/jpeg".toMediaType()))
                    .build()
            }

            // معالجة الـ Base64 التلقائية للصور العادية أو القطع المستدعاة
            val response = chain.proceed(request)
            val isPotentialBase64Image = response.isSuccessful && request.method == "GET" &&
                url.contains("/i/") && url.contains("procomic")

            if (isPotentialBase64Image) {
                val responseBody = response.body
                if (responseBody != null) {
                    val bytes = responseBody.bytes()
                    val sampleB = String(bytes, 0, minOf(bytes.size, 50), Charsets.US_ASCII).trim()
                    
                    if (sampleB.startsWith("data:image", ignoreCase = true)) {
                        val bodyString = String(bytes, Charsets.UTF_8)
                        val base64Data = bodyString.substringAfter(",").trim()
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

    override fun imageUrlParse(response: Response): String = ""

    override fun imageRequest(page: Page): Request {
        return super.imageRequest(page)
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept", "application/json, text/plain, */*")
        .add("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")

    override fun popularMangaRequest(page: Int) = GET(
        "$baseUrl/api/public/content/latest-updates?limit=30&category=comics&page=$page",
        headers,
    )

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<LatestUpdatesResponse>()
        val mangas = data.data?.filter { it.type != "novel" }?.map { it.toSManga() } ?: emptyList()
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

        val data = response.parseAs<ChaptersResponse>()
        return data.data?.map { ch ->
            SChapter.create().apply {
                url = "$seriesType/$seriesId/${ch.validId}/${ch.validChapterNumber}"
                name = "الفصل ${ch.validChapterNumber}" + (if (!ch.title.isNullOrBlank()) " - ${ch.title}" else "")
                date_upload = runCatching { dateFormat.parse(ch.publishedAt ?: "")?.time }.getOrNull() ?: 0L
                chapter_number = ch.validChapterNumber.toFloatOrNull() ?: 0f
                scanlator = if (ch.lockedByCoins == true) "🔒 مدفوع" else null
            }
        } ?: emptyList()
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

        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val chapterId = response.request.url.queryParameter("_cid") ?: return emptyList()
        val pathSegments = response.request.url.pathSegments
        val idx = pathSegments.indexOf("public")
        val seriesType = pathSegments.getOrElse(idx + 1) { "manga" }
        val seriesId = pathSegments.getOrElse(idx + 2) { "0" }

        // بناء الهيكل التخطيطي الأولي وحفظه في الكاش
        val layout = getPageLayout(chapterId, seriesType, seriesId)

        val pages = mutableListOf<Page>()
        layout.forEachIndexed { index, item ->
            if (item.isScrambled) {
                // ننشئ رابطاً وهمياً يحمل الفهرس، ليتم استدعاؤه طازجاً لاحقاً
                val placeholderUrl = "$SCRAMBLED_SCHEME$chapterId/$seriesType/$seriesId/$index"
                pages.add(Page(index, url = placeholderUrl, imageUrl = placeholderUrl))
            } else {
                pages.add(Page(index, imageUrl = item.directUrl))
            }
        }

        return pages
    }

    @Synchronized
    private fun getPageLayout(chapterId: String, seriesType: String, seriesId: String): List<LayoutItem> {
        val currentTime = System.currentTimeMillis()
        // إذا كان لدينا كاش لنفس الفصل ولم يمر عليه أكثر من دقيقتين، نستخدمه لتجنب إرهاق السيرفر
        if (cachedLayoutChapterId == chapterId && (currentTime - cachedLayoutTime) < 120_000L) {
            return cachedLayout
        }

        val layout = fetchFreshPageLayout(chapterId, seriesType, seriesId)
        if (layout.isNotEmpty()) {
            cachedLayoutChapterId = chapterId
            cachedLayoutTime = currentTime
            cachedLayout = layout
        }
        return layout
    }

    private fun fetchFreshPageLayout(chapterId: String, seriesType: String, seriesId: String): List<LayoutItem> {
        val layout = mutableListOf<LayoutItem>()
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
                        GET("$baseUrl/chapter-map-session-key/$chapterId?legacy=1", headers),
                    ).execute()
                    if (req.isSuccessful) {
                        cachedSessionKey = json.decodeFromString<SessionKeyResponse>(req.body!!.string()).data?.key
                    }
                } catch (e: Exception) { }
            }
            cachedSessionKey
        }

        try {
            val url = "$baseUrl/api/public/$seriesType/$seriesId/chapters?page=1&limit=500&order=desc&_cid=$chapterId".toHttpUrl()
            val resp = client.newCall(GET(url, headers)).execute()
            if (resp.isSuccessful) {
                val currentData = json.decodeFromString<ChaptersResponse>(resp.body!!.string())
                val chaptersList = currentData.data
                for (ch in chaptersList) {
                    if (ch.validId == chapterId) {
                        cdnPath = ch.cdnPath ?: "cdn1"
                        metadataImages = ch.metadata?.images ?: emptyList()
                        ch.metadata?.maps?.let { mapsList.addAll(it) }
                        found = true
                        break
                    }
                }
            }
        } catch (e: Exception) { }

        if (!found) {
            var pg = 2
            outer@ while (pg <= 10) {
                try {
                    val resp = client.newCall(
                        GET("$baseUrl/api/public/$seriesType/$seriesId/chapters?limit=600&page=$pg&order=desc", headers),
                    ).execute()
                    if (!resp.isSuccessful) break
                    val data = json.decodeFromString<ChaptersResponse>(resp.body!!.string())
                    val pagedChapters = data.data
                    if (pagedChapters.isEmpty()) break
                    for (ch in pagedChapters) {
                        if (ch.validId == chapterId) {
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
        val mapTokens = mutableListOf<String>()

        metadataImages.forEach { imgPath ->
            val fullUrl = imgPath.toAbsoluteUrl(cdnBase)
            if (seenUrls.add(fullUrl)) {
                layout.add(LayoutItem(isScrambled = false, directUrl = fullUrl))
            }
        }

        mapsList.forEach { map ->
            val safeToken = map.token
            val safePieces = map.pieces
            
            when {
                safeToken.isNotBlank() && safePieces.isEmpty() && safeToken.isJwt() -> {
                    mapTokens.add(safeToken)
                }
                safeToken.isNotBlank() && safePieces.isEmpty() -> {
                    val resolved = resolveMap(map, chapterId, getSessionKey)
                    val resolvedPieces = resolved?.pieces ?: emptyList()
                    if (resolved != null && resolvedPieces.isNotEmpty()) {
                        addScrambledLayoutItems(resolved, cdnBase, layout)
                    }
                }
                safePieces.isNotEmpty() -> {
                    addScrambledLayoutItems(map, cdnBase, layout)
                }
            }
        }

        for (jwtToken in mapTokens) {
            try {
                val deferredItems = fetchDeferredLayoutItems(chapterId, jwtToken, seenUrls, cdnBase, getSessionKey)
                layout.addAll(deferredItems)
            } catch (e: Exception) { }
        }

        return layout
    }

    private fun addScrambledLayoutItems(map: DeferredPageMap, cdnBase: String, layout: MutableList<LayoutItem>) {
        val dim = map.dim
        val estimatedTotalH = dim.getOrNull(1)?.takeIf { it > 0 } ?: 10000
        val parts = if (estimatedTotalH > MAX_SAFE_HEIGHT) {
            ceil(estimatedTotalH.toDouble() / MAX_SAFE_HEIGHT).toInt()
        } else {
            1
        }

        for (p in 0 until parts) {
            layout.add(LayoutItem(
                isScrambled = true,
                mapData = map,
                cdnBase = cdnBase,
                splitPart = p,
                totalParts = parts
            ))
        }
    }

    private fun fetchDeferredLayoutItems(
        chapterId: String,
        jwtToken: String,
        seenUrls: MutableSet<String>,
        cdnBase: String,
        getSessionKey: () -> String?
    ): List<LayoutItem> {
        val items = mutableListOf<LayoutItem>()
        val jwtSplit = jwtSplitValue(jwtToken)
        val splitResponses = mutableListOf<ChapterDeferredData>()

        for (s in 0..jwtSplit) {
            try {
                val resp = client.newCall(
                    GET("$baseUrl/chapter-deferred-media/$chapterId?token=$jwtToken&split=$s", headers),
                ).execute()

                if (!resp.isSuccessful) continue
                val parsed = json.decodeFromString<ChapterDeferredResponse>(resp.body!!.string())
                parsed.data?.let { splitResponses.add(it) }
            } catch (e: Exception) {
                continue
            }
        }

        for (splitData in splitResponses) {
            val decryptedMaps = mutableListOf<DeferredPageMap>()
            val absolutePieceUrls = mutableSetOf<String>()

            val safeMaps = splitData.maps
            safeMaps.forEach { map ->
                val safeToken = map.token
                val safePieces = map.pieces
                when {
                    safeToken.isNotBlank() && safePieces.isEmpty() && safeToken.isJwt() -> { }
                    else -> {
                        val resolved = resolveMap(map, chapterId, getSessionKey)
                        val resolvedPieces = resolved?.pieces ?: emptyList()
                        if (resolved != null && resolvedPieces.isNotEmpty()) {
                            decryptedMaps.add(resolved)
                            absolutePieceUrls.addAll(resolvedPieces.map { it.toAbsoluteUrl(cdnBase) })
                        }
                    }
                }
            }

            val safeImages = splitData.images
            safeImages.forEach { url ->
                val fullUrl = url.toAbsoluteUrl(cdnBase)
                if (fullUrl !in absolutePieceUrls && seenUrls.add(fullUrl)) {
                    items.add(LayoutItem(isScrambled = false, directUrl = fullUrl))
                }
            }

            decryptedMaps.forEach { map ->
                addScrambledLayoutItems(map, cdnBase, items)
            }
        }

        return items
    }

    private fun reconstructPageWithFreshMap(map: DeferredPageMap, cdnBase: String, splitPart: Int, totalParts: Int): ByteArray? {
        val pieces = map.pieces
        if (pieces.isEmpty()) return null

        val (cols, rows) = parseMode(map.mode, pieces.size)
        val bitmaps = arrayOfNulls<Bitmap>(pieces.size)
        val absolutePieces = pieces.map { it.toAbsoluteUrl(cdnBase) }
        val signedToken = map.token

        // تركيب روابط القطع بالتوكن الطازج المجلوب حالاً
        val pieceUrls = Array(absolutePieces.size) { i ->
            val base = absolutePieces[i]
            if (base.isNotBlank() && signedToken.isNotBlank() && !base.contains("/i/eyJ2IjoxLCJpdiI6IJ")) {
                if (base.contains("?")) "$base&token=$signedToken" else "$base?token=$signedToken"
            } else {
                base
            }
        }

        // تحميل القطع بالتوازي/التوالي والمزامنة مع الجلسة الحالية لتخطي الحظر
        for (targetIdx in pieceUrls.indices) {
            val pieceUrl = pieceUrls[targetIdx]
            if (pieceUrl.isBlank()) return null

            val req = Request.Builder()
                .url(pieceUrl)
                .headers(headers)
                .build()

            try {
                val response = client.newCall(req).execute()
                if (!response.isSuccessful) {
                    response.close()
                    return null
                }
                val bodyBytes = response.body?.bytes()
                response.close()

                if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                    bitmaps[targetIdx] = decodeAvif(bodyBytes)
                }
            } catch (e: Exception) {
                return null
            }
        }

        val validBitmaps = bitmaps.filterNotNull()
        if (validBitmaps.size != bitmaps.size) return null

        var calcTotalW: Int
        var calcTotalH: Int

        when {
            cols == 1 -> {
                calcTotalW = map.dim.getOrNull(0)?.takeIf { it > 0 } ?: validBitmaps.maxOf { it.width }
                calcTotalH = map.dim.getOrNull(1)?.takeIf { it > 0 } ?: validBitmaps.sumOf { it.height }
            }
            rows == 1 -> {
                calcTotalW = map.dim.getOrNull(0)?.takeIf { it > 0 } ?: validBitmaps.sumOf { it.width }
                calcTotalH = map.dim.getOrNull(1)?.takeIf { it > 0 } ?: validBitmaps.maxOf { it.height }
            }
            else -> {
                val firstBmp = validBitmaps.first()
                calcTotalW = map.dim.getOrNull(0)?.takeIf { it > 0 } ?: (firstBmp.width * cols)
                calcTotalH = map.dim.getOrNull(1)?.takeIf { it > 0 } ?: (firstBmp.height * rows)
            }
        }

        val partH = calcTotalH / totalParts
        val actualPartH = if (splitPart == totalParts - 1) calcTotalH - (partH * splitPart) else partH

        if (calcTotalW <= 0 || actualPartH <= 0) return null

        val result = try {
            Bitmap.createBitmap(calcTotalW, actualPartH, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Bitmap.createBitmap(calcTotalW, actualPartH, Bitmap.Config.RGB_565)
        }
        val canvas = Canvas(result)
        canvas.translate(0f, -(splitPart * partH).toFloat())

        val order = map.order

        when {
            cols == 1 -> {
                var currentY = 0f
                for (i in bitmaps.indices) {
                    val bmp = bitmaps[i] ?: continue
                    canvas.drawBitmap(bmp, 0f, currentY, null)
                    currentY += bmp.height
                    bmp.recycle()
                }
            }
            rows == 1 -> {
                var currentX = 0f
                for (i in bitmaps.indices) {
                    val bmp = bitmaps[i] ?: continue
                    canvas.drawBitmap(bmp, currentX, 0f, null)
                    currentX += bmp.width
                    bmp.recycle()
                }
            }
            else -> {
                val tileW = validBitmaps.first().width
                val tileH = validBitmaps.first().height
                for (i in bitmaps.indices) {
                    val bmp = bitmaps[i] ?: continue
                    val actualPos = if (order.size == bitmaps.size) order[i] else i
                    val col = actualPos % cols
                    val row = actualPos / cols
                    canvas.drawBitmap(bmp, (col * tileW).toFloat(), (row * tileH).toFloat(), null)
                    bmp.recycle()
                }
            }
        }

        val out = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, 85, out)
        result.recycle()
        return out.toByteArray()
    }

    private fun String.isJwt(): Boolean =
        startsWith("eyJhbGci") && count { it == '.' } == 2
    
    private fun jwtSplitValue(jwtToken: String): Int {
        return try {
            val payloadSegment = jwtToken.split(".").getOrNull(1) ?: return DEFAULT_SPLIT
            val padded = payloadSegment.padEnd(
                payloadSegment.length + (4 - payloadSegment.length % 4) % 4,
                '=',
            )
            val payloadJson = String(Base64.decode(padded, Base64.URL_SAFE))
            json.decodeFromString<JwtPayload>(payloadJson).split
        } catch (e: Exception) {
            DEFAULT_SPLIT
        }
    }

    private fun resolveMap(
        map: DeferredPageMap,
        chapterId: String,
        getSessionKey: () -> String?,
    ): DeferredPageMap? {
        val safePieces = map.pieces
        val safeToken = map.token
        if (safePieces.isNotEmpty()) return map

        if (safeToken.isNotBlank()) {
            var dec: DeferredPageMap? = null

            val sk = getSessionKey()
            if (sk != null) dec = decryptMap(safeToken, sk)

            if (dec == null || dec.pieces.isEmpty()) {
                try {
                    val bodyStr = json.encodeToString(map)
                    val body = bodyStr.toRequestBody("application/json".toMediaType())
                    val proxyReq = POST(
                        "$baseUrl/chapter-map-proxy-plan/$chapterId",
                        headers,
                        body,
                    )
                    val proxyResp = client.newCall(proxyReq).execute()
                    if (proxyResp.isSuccessful) dec = json.decodeFromString<ProxyPlanResponse>(proxyResp.body!!.string()).data?.map
                } catch (e: Exception) { }
            }
            return dec
        }
        return null
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
        mode.startsWith("vertical_") -> Pair(1, mode.removePrefix("vertical_").toIntOrNull() ?: pieceCount)
        mode.startsWith("horizontal_") -> Pair(mode.removePrefix("horizontal_").toIntOrNull() ?: pieceCount, 1)
        else -> Pair(1, pieceCount)
    }

    private inline fun <reified T> Response.parseAs(): T =
        json.decodeFromString(body!!.string())
}

// كلاس داخلي لتنظيم الهيكل التخطيطي للصفحات
data class LayoutItem(
    val isScrambled: Boolean,
    val directUrl: String = "",
    val mapData: DeferredPageMap? = null,
    val cdnBase: String = "",
    val splitPart: Int = 0,
    val totalParts: Int = 1
)

private const val DEFAULT_SPLIT = 3

@Serializable
data class JwtPayload(
    val split: Int = DEFAULT_SPLIT,
    val cid: JsonElement? = null,
    val p: String = "",
)

@Serializable
data class SessionKeyResponse(
    val success: Boolean? = false,
    val data: SessionKeyData? = null,
)

@Serializable
data class SessionKeyData(val key: String? = "")

@Serializable
data class EncryptedToken(
    val v: Int = 3,
    val m: String = "",
    val cid: JsonElement? = null,
    val iv: String = "",
    val tag: String = "",
    val data: String = "",
)

@Serializable
data class LatestUpdatesResponse(
    val success: Boolean? = false,
    val data: List<SeriesDto>? = emptyList(),
)

@Serializable
data class SeriesDto(
    @SerialName("mangaId") val id: JsonElement? = null,
    @SerialName("mangaSlug") val slug: String? = "",
    @SerialName("mangaTitle") val title: String? = "",
    val coverImage: String? = null,
    val type: String? = "manga",
    val coverImageApp: CoverImageApp? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = "${type ?: "manga"}/${id?.jsonPrimitive?.contentOrNull ?: "0"}/$slug"
        title = this@SeriesDto.title ?: ""
        thumbnail_url = coverImageApp?.card?.mobile ?: coverImageApp?.desktop ?: coverImage
    }
}

@Serializable
data class CoverImageApp(val desktop: String? = null, val card: CardImages? = null)

@Serializable
data class CardImages(val mobile: String? = null, val desktop: String? = null)

@Serializable
data class SeriesDetailResponse(
    val id: JsonElement? = null,
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
    val id: JsonElement? = null,
    @SerialName("chapter_number") val chapterNumber: JsonElement? = null,
    val title: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val lockedByCoins: Boolean? = null,
    @SerialName("cdn_path") val cdnPath: String? = null,
    val metadata: ChapterMetadataDto? = null,
) {
    val validId: String get() = id?.jsonPrimitive?.contentOrNull ?: "0"
    val validChapterNumber: String get() = chapterNumber?.jsonPrimitive?.contentOrNull ?: "0"
}

@Serializable
data class ChapterMetadataDto(
    val images: List<String> = emptyList(),
    val maps: List<DeferredPageMap> = emptyList(),
)

@Serializable
data class ChapterDeferredResponse(
    val success: Boolean = false,
    val data: ChapterDeferredData? = null,
)

@Serializable
data class ChapterDeferredData(
    val chapterId: JsonElement? = null,
    val splitIndex: Int = 0,
    val images: List<String> = emptyList(),
    val maps: List<DeferredPageMap> = emptyList(),
)

@Serializable
data class DeferredPageMap(
    val dim: List<Int> = emptyList(),
    val mode: String = "",
    val pieces: List<String> = emptyList(),
    val order: List<Int> = emptyList(),
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
