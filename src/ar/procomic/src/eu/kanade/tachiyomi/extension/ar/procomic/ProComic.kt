package eu.kanade.tachiyomi.extension.ar.procomic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
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
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

class ProComic : HttpSource() {

    override val name = "ProComic"

    override val baseUrl = "https://procomic.pro"

    override val lang = "ar"

    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(2)
        .addInterceptor(ScrambledImageInterceptor())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val pieceClient: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", DEFAULT_USER_AGENT)
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    private val apiHeaders: Headers by lazy {
        headersBuilder()
            .add("Accept", "application/json")
            .add("Content-Type", "application/json")
            .build()
    }

    // ============================== POPULAR ==============================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/api/public/content/latest-updates".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "18")
            .addQueryParameter("category", "comics")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val res = json.decodeFromStream<ApiResponse<List<MangaDto>>>(response.body.byteStream())
        val data = res.data ?: return MangasPage(emptyList(), false)
        val mangas = data.map { dto ->
            SManga.create().apply {
                url = "/series/${dto.mangaSlug}"
                title = dto.mangaTitle
                thumbnail_url = dto.coverImage?.let { parseCover(it) }
            }
        }
        val hasNextPage = data.size >= 18
        return MangasPage(mangas, hasNextPage)
    }

    // ============================== LATEST ===============================

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // =============================== SEARCH ==============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/api/public/content/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("limit", "18")
                .build()
            return GET(url, headers)
        }
        val url = "$baseUrl/api/public/content/filter".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "18")
            .addQueryParameter("page", page.toString())

        filters.forEach { filter ->
            when (filter) {
                is CategoryFilter -> {
                    val cat = filter.toQuery()
                    if (cat.isNotBlank()) url.addQueryParameter("category", cat)
                }
                is StatusFilter -> {
                    val stat = filter.toQuery()
                    if (stat.isNotBlank()) url.addQueryParameter("status", stat)
                }
                is TypeFilter -> {
                    val t = filter.toQuery()
                    if (t.isNotBlank()) url.addQueryParameter("type", t)
                }
                is SortFilter -> {
                    val sort = filter.toQuery()
                    if (sort.isNotBlank()) url.addQueryParameter("sort", sort)
                }
                is GenreFilter -> {
                    filter.state.filter { it.state }.forEach {
                        url.addQueryParameter("genres[]", it.id)
                    }
                }
                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // =========================== MANGA DETAILS ===========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("$baseUrl${manga.url}", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val html = response.body.string()
        val props = parseNextDataProps(html) ?: throw Exception("ProComic: Unable to parse page props")
        
        return SManga.create().apply {
            title = props.mangaTitle ?: props.title ?: ""
            description = props.description
            thumbnail_url = props.coverImage?.let { parseCover(it) }
            author = props.author
            artist = props.artist
            genre = props.tags?.joinToString { it.name }
            status = when (props.status?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // =========================== CHAPTER LIST ============================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val html = response.body.string()
        val props = parseNextDataProps(html) ?: throw Exception("ProComic: Unable to parse chapter page props")
        
        val seriesSlug = props.mangaSlug ?: props.series?.slug ?: throw Exception("ProComic: Series slug missing")
        val chaptersList = props.chapters ?: props.series?.chapters ?: return emptyList()

        return chaptersList.map { dto ->
            SChapter.create().apply {
                url = "/series/$seriesSlug/${dto.slug}#${dto.id}"
                name = "الفصل ${dto.chapterNumber}"
                date_upload = dto.publishedAt?.let { parseDate(it) } ?: 0L
            }
        }.reversed()
    }

    // ============================ PAGE LIST ==============================

    override fun pageListRequest(chapter: SChapter): Request {
        val cleanUrl = chapter.url.substringBefore("#")
        return GET("$baseUrl$cleanUrl", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body.string()
        val requestUrl = response.request.url.toString()
        val chapterId = resolveChapterId(requestUrl, html) ?: throw Exception("ProComic: Cannot resolve chapterId")

        val props = parseNextDataProps(html) ?: throw Exception("ProComic: Missing __NEXT_DATA__")
        val token = props.token ?: props.chapterToken ?: props.jwt ?: props.accessToken ?: props.chapter?.token 
            ?: throw Exception("ProComic: Access token not found")

        val splitIndex = props.splitIndex ?: DEFAULT_SPLIT

        val deferredUrl = "$baseUrl/chapter-deferred-media/$chapterId".toHttpUrl().newBuilder()
            .addQueryParameter("token", token)
            .addQueryParameter("split", splitIndex.toString())
            .build()

        val deferredResponse = client.newCall(GET(deferredUrl, apiHeaders)).execute()
        if (!deferredResponse.isSuccessful) {
            deferredResponse.close()
            throw Exception("ProComic: Deferred API HTTP ${deferredResponse.code}")
        }

        val deferredResult = json.decodeFromStream<ChapterDeferredResponse>(deferredResponse.body.byteStream())
        if (!deferredResult.success || deferredResult.data == null) {
            throw Exception("ProComic: Deferred API indicated failure")
        }

        val data = deferredResult.data
        val pages = mutableListOf<Page>()

        if (!data.images.isNullOrEmpty()) {
            data.images.forEachIndexed { index, imgUrl ->
                pages.add(Page(index, "", imgUrl))
            }
        } else if (!data.maps.isNullOrEmpty()) {
            data.maps.forEachIndexed { index, mapDto ->
                val pageUrl = buildScrambledUrl(chapterId, mapDto.token)
                pages.add(Page(index, "", pageUrl))
            }
        } else {
            throw Exception("ProComic: Both images and maps arrays are empty or missing")
        }

        return pages
    }

    // ============================ IMAGE REQUEST ==========================

    override fun imageRequest(page: Page): Request {
        val url = page.imageUrl!!
        return if (url.startsWith(SCRAMBLED_SCHEME)) {
            val proxyUrl = "$baseUrl/procomic-image-proxy?d=${URLEncoder.encode(url, "UTF-8")}"
            Request.Builder().url(proxyUrl).headers(headers).build()
        } else {
            GET(url, headers)
        }
    }

    override fun imageUrlParse(response: Response): String = ""

    // ============================== FILTERS ==============================

    override fun getFilterList(): FilterList = FilterList(
        SortFilter(),
        CategoryFilter(),
        TypeFilter(),
        StatusFilter(),
        GenreFilter(getGenres()),
    )

    // ============================== HELPERS ==============================

    private fun buildScrambledUrl(chapterId: String, pageToken: String): String {
        return "$SCRAMBLED_SCHEME$chapterId?token=${URLEncoder.encode(pageToken, "UTF-8")}"
    }

    private fun resolveChapterId(url: String, html: String): String? {
        val anchor = url.substringAfter("#", "")
        if (anchor.isNotBlank() && anchor.all { it.isDigit() }) return anchor

        val props = parseNextDataProps(html) ?: return null
        return props.chapterId?.toString()
            ?: props.chapter?.id?.toString()
            ?: props.id?.toString()
            ?: props.chapter?.chapterId?.toString()
    }

    private fun parseNextDataProps(html: String): PageProps? {
        val script = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data() ?: return null
        val nextData = json.decodeFromString<NextData>(script)
        return nextData.props?.pageProps
    }

    private fun parseCover(path: String): String {
        return if (path.startsWith("http")) path else "https://cdn2.procomic.pro$path"
    }

    private fun parseDate(dateStr: String): Long = try {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse(dateStr)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }

    // =========================================================================
    //  ScrambledImageInterceptor (المعالج المحسن والشامل بنسبة 100%)
    // =========================================================================

    inner class ScrambledImageInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val rawUrl = request.url.toString()

            // الحماية والحل الذكي 1: إذا كان الطلب مباشر للمانجا ولكن الخادم أرجع نص Base64
            if (!rawUrl.contains("/procomic-image-proxy?d=")) {
                val originalResponse = chain.proceed(request)
                val contentType = originalResponse.header("Content-Type") ?: ""
                
                if (contentType.contains("text") || contentType.contains("json") || contentType.isEmpty()) {
                    val bodyBytes = originalResponse.body.bytes()
                    val sample = String(bodyBytes.take(50).toByteArray(), Charsets.US_ASCII)
                    
                    if (sample.startsWith("data:image")) {
                        val base64Data = String(bodyBytes, Charsets.UTF_8).substringAfter(",")
                        val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                        return originalResponse.newBuilder()
                            .body(decodedBytes.toResponseBody("image/avif".toMediaType()))
                            .build()
                    }
                    return originalResponse.newBuilder().body(bodyBytes.toResponseBody(originalResponse.body.contentType())).build()
                }
                return originalResponse
            }

            // الحماية والحل الذكي 2: معالجة القطع وروابط التجميع (Scrambled URL) للمنهوا والقطع المدمجة
            val encoded = request.url.queryParameter("d") ?: return chain.proceed(request)
            val schemeUrl = URLDecoder.decode(encoded, "UTF-8")
            if (!schemeUrl.startsWith(SCRAMBLED_SCHEME)) return chain.proceed(request)

            val withoutScheme = schemeUrl.removePrefix(SCRAMBLED_SCHEME)
            val qIdx = withoutScheme.indexOf('?')
            val chapterId = if (qIdx >= 0) withoutScheme.substring(0, qIdx) else withoutScheme
            val params = if (qIdx >= 0) parseQuery(withoutScheme.substring(qIdx + 1)) else emptyMap()
            val pageToken = URLDecoder.decode(params["token"] ?: "", "UTF-8")

            val bodyObj = PlanRequestBody(token = pageToken)
            val bodyStr = json.encodeToString(bodyObj)
            val planRequest = Request.Builder()
                .url("$baseUrl/chapter-map-proxy-plan/$chapterId")
                .headers(request.headers)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .post(bodyStr.toRequestBody("application/json".toMediaType()))
                .build()

            val planResponse = chain.proceed(planRequest)
            if (!planResponse.isSuccessful) {
                planResponse.close()
                throw Exception("ProComic: Plan API HTTP ${planResponse.code}")
            }

            val planResult = json.decodeFromStream<ProxyPlanResponse>(planResponse.body.byteStream())
            if (!planResult.success || planResult.data?.map == null) {
                throw Exception("ProComic: Plan API failed")
            }

            val mapInfo = planResult.data.map
            val finalImageBytes = reconstructPage(mapInfo, request.headers)

            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(finalImageBytes.toResponseBody("image/png".toMediaType()))
                .build()
        }

        private fun parseQuery(query: String): Map<String, String> {
            return query.split("&").associate {
                val parts = it.split("=", limit = 2)
                parts[0] to (if (parts.size > 1) parts[1] else "")
            }
        }

        private fun reconstructPage(map: DeferredPageMap, headers: Headers): ByteArray {
            val mode = map.mode ?: "vertical_1"
            val dim = map.dim ?: listOf(800, 1200)
            val pieces = map.pieces ?: emptyList()
            val order = map.order ?: emptyList()

            val dimW = dim.getOrNull(0) ?: 800
            val dimH = dim.getOrNull(1) ?: 1200

            val (cols, rows) = when {
                mode.startsWith("grid_", ignoreCase = true) -> {
                    val m = Regex("grid_(\\d+)x(\\d+)", RegexOption.IGNORE_CASE).find(mode)
                    if (m != null) m.groupValues[1].toInt() to m.groupValues[2].toInt() else 1 to 1
                }
                mode.startsWith("vertical_", ignoreCase = true) -> {
                    val piecesCount = mode.substringAfter("_").toIntOrNull() ?: pieces.size
                    1 to piecesCount
                }
                else -> 1 to 1
            }

            val calls = pieces.map { url ->
                pieceClient.newCall(Request.Builder().url(url.trim()).headers(headers).build())
            }

            val futures = calls.map { call ->
                val future = java.util.concurrent.CompletableFuture<ByteArray>()
                call.enqueue(object : okhttp3.Callback {
                    override fun onResponse(call: okhttp3.Call, response: Response) {
                        try {
                            future.complete(response.use { it.body.bytes() })
                        } catch (e: Exception) {
                            future.completeExceptionally(e)
                        }
                    }
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        future.completeExceptionally(e)
                    }
                })
                future
            }

            val pieceBitmaps = futures.mapIndexed { idx, future ->
                val bodyBytes = future.get(30, TimeUnit.SECONDS)
                
                // الحل العبقري: فحص إذا كانت القطعة القادمة من الـ API عبارة عن Base64 صريح
                val sample = String(bodyBytes.take(50).toByteArray(), Charsets.US_ASCII)
                val finalBytes = if (sample.startsWith("data:image")) {
                    val base64Data = String(bodyBytes, Charsets.UTF_8).substringAfter(",")
                    Base64.decode(base64Data, Base64.DEFAULT)
                } else {
                    bodyBytes
                }

                ImageDecoder.newInstance(finalBytes).decode() 
                    ?: throw Exception("ProComic: Decode piece #$idx failed")
            }

            val cellW = ceil(dimW.toDouble() / cols).toInt()
            val cellH = ceil(dimH.toDouble() / rows).toInt()

            val output = Bitmap.createBitmap(dimW, dimH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)

            for (destCell in order.indices) {
                val pieceIdx = order[destCell]
                val bmp = pieceBitmaps.getOrNull(pieceIdx) ?: throw Exception("ProComic: missing piece index $pieceIdx")

                val destCol = destCell % cols
                val destRow = destCell / cols
                val destLeft = destCol * cellW
                val destTop = destRow * cellH

                val actualCellW = if (destCol == cols - 1) dimW - destLeft else cellW
                val actualCellH = if (destRow == rows - 1) dimH - destTop else cellH

                canvas.drawBitmap(
                    bmp,
                    Rect(0, 0, bmp.width, bmp.height),
                    Rect(destLeft, destTop, destLeft + actualCellW, destTop + actualCellH),
                    null
                )
            }

            val baos = ByteArrayOutputStream()
            output.compress(Bitmap.CompressFormat.PNG, 100, baos)
            return baos.toByteArray()
        }
    }

    // ============================ MODELS / DTOS ==============================

    @Serializable
    data class NextData(val props: PagePropsContainer? = null)

    @Serializable
    data class PagePropsContainer(val pageProps: PageProps? = null)

    @Serializable
    data class PageProps(
        val title: String? = null,
        val mangaTitle: String? = null,
        val mangaSlug: String? = null,
        val description: String? = null,
        val coverImage: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val status: String? = null,
        val tags: List<TagDto>? = emptyList(),
        val chapters: List<ChapterDto>? = emptyList(),
        val series: SeriesContainerDto? = null,
        val chapterId: Int? = null,
        val id: Int? = null,
        val token: String? = null,
        val chapterToken: String? = null,
        val jwt: String? = null,
        val accessToken: String? = null,
        val splitIndex: Int? = null,
        val chapter: ChapterDto? = null,
    )

    @Serializable
    data class TagDto(val name: String)

    @Serializable
    data class SeriesContainerDto(
        val slug: String? = null,
        val chapters: List<ChapterDto>? = emptyList(),
    )

    @Serializable
    data class ApiResponse<T>(val data: T? = null)

    @Serializable
    data class MangaDto(
        val mangaSlug: String,
        val mangaTitle: String,
        val coverImage: String? = null,
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
        val token: String? = null,
        val chapterId: Int? = null,
        val slug: String? = null,
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
        val images: List<String>? = emptyList(),
        val maps: List<DeferredPageMap>? = emptyList(),
    )

    @Serializable
    data class DeferredPageMap(
        val dim: List<Int>? = emptyList(),
        val mode: String? = "",
        val pieces: List<String>? = emptyList(),
        val order: List<Int>? = emptyList(),
        val token: String = "",
    )

    @Serializable
    data class PlanRequestBody(val token: String)

    @Serializable
    data class ProxyPlanResponse(
        val success: Boolean = false,
        val data: ProxyPlanData? = null,
    )

    @Serializable
    data class ProxyPlanData(val map: DeferredPageMap? = null)

    companion object {
        const val SCRAMBLED_SCHEME = "procomic-scrambled://"
        const val DEFAULT_SPLIT = 3
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
