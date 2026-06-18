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
import java.util.TimeZone

class ProComic : HttpSource() {

    override val name = "برو كوميك"

    override val baseUrl = "https://procomic.pro"

    override val lang = "ar"

    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Accept", "application/json")
        .add("Accept-Language", "ar,en;q=0.9")

    // ========== POPULAR / LATEST MANGA (USING THE NEW API) ==========

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/api/public/content/latest-updates".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "18")
            .addQueryParameter("category", "comics")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = json.decodeFromString<LatestUpdatesResponse>(response.body.string())
        if (!result.success || result.data.isEmpty()) return MangasPage(emptyList(), false)

        val mangas = result.data.map { mangaDto ->
            SManga.create().apply {
                url = "/series/manga/${mangaDto.mangaId}/${mangaDto.mangaSlug}"
                title = mangaDto.mangaTitle
                thumbnail_url = mangaDto.coverImage
            }
        }
        return MangasPage(mangas, hasNextPage = mangas.size >= 18)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ========== SEARCH ==========

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/public/content/latest-updates".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "18")
            .addQueryParameter("category", "comics")
            if (query.isNotBlank()) {
                url.addQueryParameter("search", query)
            }
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ========== MANGA DETAILS ==========

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.substringAfter("/manga/").substringBefore("/")
        return GET("$baseUrl/api/public/content/series/$id", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val res = json.decodeFromString<MangaDetailsResponse>(response.body.string())
        val details = res.data ?: throw Exception("تعذر قراءة بيانات المانجا")

        return SManga.create().apply {
            title = details.title
            description = details.metadata?.descriptions?.ar ?: details.metadata?.descriptions?.en
            author = details.metadata?.author
            artist = details.metadata?.artist
            genre = details.genres?.joinToString { it.ar ?: it.en ?: "" }
            status = when (details.status) {
                "مستمر" -> SManga.ONGOING
                "مكتمل" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            thumbnail_url = details.coverImage
        }
    }

    // ========== CHAPTERS ==========

    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.substringAfter("/manga/").substringBefore("/")
        return GET("$baseUrl/api/public/content/series/$id/chapters?limit=1000", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val res = json.decodeFromString<ChaptersResponse>(response.body.string())
        val mangaUrl = response.request.url.toString()
        val seriesId = mangaUrl.substringAfter("/series/").substringBefore("/chapters")

        return res.data.map { chapterDto ->
            SChapter.create().apply {
                url = "/chapter-deferred-media/${chapterDto.id}"
                name = "الفصل ${chapterDto.chapterNumber}" + if (chapterDto.title.isNullOrBlank()) "" else " - ${chapterDto.title}"
                date_upload = parseDate(chapterDto.publishedAt)
            }
        }.reversed()
    }

    // ========== PAGE LIST ==========

    override fun pageListRequest(chapter: SChapter): Request {
        val id = chapter.url.substringAfterLast("/")
        return GET("$baseUrl/api/public/content/chapters/$id/deferred-media", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val res = json.decodeFromString<ChapterDeferredResponse>(response.body.string())
        val data = res.data ?: return emptyList()

        val pages = mutableListOf<Page>()
        var index = 0

        data.images.forEach { imgUrl ->
            pages.add(Page(index++, "", imgUrl))
        }

        if (data.maps.isNotEmpty()) {
            val jsonMaps = json.encodeToString(data.maps)
            val mapPayload = Base64.encodeToString(jsonMaps.toByteArray(), Base64.NO_WRAP)
            pages.add(Page(index, "", "deferred_map:$mapPayload"))
        }

        return pages
    }

    // ========== IMAGE PROCESSING (DEFERRED MAPS) ==========

    override fun fetchImage(page: Page): rx.Observable<Response> {
        if (page.imageUrl!!.startsWith("deferred_map:")) {
            val payload = page.imageUrl!!.substringAfter("deferred_map:")
            val jsonMaps = String(Base64.decode(payload, Base64.NO_WRAP))
            val maps = json.decodeFromString<List<DeferredPageMap>>(jsonMaps)

            return rx.Observable.fromCallable {
                val bitmap = renderDeferredMap(maps)
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                val body = stream.toByteArray().toResponseBody("image/jpeg".toMediaType())
                Response.Builder()
                    .request(Request.Builder().url(baseUrl).build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            }
        }
        return super.fetchImage(page)
    }

    private fun renderDeferredMap(maps: List<DeferredPageMap>): Bitmap {
        var totalWidth = 0
        var totalHeight = 0

        maps.forEach { map ->
            if (map.dim.size >= 2) {
                if (map.dim[0] > totalWidth) totalWidth = map.dim[0]
                totalHeight += map.dim[1]
            }
        }

        if (totalWidth == 0 || totalHeight == 0) {
            totalWidth = 800
            totalHeight = 1200
        }

        val resultBitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        var currentY = 0

        maps.forEach { map ->
            if (map.pieces.isNotEmpty() && map.layout.isNotEmpty()) {
                val pieceBitmaps = map.pieces.map { url ->
                    val req = GET(url, headers)
                    val resp = client.newCall(req).execute()
                    val bytes = resp.body.bytes()
                    val decodedBytes = ImageDecoder.decode(bytes) ?: bytes
                    BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                }

                map.layout.forEachIndexed { i, layoutPiece ->
                    val pieceIndex = layoutPiece.pieceIndex
                    if (pieceIndex in pieceBitmaps.indices) {
                        val srcBitmap = pieceBitmaps[pieceIndex]
                        if (srcBitmap != null) {
                            val srcX = layoutPiece.left
                            val srcY = layoutPiece.top
                            val w = layoutPiece.width
                            val h = layoutPiece.height

                            val destX = layoutPiece.left
                            val destY = currentY + layoutPiece.top

                            val srcRect = android.graphics.Rect(srcX, srcY, srcX + w, srcY + h)
                            val destRect = android.graphics.Rect(destX, destY, destX + w, destY + h)
                            canvas.drawBitmap(srcBitmap, srcRect, destRect, null)
                        }
                    }
                }
                if (map.dim.size >= 2) currentY += map.dim[1]
            }
        }
        return resultBitmap
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun parseDate(dateStr: String?): Long {
        if (dateStr == null) return 0L
        return try {
            val df = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            df.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}

// ========== DATA CLASSES FOR SERIALIZATION ==========

@Serializable
data class LatestUpdatesResponse(
    val success: Boolean = false,
    val data: List<MangaDto> = emptyList(),
)

@Serializable
data class MangaDto(
    val mangaId: Int = 0,
    val mangaSlug: String = "",
    val mangaTitle: String = "",
    val coverImage: String = "",
    val type: String? = null,
)

@Serializable
data class MangaDetailsResponse(
    val success: Boolean = false,
    val data: MangaDetailsDto? = null,
)

@Serializable
data class MangaDetailsDto(
    val id: Int = 0,
    val title: String = "",
    val slug: String = "",
    val coverImage: String = "",
    val status: String? = null,
    val metadata: MetadataDto? = null,
    val genres: List<GenreDto>? = emptyList(),
)

@Serializable
data class MetadataDto(
    val author: String? = null,
    val artist: String? = null,
    val descriptions: DescriptionsDto? = null,
)

@Serializable
data class DescriptionsDto(
    val ar: String? = null,
    val en: String? = null,
)

@Serializable
data class GenreDto(
    val id: Int = 0,
    val en: String? = null,
    val ar: String? = null,
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
    val layout: List<LayoutPiece> = emptyList(),
)

@Serializable
data class LayoutPiece(
    val left: Int = 0,
    val top: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val pieceIndex: Int = 0,
)
