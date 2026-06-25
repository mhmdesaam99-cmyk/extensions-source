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
        // scheme وهمي لا يُرسل للشبكة أبداً — يُعترض محلياً فقط
        private const val SCRAMBLED_SCHEME = "https://127.0.0.1/__scrambled__/"
        // صورة JPEG بيضاء 1x1 pixel — fallback عند فشل التجميع بدل فتح WebView
        private val EMPTY_IMAGE_BYTES: ByteArray = Base64.decode(
            "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8U" +
                "HRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgN" +
                "DRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIy" +
                "MjIyMjL/wAARCAABAAEDASIAAhEBAxEB/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAA" +
                "AAAAAAAAAAAAAP/EABQBAQAAAAAAAAAAAAAAAAAAAAD/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oA" +
                "DAMBAAIRAxEAPwCwABmX/9k=",
            Base64.DEFAULT,
        )
    }

    private fun String.toAbsoluteUrl(cdnBase: String): String {
        return when {
            this.startsWith("http") -> this
            this.startsWith("eyJ2IjoxLCJpdiI6I") -> "$cdnBase/i/$this"
            this.startsWith("/") -> "$cdnBase$this"
            else -> "$cdnBase/$this"
        }
    }

    // الـ Interceptor الآن يقرأ خريطة التفكيك محلياً من كائن الصفحة لمنع الـ 502 تماماً
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .rateLimit(2, 1)
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()

            if (url.startsWith(SCRAMBLED_SCHEME)) {
                val base64Image = request.tag(String::class.java)
                val imageBytes = if (base64Image != null) {
                    try { Base64.decode(base64Image, Base64.NO_WRAP) } catch (e: Exception) { null }
                } else null

                val responseBytes = imageBytes ?: EMPTY_IMAGE_BYTES
                return@addInterceptor Response.Builder()
                    .request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(responseBytes.toResponseBody("image/jpeg".toMediaType()))
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
        val fragmentData = page.url.substringAfter("#", "")
        if (fragmentData.isNotBlank()) {
            // الـ fragment يحتوي على الصورة المدمجة كـ Base64 مباشرة
            return Request.Builder()
                .url(SCRAMBLED_SCHEME + "page.jpg")
                .tag(String::class.java, fragmentData)
                .build()
        }
        // صورة عادية
        return Request.Builder()
            .url(page.imageUrl ?: page.url.substringBefore("#"))
            .headers(headers)
            .build()
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
                map.token.isNotBlank() && map.pieces.isEmpty() -> {
                    val mergedBase64 = resolveAndMerge(map, chapterId, apiHeaders)
                    if (mergedBase64 != null) {
                        val fakeKey = "merged_${pages.size}"
                        if (seenUrls.add(fakeKey)) {
                            val shortUrl = "$SCRAMBLED_SCHEME${pages.size}.jpg#$mergedBase64"
                            pages.add(Page(pages.size, url = shortUrl, imageUrl = shortUrl))
                        }
                    }
                }
                map.pieces.isNotEmpty() -> {
                    // صور مباشرة (مانجا عادية) — نضيفها مباشرة بدون تجميع
                    map.pieces.forEach { piece ->
                        val fullUrl = piece.toAbsoluteUrl(cdnBase)
                        if (seenUrls.add(fullUrl)) {
                            pages.add(Page(pages.size, imageUrl = fullUrl))
                        }
                    }
                }
            }
        }

        // لم نعد نحتاج fetchDeferredPages لأن proxy-plan يتولى كل شيء مباشرة

        return pages
    }

    // تجلب الـ map من proxy-plan ثم تحمّل القطع بالتوازي وتدمجها فوراً في صورة واحدة
    // كل هذا في خطوة واحدة لأن URLs تُحرق عند أي تأخير
    private fun resolveAndMerge(
        map: DeferredPageMap,
        chapterId: String,
        apiHeaders: Headers,
    ): String? {
        return try {
            // الخطوة 1: جلب الـ URLs من proxy-plan
            val bodyStr = json.encodeToString(map)
            val body = bodyStr.toRequestBody("application/json".toMediaType())
            val reqHeaders = apiHeaders.newBuilder()
                .set("Origin", baseUrl)
                .set("Referer", "$baseUrl/")
                .set("Content-Type", "application/json")
                .set("Accept", "application/json")
                .build()
            val proxyResp = client.newCall(
                POST("$baseUrl/chapter-map-proxy-plan/$chapterId", reqHeaders, body),
            ).execute()
            if (!proxyResp.isSuccessful) return null
            val result = proxyResp.parseAs<ProxyPlanResponse>()
            if (!result.success) return null
            val resolvedMap = result.data?.map ?: return null
            if (resolvedMap.pieces.isEmpty()) return null

            // الخطوة 2: تحميل كل قطعة في thread منفصل بالتوازي فوراً
            val pieceBytes = Array<ByteArray?>(resolvedMap.pieces.size) { null }
            val threads = resolvedMap.pieces.mapIndexed { idx, pieceUrl ->
                Thread {
                    try {
                        val req = Request.Builder()
                            .url(pieceUrl)
                            .header("Referer", "$baseUrl/")
                            .header("Accept", "image/avif,image/webp,image/jpeg,*/*")
                            .header("User-Agent", headers["User-Agent"] ?: "Mozilla/5.0")
                            .build()
                        network.cloudflareClient.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) pieceBytes[idx] = resp.body.bytes()
                        }
                    } catch (e: Exception) { }
                }.also { it.start() }
            }
            threads.forEach { it.join(15_000L) }

            // الخطوة 3: تحويل bytes إلى Bitmaps
            val bitmaps = Array<Bitmap?>(resolvedMap.pieces.size) { null }
            pieceBytes.forEachIndexed { idx, bytes ->
                if (bytes != null && bytes.isNotEmpty()) {
                    val isBase64 = bytes.size > 5 &&
                        bytes[0] == 'd'.code.toByte() && bytes[4] == ':'.code.toByte()
                    val finalBytes = if (isBase64) {
                        Base64.decode(String(bytes).substringAfter("base64,"), Base64.DEFAULT)
                    } else bytes
                    bitmaps[idx] = decodeAvif(finalBytes)
                }
            }

            // الخطوة 4: دمج القطع في صورة واحدة
            val mergedBytes = mergeMapToBitmap(resolvedMap, bitmaps) ?: return null
            Base64.encodeToString(mergedBytes, Base64.NO_WRAP)
        } catch (e: Exception) { null }
    }

    // دمج القطع مع مراعاة rects وorder
    private fun mergeMapToBitmap(map: DeferredPageMap, bitmaps: Array<Bitmap?>): ByteArray? {
        val validBitmaps = bitmaps.filterNotNull()
        if (validBitmaps.isEmpty()) return null

        val useRects = map.rects.size == map.pieces.size
        val totalW = map.dim.getOrNull(0)?.takeIf { w -> w > 0 }
            ?: if (useRects) map.rects.maxOf { r -> r.left + r.width } else validBitmaps.maxOf { b -> b.width }
        val totalH = map.dim.getOrNull(1)?.takeIf { h -> h > 0 }
            ?: if (useRects) map.rects.maxOf { r -> r.top + r.height } else validBitmaps.sumOf { b -> b.height }
        if (totalW <= 0 || totalH <= 0) return null

        val result = try {
            Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Bitmap.createBitmap(totalW, totalH, Bitmap.Config.RGB_565)
        }
        val canvas = Canvas(result)

        if (useRects) {
            for (targetIdx in bitmaps.indices) {
                val srcIdx = if (map.order.size == map.pieces.size) map.order[targetIdx] else targetIdx
                val bmp = bitmaps[srcIdx] ?: continue
                val rect = map.rects[targetIdx]
                canvas.drawBitmap(bmp, rect.left.toFloat(), rect.top.toFloat(), null)
            }
        } else {
            val (cols, _) = parseMode(map.mode, map.pieces.size)
            if (cols <= 1) {
                var y = 0f
                for (targetIdx in bitmaps.indices) {
                    val srcIdx = if (map.order.size == map.pieces.size) map.order[targetIdx] else targetIdx
                    val bmp = bitmaps[srcIdx] ?: continue
                    canvas.drawBitmap(bmp, 0f, y, null)
                    y += bmp.height
                }
            } else {
                val tileW = validBitmaps.first().width
                val tileH = validBitmaps.first().height
                for (targetIdx in bitmaps.indices) {
                    val srcIdx = if (map.order.size == map.pieces.size) map.order[targetIdx] else targetIdx
                    val bmp = bitmaps[srcIdx] ?: continue
                    canvas.drawBitmap(bmp, ((targetIdx % cols) * tileW).toFloat(), ((targetIdx / cols) * tileH).toFloat(), null)
                }
            }
        }

        bitmaps.forEach { it?.recycle() }
        val out = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, 90, out)
        result.recycle()
        return out.toByteArray()
    }

    private fun decodeAvif(bytes: ByteArray): Bitmap? {
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) { null }
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
@Serializable
data class RectDto(
    val left: Int = 0,
    val top: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
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
