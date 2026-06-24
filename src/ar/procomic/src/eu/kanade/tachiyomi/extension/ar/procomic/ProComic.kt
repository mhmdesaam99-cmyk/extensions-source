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
import okhttp3.Interceptor
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
import java.util.concurrent.ConcurrentHashMap
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

    // ذاكرة كاش داخلية فائقة السرعة وآمنة لمنع تضخم روابط الصور وضمان التقاطها داخل الـ Interceptor
    private val jitCache = ConcurrentHashMap<String, JitPage>()

    companion object {
        private const val SCRAMBLED_SCHEME = "https://procomic.pro/__jit_asset__/"
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

            // التحقق من أن الطلب يخص روابط التجميع التمويهية الخاصة بنا
            if (url.startsWith(SCRAMBLED_SCHEME)) {
                val cacheKey = url.substringBefore(".jpg").substringAfter(SCRAMBLED_SCHEME, "")
                val jitPage = request.tag(JitPage::class.java) ?: jitCache[cacheKey]

                if (jitPage == null) {
                    return@addInterceptor Response.Builder()
                        .request(request).protocol(Protocol.HTTP_1_1)
                        .code(400).message("Missing JIT Metadata Cache Key")
                        .body("".toResponseBody(null)).build()
                }

                val apiHeaders = Headers.Builder()
                    .add("Referer", "$baseUrl/")
                    .add("Origin", baseUrl)
                    .add("Accept", "application/json")
                    .add("User-Agent", headers["User-Agent"] ?: "Mozilla/5.0")
                    .build()

                // الخطوة العبقرية: جلب نسخة طازجة وحية فوراً من الفصل للحصول على توكن لم يمر عليه بايت واحد
                val freshChapter = fetchFreshChapterDto(jitPage.chapterId, jitPage.seriesType, jitPage.seriesId, apiHeaders, chain)
                if (freshChapter == null) {
                    return@addInterceptor Response.Builder()
                        .request(request).protocol(Protocol.HTTP_1_1)
                        .code(500).message("Failed to fetch fresh live token from API")
                        .body("".toResponseBody(null)).build()
                }

                val fetchedBytes = when (jitPage.sourceType) {
                    3 -> { // تحميل وفك تشفير الصور العادية فوراً وبثها للتطبيق
                        try {
                            val imgReq = Request.Builder().url(jitPage.pageUrl).headers(headers).build()
                            val imgResp = chain.proceed(imgReq)
                            if (imgResp.isSuccessful) {
                                val bodyBytes = imgResp.body.bytes()
                                val isBase64Text = bodyBytes.size > 20 &&
                                    bodyBytes[0] == 'd'.code.toByte() &&
                                    bodyBytes[1] == 'a'.code.toByte() &&
                                    bodyBytes[2] == 't'.code.toByte() &&
                                    bodyBytes[3] == 'a'.code.toByte() &&
                                    bodyBytes[4] == ':'.code.toByte()

                                if (isBase64Text) {
                                    val bodyString = String(bodyBytes)
                                    val base64Data = bodyString.substringAfter("base64,")
                                    Base64.decode(base64Data, Base64.DEFAULT)
                                } else {
                                    bodyBytes
                                }
                            } else null
                        } catch (e: Exception) { null }
                    }
                    1 -> { // معالجة الخرائط القياسية المباشرة بتوكن جديد تماماً
                        val freshMap = freshChapter.metadata?.maps?.getOrNull(jitPage.mapIndex)
                        if (freshMap != null) {
                            var cachedSessionKey: String? = null
                            val skProvider = {
                                if (cachedSessionKey == null) {
                                    try {
                                        val req = chain.proceed(Request.Builder().url("$baseUrl/chapter-map-session-key/${jitPage.chapterId}?legacy=1").headers(apiHeaders).build())
                                        if (req.isSuccessful) cachedSessionKey = json.decodeFromString<SessionKeyResponse>(req.body.string()).data?.key
                                    } catch (e: Exception) {}
                                }
                                cachedSessionKey
                            }
                            val freshResolved = resolveMapJit(freshMap, jitPage.chapterId, apiHeaders, skProvider, chain)
                            if (freshResolved != null && freshResolved.pieces.isNotEmpty()) {
                                val scrambled = ScrambledMap(
                                    dim = freshResolved.dim,
                                    mode = freshResolved.mode,
                                    pieces = freshResolved.pieces.map { it.toAbsoluteUrl(jitPage.cdnBase) },
                                    order = freshResolved.order,
                                    signedToken = freshResolved.token,
                                    splitPart = jitPage.splitPart,
                                    totalParts = jitPage.totalParts,
                                )
                                reconstructPage(scrambled, chain)
                            } else null
                        } else null
                    }
                    2 -> { // معالجة الخرائط المؤجلة (Deferred Media) عبر فك توكن الـ JWT الحي
                        val freshMaps = freshChapter.metadata?.maps ?: emptyList()
                        val jwtTokens = freshMaps.filter { it.token.isNotBlank() && it.pieces.isEmpty() && it.token.isJwt() }.map { it.token }
                        val freshJwtToken = jwtTokens.getOrNull(jitPage.jwtTokenIndex)

                        if (freshJwtToken != null) {
                            var targetMap: DeferredPageMap? = null
                            try {
                                val resp = chain.proceed(Request.Builder().url("$baseUrl/chapter-deferred-media/${jitPage.chapterId}?token=$freshJwtToken&split=${jitPage.deferredSplit}").headers(apiHeaders).build())
                                if (resp.isSuccessful) {
                                    val parsed = json.decodeFromString<ChapterDeferredResponse>(resp.body.string())
                                    targetMap = parsed.data?.maps?.getOrNull(jitPage.mapIndex)
                                }
                            } catch (e: Exception) {}

                            if (targetMap != null) {
                                var cachedSessionKey: String? = null
                                val skProvider = {
                                    if (cachedSessionKey == null) {
                                        try {
                                            val req = chain.proceed(Request.Builder().url("$baseUrl/chapter-map-session-key/${jitPage.chapterId}?legacy=1").headers(apiHeaders).build())
                                            if (req.isSuccessful) cachedSessionKey = json.decodeFromString<SessionKeyResponse>(req.body.string()).data?.key
                                        } catch (e: Exception) {}
                                    }
                                    cachedSessionKey
                                }
                                val freshResolved = resolveMapJit(targetMap, jitPage.chapterId, apiHeaders, skProvider, chain)
                                if (freshResolved != null && freshResolved.pieces.isNotEmpty()) {
                                    val scrambled = ScrambledMap(
                                        dim = freshResolved.dim,
                                        mode = freshResolved.mode,
                                        pieces = freshResolved.pieces.map { it.toAbsoluteUrl(jitPage.cdnBase) },
                                        order = freshResolved.order,
                                        signedToken = freshResolved.token,
                                        splitPart = jitPage.splitPart,
                                        totalParts = jitPage.totalParts,
                                    )
                                    reconstructPage(scrambled, chain)
                                } else null
                            } else null
                        } else null
                    }
                    else -> null
                }

                if (fetchedBytes != null) {
                    return@addInterceptor Response.Builder()
                        .request(request).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .body(fetchedBytes.toResponseBody("image/jpeg".toMediaType()))
                        .build()
                } else {
                    return@addInterceptor Response.Builder()
                        .request(request).protocol(Protocol.HTTP_1_1)
                        .code(500).message("JIT Reconstruction Failed Object")
                        .body("".toResponseBody(null)).build()
                }
            }

            chain.proceed(request)
        }
        .build()

    private fun fetchFreshChapterDto(
        chapterId: String,
        seriesType: String,
        seriesId: String,
        apiHeaders: Headers,
        chain: Interceptor.Chain
    ): ChapterDto? {
        try {
            val url = "$baseUrl/api/public/$seriesType/$seriesId/chapters?limit=500&order=desc&_cid=$chapterId"
            val req = Request.Builder().url(url).headers(apiHeaders).build()
            val resp = chain.proceed(req)
            if (resp.isSuccessful) {
                val data = json.decodeFromString<ChaptersResponse>(resp.body.string())
                return data.data.find { it.id.toString() == chapterId }
            }
        } catch (e: Exception) {}
        return null
    }

    override fun imageUrlParse(response: Response): String = ""

    override fun imageRequest(page: Page): Request {
        val request = super.imageRequest(page)
        val cacheKey = page.url.substringBefore(".jpg").substringAfter(SCRAMBLED_SCHEME, "")
        if (cacheKey.isNotBlank()) {
            val jitPage = jitCache[cacheKey]
            if (jitPage != null) {
                return request.newBuilder().tag(JitPage::class.java, jitPage).build()
            }
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
        val parts = response.request.url.pathSegments
        val idx = parts.indexOf("public")
        val seriesType = parts.getOrElse(idx + 1) { "manga" }
        val seriesId = parts.getOrElse(idx + 2) { "0" }

        val apiHeaders = headers.newBuilder().set("Accept", "application/json").build()
        val pages = mutableListOf<Page>()

        // تنظيف ذاكرة الـ الكاش الخاصة بهذا الفصل لمنع استهلاك الرام
        jitCache.keys.removeAll { it.startsWith("$chapterId-") }

        val currentData = try { response.parseAs<ChaptersResponse>() } catch (e: Exception) { ChaptersResponse() }
        var currentChapter: ChapterDto? = currentData.data.find { it.id.toString() == chapterId }

        if (currentChapter == null) {
            var pg = 2
            while (pg <= 5) {
                try {
                    val resp = client.newCall(GET("$baseUrl/api/public/$seriesType/$seriesId/chapters?limit=100&page=$pg&order=desc", apiHeaders)).execute()
                    if (!resp.isSuccessful) break
                    val data = resp.parseAs<ChaptersResponse>()
                    val found = data.data.find { it.id.toString() == chapterId }
                    if (found != null) {
                        currentChapter = found
                        break
                    }
                    if (data.data.isEmpty()) break
                } catch (e: Exception) { break }
                pg++
            }
        }

        if (currentChapter == null) return emptyList()

        val cdnPath = currentChapter.cdnPath ?: "cdn1"
        val cdnBase = "https://$cdnPath.procomic.pro"
        val metadataImages = currentChapter.metadata?.images ?: emptyList()
        val mapsList = currentChapter.metadata?.maps ?: emptyList()

        var pageIndex = 0

        // 1. صناعة روابط تمويهية للصور العادية وحفظها في الكاش الداخلي
        metadataImages.forEachIndexed { imgIdx, imgPath ->
            val fullUrl = imgPath.toAbsoluteUrl(cdnBase)
            val cacheKey = "$chapterId-img-$imgIdx"
            jitCache[cacheKey] = JitPage(chapterId, seriesType, seriesId, cdnBase, sourceType = 3, pageUrl = fullUrl)
            
            val shortUrl = "$SCRAMBLED_SCHEME$cacheKey.jpg"
            pages.add(Page(pageIndex, url = shortUrl, imageUrl = shortUrl))
            pageIndex++
        }

        // 2. صناعة روابط تمويهية للخرائط العادية وحفظها في الكاش الداخلي
        mapsList.forEachIndexed { mapIdx, map ->
            var cachedSessionKey: String? = null
            val getSessionKey = {
                if (cachedSessionKey == null) {
                    try {
                        val req = client.newCall(GET("$baseUrl/chapter-map-session-key/$chapterId?legacy=1", apiHeaders)).execute()
                        if (req.isSuccessful) cachedSessionKey = json.decodeFromString<SessionKeyResponse>(req.body.string()).data?.key
                    } catch (e: Exception) {}
                }
                cachedSessionKey
            }
            val resolved = resolveMap(map, chapterId, apiHeaders, getSessionKey) ?: map
            val estimatedTotalH = resolved.dim.getOrNull(1)?.takeIf { it > 0 } ?: 8000
            val parts = if (estimatedTotalH > MAX_SAFE_HEIGHT) ceil(estimatedTotalH.toDouble() / MAX_SAFE_HEIGHT).toInt() else 1

            for (p in 0 until parts) {
                val cacheKey = "$chapterId-map-$mapIdx-p-$p"
                jitCache[cacheKey] = JitPage(
                    chapterId = chapterId, seriesType = seriesType, seriesId = seriesId, cdnBase = cdnBase,
                    sourceType = 1, mapIndex = mapIdx, splitPart = p, totalParts = parts
                )
                val shortUrl = "$SCRAMBLED_SCHEME$cacheKey.jpg"
                pages.add(Page(pageIndex, url = shortUrl, imageUrl = shortUrl))
                pageIndex++
            }
        }

        // 3. صناعة روابط تمويهية للخرائط المؤجلة (Deferred Media) وحفظها في الكاش الداخلي
        val mapTokens = mapsList.filter { it.token.isNotBlank() && it.pieces.isEmpty() && it.token.isJwt() }.map { it.token }
        var deferredMapGlobalIdx = 0

        mapTokens.forEachIndexed { jwtIdx, jwtToken ->
            val jwtSplit = jwtSplitValue(jwtToken)
            for (s in 0..jwtSplit) {
                try {
                    val resp = client.newCall(GET("$baseUrl/chapter-deferred-media/$chapterId?token=$jwtToken&split=$s", apiHeaders)).execute()
                    if (!resp.isSuccessful) continue
                    val splitData = resp.parseAs<ChapterDeferredResponse>().data ?: continue

                    splitData.images.forEach { imgPath ->
                        val fullUrl = imgPath.toAbsoluteUrl(cdnBase)
                        val cacheKey = "$chapterId-defimg-$pageIndex"
                        jitCache[cacheKey] = JitPage(chapterId, seriesType, seriesId, cdnBase, sourceType = 3, pageUrl = fullUrl)
                        val shortUrl = "$SCRAMBLED_SCHEME$cacheKey.jpg"
                        pages.add(Page(pageIndex, url = shortUrl, imageUrl = shortUrl))
                        pageIndex++
                    }

                    splitData.maps.forEachIndexed { localMapIdx, map ->
                        var cachedSessionKey: String? = null
                        val getSessionKey = {
                            if (cachedSessionKey == null) {
                                try {
                                    val req = client.newCall(GET("$baseUrl/chapter-map-session-key/$chapterId?legacy=1", apiHeaders)).execute()
                                    if (req.isSuccessful) cachedSessionKey = json.decodeFromString<SessionKeyResponse>(req.body.string()).data?.key
                                } catch (e: Exception) {}
                            }
                            cachedSessionKey
                        }
                        val resolved = resolveMap(map, chapterId, apiHeaders, getSessionKey) ?: map
                        val estimatedTotalH = resolved.dim.getOrNull(1)?.takeIf { it > 0 } ?: 8000
                        val parts = if (estimatedTotalH > MAX_SAFE_HEIGHT) ceil(estimatedTotalH.toDouble() / MAX_SAFE_HEIGHT).toInt() else 1

                        for (p in 0 until parts) {
                            val cacheKey = "$chapterId-defmap-$deferredMapGlobalIdx-p-$p"
                            jitCache[cacheKey] = JitPage(
                                chapterId = chapterId, seriesType = seriesType, seriesId = seriesId, cdnBase = cdnBase,
                                sourceType = 2, mapIndex = localMapIdx, splitPart = p, totalParts = parts,
                                deferredSplit = s, jwtTokenIndex = jwtIdx
                            )
                            val shortUrl = "$SCRAMBLED_SCHEME$cacheKey.jpg"
                            pages.add(Page(pageIndex, url = shortUrl, imageUrl = shortUrl))
                            pageIndex++
                        }
                        deferredMapGlobalIdx++
                    }
                } catch (e: Exception) {}
            }
        }

        return pages
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
                    val bodyStr = json.encodeToString(map)
                    val body = bodyStr.toRequestBody("application/json".toMediaType())
                    val proxyReq = POST("$baseUrl/chapter-map-proxy-plan/$chapterId", apiHeaders, body)
                    val proxyResp = client.newCall(proxyReq).execute()
                    if (proxyResp.isSuccessful) dec = proxyResp.parseAs<ProxyPlanResponse>().data?.map
                } catch (e: Exception) { }
            }
            return dec
        }
        return null
    }

    private fun resolveMapJit(
        map: DeferredPageMap,
        chapterId: String,
        apiHeaders: Headers,
        skProvider: () -> String?,
        chain: Interceptor.Chain
    ): DeferredPageMap? {
        if (map.token.isNotBlank()) {
            val sk = skProvider()
            var dec = if (sk != null) decryptMap(map.token, sk) else null
            if (dec == null || dec.pieces.isEmpty()) {
                try {
                    val bodyStr = json.encodeToString(map)
                    val body = bodyStr.toRequestBody("application/json".toMediaType())
                    val proxyReq = Request.Builder()
                        .url("$baseUrl/chapter-map-proxy-plan/$chapterId")
                        .post(body)
                        .headers(apiHeaders)
                        .build()
                    val proxyResp = chain.proceed(proxyReq)
                    if (proxyResp.isSuccessful) {
                        dec = json.decodeFromString<ProxyPlanResponse>(proxyResp.body.string()).data?.map
                    }
                } catch (e: Exception) {}
            }
            return dec
        }
        return map
    }

    private fun reconstructPage(map: ScrambledMap, chain: Interceptor.Chain): ByteArray? {
        if (map.pieces.isEmpty()) return null

        val (cols, rows) = parseMode(map.mode, map.pieces.size)
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
                val resp = chain.proceed(req)
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
            } catch (e: Exception) { }
        }

        return try {
            val validBitmaps = bitmaps.filterNotNull()
            if (validBitmaps.isEmpty()) return null

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

            val totalParts = map.totalParts ?: 1
            val splitPart = map.splitPart ?: 0
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

private const val DEFAULT_SPLIT = 3

@Serializable
data class JitPage(
    val chapterId: String,
    val seriesType: String,
    val seriesId: String,
    val cdnBase: String,
    val sourceType: Int, // 1 = خارطة مباشرة، 2 = خارطة مؤجلة، 3 = صورة ثابتة
    val mapIndex: Int = -1,
    val pageUrl: String = "",
    val splitPart: Int = 0,
    val totalParts: Int = 1,
    val deferredSplit: Int = 0,
    val jwtTokenIndex: Int = 0
)

@Serializable
data class JwtPayload(
    val split: Int = DEFAULT_SPLIT,
    val cid: Int = 0,
    val p: String = "",
)

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
    val cid: Int = 0,
    val iv: String = "",
    val tag: String = "",
    val data: String = "",
)

@Serializable
data class ScrambledMap(
    val dim: List<Int> = emptyList(),
    val mode: String = "",
    val pieces: List<String> = emptyList(),
    val order: List<Int> = emptyList(),
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
data class ChapterDeferredResponse(
    val success: Boolean = false,
    val data: ChapterDeferredData? = null,
)

@Serializable
data class ChapterDeferredData(
    val chapterId: Int = 0,
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
