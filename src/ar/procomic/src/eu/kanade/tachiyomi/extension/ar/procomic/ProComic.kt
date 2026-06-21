package eu.kanade.tachiyomi.extension.ar.procomic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
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
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream

class ProComic : HttpSource() {

    override val name = "ProComic"

    override val baseUrl = "https://procomic.pro"

    override val lang = "ar"

    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override val client: OkHttpClient = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request = latestUpdatesRequest(page)

    override fun popularMangaParse(response: Response): MangasPage = latestUpdatesParse(response)

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/api/public/content/latest-updates?limit=18&category=comics&page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val res = json.decodeFromString<LatestUpdatesResponse>(response.body.string())
        val mangas = res.data.map { dto ->
            SManga.create().apply {
                title = dto.mangaTitle ?: ""
                thumbnail_url = dto.coverImage
                url = dto.mangaId.toString()
            }
        }
        return MangasPage(mangas, mangas.size >= 18)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/public/content/latest-updates?limit=100&category=comics&page=$page".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val res = json.decodeFromString<LatestUpdatesResponse>(response.body.string())
        val query = response.request.url.queryParameter("query") ?: ""
        val mangas = res.data
            .filter { it.mangaTitle?.contains(query, ignoreCase = true) == true }
            .map { dto ->
                SManga.create().apply {
                    title = dto.mangaTitle ?: ""
                    thumbnail_url = dto.coverImage
                    url = dto.mangaId.toString()
                }
            }
        return MangasPage(mangas, false)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("$baseUrl/api/public/content/series/${manga.url}", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val res = json.decodeFromString<SeriesDetailsResponse>(response.body.string())
        val details = res.data ?: throw Exception("Data null")
        return SManga.create().apply {
            title = details.mangaTitle ?: details.title ?: ""
            thumbnail_url = details.coverImage
            author = details.metadata?.author
            artist = details.metadata?.artist
            description = details.metadata?.descriptions?.get("ar") ?: details.metadata?.descriptions?.get("en")
            status = when (details.status) {
                "مستمر", "ongoing" -> SManga.ONGOING
                "مكتمل", "completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        return GET("$baseUrl/api/public/content/series/${manga.url}/chapters", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val res = json.decodeFromString<ChaptersResponse>(response.body.string())
        return res.data.map { dto ->
            SChapter.create().apply {
                name = "الفصل ${dto.chapterNumber}" + if (dto.title.isNullOrBlank()) "" else " - ${dto.title}"
                chapter_number = dto.chapterNumber.toFloatOrNull() ?: -1f
                date_upload = dto.publishedAt?.let { parseDate(it) } ?: 0L
                val pageData = ChapterPageData(
                    id = dto.id,
                    images = dto.metadata?.images ?: emptyList(),
                    maps = dto.metadata?.maps ?: emptyList()
                )
                url = json.encodeToString(pageData)
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val pageData = json.decodeFromString<ChapterPageData>(chapter.url)
        return GET("$baseUrl/api/public/content/chapters/${pageData.id}", headers).newBuilder()
            .tag(ChapterPageData::class.java, pageData)
            .build()
    }

    override fun pageListParse(response: Response): List<Page> {
        val pageData = response.request.tag(ChapterPageData::class.java)
            ?: json.decodeFromString<ChapterPageData>(response.body.string())
        val pages = mutableListOf<Page>()
        if (pageData.images.isNotEmpty()) {
            pageData.images.forEachIndexed { index, imgUrl ->
                pages.add(Page(index, "", imgUrl))
            }
            return pages
        }
        pageData.maps.forEachIndexed { index, mapDto ->
            if (mapDto.pieces.isNotEmpty()) {
                val scrambled = ScrambledPage(mapDto.dim, mapDto.mode, mapDto.pieces, mapDto.order)
                pages.add(Page(index, "", json.encodeToString(scrambled)))
            } else if (!mapDto.token.isNullOrEmpty()) {
                val mapRequest = Request.Builder()
                    .url("$baseUrl/chapter-map-proxy-plan/${pageData.id}")
                    .post(json.encodeToString(mapDto).toRequestBody("application/json".toMediaType()))
                    .headers(headers)
                    .build()
                val mapResponse = client.newCall(mapRequest).execute()
                val proxyResult = try {
                    json.decodeFromString<MapProxyResponse>(mapResponse.body.string())
                } catch (e: Exception) {
                    null
                }
                val actualMap = proxyResult?.data?.map ?: mapDto
                if (actualMap.pieces.isNotEmpty()) {
                    val scrambled = ScrambledPage(actualMap.dim, actualMap.mode, actualMap.pieces, actualMap.order)
                    pages.add(Page(index, "", json.encodeToString(scrambled)))
                }
            }
        }
        return pages
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    override fun fetchImage(page: Page): rx.Observable<Response> {
        if (page.url.contains("chapter-deferred-media")) {
            return client.newCall(GET(page.url, headers))
                .asObservable()
                .flatMap { response ->
                    val result = json.decodeFromString<ChapterDeferredResponse>(response.body.string())
                    val map = result.data?.maps?.firstOrNull()
                    if (map != null) {
                        fetchCanvasImage(map)
                    } else {
                        super.fetchImage(page)
                    }
                }
        }

        val pageMapJson = page.url.substringAfter("pageMap=", "")
        if (pageMapJson.isNotEmpty()) {
            val pageMap = json.decodeFromString<DeferredPageMap>(pageMapJson)
            return fetchCanvasImage(pageMap)
        }

        // استخدام super.fetchImage بدلاً من getImage
        return super.fetchImage(page)
    }
        val width = scrambledPage.dim.getOrNull(0) ?: 800
        val height = scrambledPage.dim.getOrNull(1) ?: 1250
        val modeParts = scrambledPage.mode.removePrefix("grid_").split("x")
        val rows = modeParts.getOrNull(0)?.toIntOrNull() ?: 1
        val cols = modeParts.getOrNull(1)?.toIntOrNull() ?: 1
        val pieceWidth = width / cols
        val pieceHeight = height / rows
        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        scrambledPage.pieces.forEachIndexed { index, pieceUrl ->
            val targetIndex = scrambledPage.order?.getOrNull(index) ?: index
            val r = targetIndex / cols
            val c = targetIndex % cols
            val pieceRequest = Request.Builder().url(pieceUrl).headers(headers).build()
            val pieceResponse = client.newCall(pieceRequest).execute()
            val pieceBytes = pieceResponse.body.bytes()
            val cleanedBytes = if (pieceBytes.size > 20 && String(pieceBytes, 0, 20).contains("base64")) {
                val base64String = String(pieceBytes).substringAfter("base64,")
                Base64.decode(base64String, Base64.DEFAULT)
            } else {
                pieceBytes
            }
            val bitmap = BitmapFactory.decodeByteArray(cleanedBytes, 0, cleanedBytes.size)
            if (bitmap != null) {
                val left = c * pieceWidth
                val top = r * pieceHeight
                canvas.drawBitmap(bitmap, left.toFloat(), top.toFloat(), null)
                bitmap.recycle()
            }
        }
        val outputStream = ByteArrayOutputStream()
        resultBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        val finalBytes = outputStream.toByteArray()
        resultBitmap.recycle()
        return Response.Builder()
            .request(Request.Builder().url(page.url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(finalBytes.toResponseBody("image/jpeg".toMediaType()))
            .build()
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}

@Serializable
data class LatestUpdatesResponse(
    val success: Boolean,
    val data: List<MangaDto> = emptyList()
)

@Serializable
data class MangaDto(
    val mangaId: Int,
    val mangaSlug: String? = null,
    val mangaTitle: String? = null,
    val coverImage: String? = null,
    val type: String? = null,
    val status: String? = null
)

@Serializable
data class SeriesDetailsResponse(
    val success: Boolean,
    val data: SeriesDetailsData? = null
)

@Serializable
data class SeriesDetailsData(
    val id: Int? = null,
    val mangaTitle: String? = null,
    val title: String? = null,
    val coverImage: String? = null,
    val status: String? = null,
    val metadata: SeriesMetadata? = null
)

@Serializable
data class SeriesMetadata(
    val author: String? = null,
    val artist: String? = null,
    val descriptions: Map<String, String> = emptyMap()
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
    val cdn_path: String? = null,
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
    val order: List<Int>? = null,
    val token: String? = null,
    val method: String? = null
)

@Serializable
data class ChapterPageData(
    val id: Int,
    val images: List<String>,
    val maps: List<DeferredPageMap>
)

@Serializable
data class ScrambledPage(
    val dim: List<Int>,
    val mode: String,
    val pieces: List<String>,
    val order: List<Int>? = null
)

@Serializable
data class MapProxyResponse(
    val success: Boolean,
    val data: MapProxyData
)

@Serializable
data class MapProxyData(
    val map: DeferredPageMap
)
