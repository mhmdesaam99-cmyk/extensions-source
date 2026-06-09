package eu.kanade.tachiyomi.extension.ar.procomic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import rx.Observable
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class ProComic : HttpSource() {

    override val name = "ProComic"
    override val baseUrl = "https://procomic.pro"
    override val lang = "ar"
    override val supportsLatest = true

    private val imgProxy = "https://img2.procomic.pro"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")
        .add("Accept", "*/*")
        .add("Accept-Language", "ar,en;q=0.9")
        .add("sec-ch-ua", "\"Not_A Brand\";v=\"99\", \"Google Chrome\";v=\"109\", \"Chromium\";v=\"109\"")
        .add("sec-ch-ua-mobile", "?0")
        .add("sec-ch-ua-platform", "\"Windows\"")
        .add("sec-fetch-dest", "empty")
        .add("sec-fetch-mode", "cors")
        .add("sec-fetch-site", "same-origin")

    override fun client(): OkHttpClient = super.client().newBuilder()
        .addInterceptor(RateLimitInterceptor(300, 60))
        .build()

    private var libraryCache: List<LibraryItemDto>? = null

    private fun getLibrary(): List<LibraryItemDto> {
        libraryCache?.let { return it }
        val request = Request.Builder()
            .url("$baseUrl/api/library")
            .headers(headers)
            .build()
        val response = client.newCall(request).execute()
        val wrapper = json.decodeFromString<LibraryResponseDto>(response.body.string())
        libraryCache = wrapper.library
        return wrapper.library
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/api/public/content/latest-updates?limit=18&category=comics&page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val wrapper = json.decodeFromString<LatestUpdatesResponseDto>(response.body.string())
        val mangas = wrapper.data.map { item ->
            SManga.create().apply {
                title = item.mangaTitle
                url = "${item.type}/${item.mangaId}/${item.mangaSlug}"
                thumbnail_url = item.coverImageApp?.card?.mobile
                    ?: item.coverImageApp?.desktop
                    ?: item.coverImage
                status = parseStatus(item.status)
            }
        }
        return MangasPage(mangas, mangas.size == 18)
    }

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/api/library", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val wrapper = json.decodeFromString<LibraryResponseDto>(response.body.string())
        libraryCache = wrapper.library
        val mangas = wrapper.library.map { it.toSManga(baseUrl) }
        return MangasPage(mangas, false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/api/library", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return MangasPage(emptyList(), false)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return Observable.fromCallable {
            val library = getLibrary()

            var filtered = if (query.isNotBlank()) {
                library.filter {
                    it.title.contains(query, ignoreCase = true) ||
                        it.metadata?.originalTitle?.contains(query, ignoreCase = true) == true ||
                        it.metadata?.altTitles?.any { alt -> alt.contains(query, ignoreCase = true) } == true
                }
            } else {
                library.toList()
            }

            val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
            val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()
            val statusFilter = filters.filterIsInstance<StatusFilter>().firstOrNull()

            if (genreFilter != null && genreFilter.state != 0) {
                val selectedGenre = genreFilter.values[genreFilter.state]
                filtered = filtered.filter { item ->
                    item.metadata?.genres?.any { g -> g.en == selectedGenre } == true
                }
            }

            if (typeFilter != null && typeFilter.state != 0) {
                val selectedType = typeFilter.values[typeFilter.state].lowercase()
                filtered = filtered.filter { it.type?.lowercase() == selectedType }
            }

            if (statusFilter != null && statusFilter.state != 0) {
                val selectedStatus = statusFilter.values[statusFilter.state].lowercase()
                filtered = filtered.filter {
                    it.metadata?.viewStatus?.lowercase() == selectedStatus ||
                        it.status?.lowercase() == selectedStatus
                }
            }

            val mangas = filtered.map { it.toSManga(baseUrl) }
            MangasPage(mangas, false)
        }
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("$baseUrl/api/library", headers)
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return Observable.fromCallable {
            val parts = manga.url.split("/")
            val mangaId = parts.getOrNull(1)?.toIntOrNull() ?: return@fromCallable manga

            val library = getLibrary()
            val item = library.find { it.id == mangaId } ?: return@fromCallable manga

            SManga.create().apply {
                title = item.title
                url = manga.url
                thumbnail_url = manga.thumbnail_url
                author = item.metadata?.author
                artist = item.metadata?.artist
                description = buildDescription(item)
                genre = buildGenreString(item)
                status = parseStatus(item.metadata?.viewStatus ?: item.status ?: "")
                initialized = true
            }
        }
    }

    override fun mangaDetailsParse(response: Response): SManga = throw UnsupportedOperationException()

    override fun chapterListRequest(manga: SManga): Request {
        val parts = manga.url.split("/")
        val type = parts.getOrNull(0) ?: "manhua"
        val mangaId = parts.getOrNull(1) ?: ""
        val slug = parts.getOrNull(2) ?: ""
        return GET("$baseUrl/api/public/series/$type/$mangaId/$slug/chapters", headers)
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return Observable.fromCallable {
            val parts = manga.url.split("/")
            val type = parts.getOrNull(0) ?: "manhua"
            val mangaId = parts.getOrNull(1) ?: ""
            val slug = parts.getOrNull(2) ?: ""

            val request = GET("$baseUrl/api/public/series/$type/$mangaId/$slug/chapters", headers)
            val response = client.newCall(request).execute()
            val body = response.body.string()

            val chapters: List<ChapterDto> = try {
                val wrapper = json.decodeFromString<ChapterListResponseDto>(body)
                wrapper.data ?: wrapper.chapters ?: emptyList()
            } catch (_: Exception) {
                try {
                    json.decodeFromString(body)
                } catch (_: Exception) {
                    emptyList()
                }
            }

            chapters.filter { ch ->
                ch.language == "AR" && !ch.lockedForever && !ch.lockedByExclusive
            }.map { ch ->
                SChapter.create().apply {
                    name = "فصل ${ch.number}"
                    url = "${ch.id}"
                    chapter_number = ch.number.toFloatOrNull() ?: -1f
                    date_upload = try {
                        dateFormat.parse(ch.publishedAt)?.time ?: 0L
                    } catch (_: Exception) { 0L }
                }
            }.sortedByDescending { it.chapter_number }
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url
        return GET("$baseUrl/api/public/chapters/$chapterId/pages", headers)
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return Observable.fromCallable {
            val chapterId = chapter.url

            val pagesRequest = GET("$baseUrl/api/public/chapters/$chapterId/pages", headers)
            val pagesResponse = client.newCall(pagesRequest).execute()
            val pagesBody = pagesResponse.body.string()

            val pagesData: ChapterPagesResponseDto? = try {
                json.decodeFromString(pagesBody)
            } catch (_: Exception) { null }

            when {
                pagesData?.pages != null && pagesData.pages.isNotEmpty() -> {
                    pagesData.pages.mapIndexed { index, pageUrl ->
                        Page(index, "", pageUrl)
                    }
                }
                pagesData?.token != null -> {
                    fetchDeferredPages(chapterId, pagesData.token)
                }
                else -> {
                    val tokenRequest = GET("$baseUrl/api/public/chapters/$chapterId/token", headers)
                    val tokenResponse = client.newCall(tokenRequest).execute()
                    val tokenBody = tokenResponse.body.string()
                    val tokenData: TokenResponseDto? = try {
                        json.decodeFromString(tokenBody)
                    } catch (_: Exception) { null }

                    val token = tokenData?.token ?: tokenData?.data ?: return@fromCallable emptyList()
                    fetchDeferredPages(chapterId, token)
                }
            }
        }
    }

    private fun fetchDeferredPages(chapterId: String, token: String): List<Page> {
        val deferredRequest = GET(
            "$baseUrl/chapter-deferred-media/$chapterId?token=$token&split=1",
            headers.newBuilder()
                .set("Referer", "$baseUrl/")
                .build(),
        )
        val deferredResponse = client.newCall(deferredRequest).execute()
        val deferredBody = deferredResponse.body.string()

        val deferredData: DeferredMediaResponseDto? = try {
            json.decodeFromString(deferredBody)
        } catch (_: Exception) { null }

        return when {
            deferredData?.pages != null && deferredData.pages.isNotEmpty() -> {
                deferredData.pages.mapIndexed { index, pageUrl ->
                    Page(index, "", pageUrl)
                }
            }
            deferredData?.maps != null && deferredData.maps.isNotEmpty() -> {
                deferredData.maps.mapIndexed { index, mapData ->
                    Page(index, mapData.toJsonString(), "$imgProxy/i/${mapData.token}")
                }
            }
            else -> emptyList()
        }
    }

    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun fetchImage(page: Page): Observable<Response> {
        return Observable.fromCallable {
            val imageUrl = page.imageUrl ?: return@fromCallable buildEmptyResponse()

            if (imageUrl.startsWith("$imgProxy/i/")) {
                val imgHeaders = headers.newBuilder()
                    .set("Referer", "$baseUrl/")
                    .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    .build()

                val response = client.newCall(GET(imageUrl, imgHeaders)).execute()
                val contentType = response.header("Content-Type", "image/avif") ?: "image/avif"

                if (contentType.contains("application/json") || contentType.contains("text/")) {
                    val bodyText = response.body.string()
                    if (bodyText.startsWith("data:image/avif;base64,") ||
                        bodyText.startsWith("data:image/webp;base64,") ||
                        bodyText.startsWith("data:image/")
                    ) {
                        return@fromCallable decodeBase64Image(bodyText, page)
                    }
                }

                if (page.url.isNotBlank() && page.url.contains("dim")) {
                    return@fromCallable stitchScrambledImage(response, page)
                }

                return@fromCallable response
            }

            val imgHeaders = headers.newBuilder()
                .set("Referer", "$baseUrl/")
                .set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .build()
            client.newCall(GET(imageUrl, imgHeaders)).execute()
        }
    }

    private fun decodeBase64Image(dataUri: String, page: Page): Response {
        val commaIndex = dataUri.indexOf(',')
        val mimeMatch = Regex("data:(image/[^;]+)").find(dataUri)
        val mimeType = mimeMatch?.groupValues?.get(1) ?: "image/avif"
        val base64Data = if (commaIndex >= 0) dataUri.substring(commaIndex + 1).trim() else dataUri
        val bytes = Base64.decode(base64Data, Base64.DEFAULT)
        val body = bytes.toResponseBody(mimeType.toMediaTypeOrNull())
        return Response.Builder()
            .code(200)
            .message("OK")
            .body(body)
            .build()
    }

    private fun stitchScrambledImage(response: Response, page: Page): Response {
        val mapData: DeferredPageMap? = try {
            json.decodeFromString(page.url)
        } catch (_: Exception) { null }

        if (mapData == null || mapData.pieces.isNullOrEmpty()) return response

        val bytes = response.body.bytes()
        val sourceBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return buildBytesResponse(bytes, "image/avif")

        val totalWidth = mapData.dim.w
        val totalHeight = mapData.dim.h
        val order = mapData.order ?: run {
            sourceBitmap.recycle()
            return buildBytesResponse(bytes, "image/avif")
        }

        val cols: Int
        val rows: Int

        when {
            mapData.mode?.startsWith("grid_") == true -> {
                val parts = mapData.mode.removePrefix("grid_").split("x")
                cols = parts.getOrNull(0)?.toIntOrNull() ?: 3
                rows = parts.getOrNull(1)?.toIntOrNull() ?: 3
            }
            mapData.mode?.startsWith("vertical_") == true -> {
                cols = 1
                rows = mapData.mode.removePrefix("vertical_").toIntOrNull() ?: order.size
            }
            else -> {
                cols = 3
                rows = (order.size + 2) / 3
            }
        }

        val pieceW = totalWidth / cols
        val pieceH = totalHeight / rows

        val result = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        order.forEachIndexed { destIndex, srcIndex ->
            val srcCol = srcIndex % cols
            val srcRow = srcIndex / cols
            val destCol = destIndex % cols
            val destRow = destIndex / cols

            val srcRect = Rect(srcCol * pieceW, srcRow * pieceH, (srcCol + 1) * pieceW, (srcRow + 1) * pieceH)
            val destRect = Rect(destCol * pieceW, destRow * pieceH, (destCol + 1) * pieceW, (destRow + 1) * pieceH)

            canvas.drawBitmap(sourceBitmap, srcRect, destRect, paint)
        }

        sourceBitmap.recycle()

        val out = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.PNG, 100, out)
        result.recycle()

        val outBytes = out.toByteArray()
        return buildBytesResponse(outBytes, "image/png")
    }

    private fun buildBytesResponse(bytes: ByteArray, mime: String): Response {
        return Response.Builder()
            .code(200)
            .message("OK")
            .body(bytes.toResponseBody(mime.toMediaTypeOrNull()))
            .build()
    }

    private fun buildEmptyResponse(): Response {
        return Response.Builder()
            .code(404)
            .message("Not Found")
            .body("".toResponseBody("text/plain".toMediaTypeOrNull()))
            .build()
    }

    private var genresList: List<GenreDto> = emptyList()
    private var genresLoaded = false

    private fun ensureGenresLoaded() {
        if (genresLoaded) return
        try {
            val resp = client.newCall(GET("$baseUrl/api/public/categories/series/genres", headers)).execute()
            genresList = json.decodeFromString(resp.body.string())
            genresLoaded = true
        } catch (_: Exception) {}
    }

    override fun getFilterList(): FilterList {
        ensureGenresLoaded()

        val genreNames = listOf("كل التصنيفات") + genresList.map { it.ar.ifBlank { it.en } }
        val typeValues = arrayOf("الكل", "Manga", "Manhwa", "Manhua")
        val statusValues = arrayOf("الكل", "public", "exclusive", "dropped", "completed")

        return FilterList(
            TypeFilter("نوع العمل", typeValues),
            StatusFilter("الحالة", statusValues),
            GenreFilter("التصنيف", genreNames.toTypedArray()),
        )
    }

    private fun parseStatus(status: String?): Int = when (status?.lowercase()?.trim()) {
        "completed", "مكتملة", "منتهية" -> SManga.COMPLETED
        "ongoing", "مستمرة" -> SManga.ONGOING
        "hiatus", "متوقفة" -> SManga.ON_HIATUS
        "dropped" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    private fun buildDescription(item: LibraryItemDto): String {
        val sb = StringBuilder()
        item.metadata?.descriptions?.ar?.let { if (it.isNotBlank()) sb.appendLine(it.trim()) }
        if (sb.isEmpty()) {
            item.metadata?.descriptions?.en?.let { if (it.isNotBlank()) sb.appendLine(it.trim()) }
        }
        item.metadata?.originalTitle?.let { if (it.isNotBlank()) sb.appendLine("العنوان الأصلي: $it") }
        item.metadata?.year?.let { sb.appendLine("السنة: $it") }
        item.metadata?.origin?.let { sb.appendLine("المصدر: $it") }
        return sb.toString().trim()
    }

    private fun buildGenreString(item: LibraryItemDto): String? {
        val genres = item.metadata?.genres?.map { it.ar.ifBlank { it.en } } ?: emptyList()
        val tags = item.metadata?.tags?.map { it.ar.ifBlank { it.en } } ?: emptyList()
        val all = (genres + tags).filter { it.isNotBlank() }
        return if (all.isEmpty()) null else all.joinToString(", ")
    }
}

