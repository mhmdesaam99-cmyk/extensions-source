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
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import rx.Observable
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class ProComic : HttpSource() {

    override val name = "ProComic"

    override val baseUrl = "https://procomic.pro"

    override val lang = "ar"

    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .rateLimit(2, 1)
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()

            if (url.startsWith(SCRAMBLED_SCHEME)) {
                val pageMap = request.tag(ScrambledMap::class.java)
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
            val isImageRequest = response.isSuccessful && request.method == "GET" && url.contains("/i/")

            if (isImageRequest) {
                val responseBody = response.body
                if (responseBody != null) {
                    val bytes = responseBody.bytes()
                    val isBase64Text = bytes.size > 11 &&
                        bytes[0] == 'd'.code.toByte() &&
                        bytes[1] == 'a'.code.toByte() &&
                        bytes[2] == 't'.code.toByte() &&
                        bytes[3] == 'a'.code.toByte() &&
                        bytes[4] == ':'.code.toByte()

                    if (isBase64Text) {
                        val bodyString = String(bytes)
                        if (bodyString.contains("base64,")) {
                            val base64Data = bodyString.substringAfter("base64,")
                            val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                            val mimeTypeString = bodyString.substringAfter("data:").substringBefore(";")
                            val mimeType = try {
                                mimeTypeString.toMediaType()
                            } catch (_: Exception) {
                                "image/avif".toMediaType()
                            }

                            return@addInterceptor response.newBuilder()
                                .body(decodedBytes.toResponseBody(mimeType))
                                .build()
                        }
                    }
                    return@addInterceptor response.newBuilder()
                        .body(bytes.toResponseBody(responseBody.contentType()))
                        .build()
                }
            }
            response
        }
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request {
        val payload = mapOf(
            "page" to page,
            "perPage" to 30,
            "sort" to mapOf("field" to "views", "order" to "desc")
        )
        val body = json.encodeToString(payload).toRequestBody("application/json".toMediaType())
        return POST("$baseUrl/api/mangas/filter", headers, body)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val responseString = response.body?.string() ?: ""
        val result = json.decodeFromString<MangasResponse>(responseString)
        val mangas = result.data.map { dto ->
            SManga.create().apply {
                url = "/manga/${dto.slug}"
                title = dto.title
                thumbnail_url = dto.cover?.let { "$baseUrl/storage/$it" }
            }
        }
        return MangasPage(mangas, result.page < result.totalPages)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val payload = mapOf(
            "page" to page,
            "perPage" to 30,
            "sort" to mapOf("field" to "last_chapter_created_at", "order" to "desc")
        )
        val body = json.encodeToString(payload).toRequestBody("application/json".toMediaType())
        return POST("$baseUrl/api/mangas/filter", headers, body)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val payload = mapOf(
            "page" to page,
            "perPage" to 30,
            "search" to query,
            "sort" to mapOf("field" to "views", "order" to "desc")
        )
        val body = json.encodeToString(payload).toRequestBody("application/json".toMediaType())
        return POST("$baseUrl/api/mangas/filter", headers, body)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url.substringAfter("/manga/")
        return GET("$baseUrl/api/mangas/$slug", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val responseString = response.body?.string() ?: ""
        val dto = json.decodeFromString<MangaDetailsDto>(responseString)
        return SManga.create().apply {
            url = "/manga/${dto.slug}"
            title = dto.title
            thumbnail_url = dto.cover?.let { "$baseUrl/storage/$it" }
            description = dto.summary
            status = when (dto.status) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            genre = dto.genres.joinToString { it.title }
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.substringAfter("/manga/")
        return GET("$baseUrl/api/mangas/$slug/chapters", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val responseString = response.body?.string() ?: ""
        val result = json.decodeFromString<ChaptersResponse>(responseString)
        return result.data.map { dto ->
            SChapter.create().apply {
                url = "/chapter/${dto.id}"
                name = "الفصل ${dto.chapterNumber}" + if (!dto.title.isNullOrBlank()) " - ${dto.title}" else ""
                chapter_number = dto.chapterNumber.toFloatOrNull() ?: -1f
            }
        }.reversed()
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val id = chapter.url.substringAfter("/chapter/")
        return GET("$baseUrl/api/chapters/$id", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val responseString = response.body?.string() ?: ""
        val result = json.decodeFromString<ChapterDto>(responseString)
        val metadata = result.metadata ?: return emptyList()
        
        val pages = mutableListOf<Page>()
        var index = 0

        metadata.images.forEach { imageUrl ->
            val matchingMap = metadata.maps.find { it.pieces.contains(imageUrl) }
            if (matchingMap != null) {
                if (matchingMap.pieces.first() == imageUrl) {
                    val scrambledMap = ScrambledMap(
                        dim = matchingMap.dim,
                        mode = matchingMap.mode,
                        pieces = matchingMap.pieces
                    )
                    val mapJson = json.encodeToString(scrambledMap)
                    val encodedMap = Base64.encodeToString(mapJson.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
                    val pseudoUrl = "$SCRAMBLED_SCHEME${result.id}_part_${matchingMap.pieces.indexOf(imageUrl)}.jpg#$encodedMap"
                    pages.add(Page(index++, "", pseudoUrl))
                }
            } else {
                pages.add(Page(index++, "", imageUrl))
            }
        }
        return pages
    }

    override fun pageListParse(response: Response, chapter: SChapter): List<Page> = pageListParse(response)

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun fetchImageUrl(page: Page): Observable<String> = Observable.just(page.imageUrl!!)

    override fun imageRequest(page: Page): Request {
        val url = page.imageUrl!!
        if (url.startsWith(SCRAMBLED_SCHEME)) {
            val encodedMap = url.substringAfter("#")
            val mapJson = String(Base64.decode(encodedMap, Base64.DEFAULT or Base64.URL_SAFE))
            val mapData = json.decodeFromString<ScrambledMap>(mapJson)
            return Request.Builder()
                .url(url)
                .headers(headers)
                .tag(ScrambledMap::class.java, mapData)
                .build()
        }
        return GET(url, headers)
    }

    private fun reconstructPage(map: ScrambledMap): ByteArray? {
        val totalWidth = map.dim.getOrNull(0) ?: return null
        val totalHeight = map.dim.getOrNull(1) ?: return null

        val piecesBitmaps = map.pieces.map { pieceUrl ->
            val request = GET(pieceUrl, headers)
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }
            val bytes = response.body?.bytes() ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        }

        if (piecesBitmaps.isEmpty()) return null

        val targetHeight = if (totalHeight > MAX_SAFE_HEIGHT) MAX_SAFE_HEIGHT else totalHeight
        val scaleY = targetHeight.toFloat() / totalHeight.toFloat()

        val resultBitmap = Bitmap.createBitmap(totalWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        if (map.mode.startsWith("vertical_")) {
            val numPieces = map.mode.substringAfter("vertical_").toIntOrNull() ?: map.pieces.size
            var currentY = 0f

            for (i in 0 until numPieces) {
                val bitmap = piecesBitmaps.getOrNull(i) ?: break
                val pieceOriginalHeight = totalHeight / numPieces
                val pieceTargetHeight = pieceOriginalHeight * scaleY

                val srcRect = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
                val dstRect = android.graphics.RectF(0f, currentY, totalWidth.toFloat(), currentY + pieceTargetHeight)

                canvas.drawBitmap(bitmap, srcRect, dstRect, null)
                currentY += pieceTargetHeight
            }
        } else {
            val bitmap = piecesBitmaps[0]
            val srcRect = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
            val dstRect = android.graphics.RectF(0f, 0f, totalWidth.toFloat(), targetHeight.toFloat())
            canvas.drawBitmap(bitmap, srcRect, dstRect, null)
        }

        piecesBitmaps.forEach { it.recycle() }

        val outputStream = ByteArrayOutputStream()
        resultBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        resultBitmap.recycle()

        return outputStream.toByteArray()
    }

    companion object {
        private const val SCRAMBLED_SCHEME = "https://localhost/scrambled_asset/"
        private const val MAX_SAFE_HEIGHT = 6000
    }
}

@Serializable
data class ScrambledMap(
    val dim: List<Int>,
    val mode: String,
    val pieces: List<String>
)

@Serializable
data class MangasResponse(
    val data: List<MangaDto> = emptyList(),
    val page: Int = 1,
    @SerialName("total_pages") val totalPages: Int = 1,
)

@Serializable
data class MangaDto(
    val id: Int = 0,
    val title: String = "",
    val slug: String = "",
    val cover: String? = null,
)

@Serializable
data class MangaDetailsDto(
    val id: Int = 0,
    val title: String = "",
    val slug: String = "",
    val cover: String? = null,
    val summary: String? = null,
    val status: String? = null,
    val genres: List<GenreDto> = emptyList(),
)

@Serializable
data class GenreDto(
    val id: Int = 0,
    val title: String = "",
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
)