class RateLimitInterceptor(
    private val requestsPerWindow: Int,
    private val windowSeconds: Long,
) : Interceptor {
    private val delayMs = (windowSeconds * 1000L) / requestsPerWindow

    override fun intercept(chain: Interceptor.Chain): Response {
        Thread.sleep(delayMs)
        return chain.proceed(chain.request())
    }
}

class TypeFilter(name: String, val values: Array<String>) :
    Filter.Select<String>(name, values)

class StatusFilter(name: String, val values: Array<String>) :
    Filter.Select<String>(name, values)

class GenreFilter(name: String, val values: Array<String>) :
    Filter.Select<String>(name, values)

@Serializable
data class LatestUpdatesResponseDto(
    val success: Boolean = false,
    val data: List<LatestMangaItemDto> = emptyList(),
)

@Serializable
data class LatestMangaItemDto(
    val mangaId: Int,
    val mangaSlug: String,
    val mangaTitle: String,
    val coverImage: String = "",
    val type: String = "manhua",
    val origin: String = "",
    val status: String = "",
    val isBlockedSeries: Boolean = false,
    val isSensitiveImage: Boolean = false,
    val viewStatus: String? = null,
    val chapters: List<ChapterDto> = emptyList(),
    val coverImageApp: CoverImageAppDto? = null,
)

@Serializable
data class CoverImageAppDto(
    val desktop: String? = null,
    val card: CoverCardDto? = null,
)

@Serializable
data class CoverCardDto(
    val mobile: String? = null,
    val desktop: String? = null,
)

@Serializable
data class LibraryResponseDto(
    val library: List<LibraryItemDto> = emptyList(),
)

@Serializable
data class LibraryItemDto(
    val id: Int,
    val slug: String,
    val title: String,
    val coverImage: String = "",
    val status: String? = null,
    @SerialName("series_status") val seriesStatus: String? = null,
    val chaptersCount: Int = 0,
    val hasUpdates: Boolean = false,
    val type: String? = null,
    @SerialName("cdn_path") val cdnPath: String? = null,
    @SerialName("is_sensitive_image") val isSensitiveImage: Boolean = false,
    val metadata: MangaMetadataDto? = null,
    val isBlockedSeries: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    fun toSManga(baseUrl: String): SManga = SManga.create().apply {
        title = this@LibraryItemDto.title
        url = "${this@LibraryItemDto.type ?: "manhua"}/${this@LibraryItemDto.id}/${this@LibraryItemDto.slug}"
        val cdnBase = when (cdnPath) {
            "cdn1" -> "https://cdn1.procomic.pro"
            "cdn2" -> "https://cdn2.procomic.pro"
            "cdn3" -> "https://cdn3.procomic.pro"
            "cdn4" -> "https://cdn4.procomic.pro"
            else -> "https://app.procomic.pro"
        }
        thumbnail_url = if (coverImage.startsWith("http")) {
            coverImage
        } else {
            "$cdnBase$coverImage"
        }
        author = metadata?.author
        artist = metadata?.artist
        status = when (metadata?.viewStatus?.lowercase()) {
            "completed" -> SManga.COMPLETED
            "ongoing" -> SManga.ONGOING
            "exclusive" -> SManga.ONGOING
            "dropped" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
data class MangaMetadataDto(
    val originalTitle: String? = null,
    val altTitles: List<String>? = null,
    val author: String? = null,
    val artist: String? = null,
    val year: String? = null,
    val origin: String? = null,
    val viewStatus: String? = null,
    val descriptions: DescriptionsDto? = null,
    val genres: List<GenreDto>? = null,
    val tags: List<TagDto>? = null,
    val safeBrowsingEnabled: Boolean? = null,
    val safeBrowsingBlocked: Boolean? = null,
    val exclusiveLockStrategy: String? = null,
    val exclusiveLockCount: Int? = null,
)

@Serializable
data class DescriptionsDto(
    val ar: String = "",
    val en: String = "",
)

@Serializable
data class GenreDto(
    val id: Int = 0,
    val en: String = "",
    val ar: String = "",
    val descriptionEn: String = "",
    val descriptionAr: String = "",
)

@Serializable
data class TagDto(
    val id: Int = 0,
    val en: String = "",
    val ar: String = "",
    val descriptionEn: String = "",
    val descriptionAr: String = "",
)

@Serializable
data class ChapterListResponseDto(
    val success: Boolean = false,
    val data: List<ChapterDto>? = null,
    val chapters: List<ChapterDto>? = null,
)

@Serializable
data class ChapterDto(
    val id: Int,
    val slug: String,
    val number: String,
    val language: String = "AR",
    val publishedAt: String = "",
    val supportMode: String = "default",
    val coinsRequired: Int? = null,
    val hasShortlink: Boolean = false,
    val lockedForever: Boolean = false,
    val lockedByCoins: Boolean = false,
    val lockedByExclusive: Boolean = false,
)

@Serializable
data class ChapterPagesResponseDto(
    val success: Boolean = false,
    val pages: List<String>? = null,
    val token: String? = null,
    val data: List<String>? = null,
)

@Serializable
data class TokenResponseDto(
    val success: Boolean = false,
    val token: String? = null,
    val data: String? = null,
)

@Serializable
data class DeferredMediaResponseDto(
    val success: Boolean = false,
    val pages: List<String>? = null,
    val maps: List<DeferredPageMap>? = null,
    val token: String? = null,
)

@Serializable
data class DeferredPageMap(
    val dim: DimDto,
    val mode: String? = null,
    val pieces: List<String>? = null,
    val order: List<Int>? = null,
    val token: String = "",
) {
    fun toJsonString(): String {
        return "{\"dim\":{\"w\":${dim.w},\"h\":${dim.h}},\"mode\":\"$mode\",\"order\":${order?.toString() ?: "null"},\"token\":\"$token\"}"
    }
}

@Serializable
data class DimDto(
    val w: Int,
    val h: Int,
)
