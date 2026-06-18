package eu.kanade.tachiyomi.extension.ar.procomic

import android.util.Base64
import eu.kanade.tachiyomi.extension.ar.procomic.ProComicCrypto.decryptUrl
import eu.kanade.tachiyomi.extension.ar.procomic.ProComicCrypto.toAbsoluteUrl
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
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
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class ProComic : HttpSource() {

    override val name = "ProComic"
    override val baseUrl = "https://procomic.pro"
    override val lang = "ar"
    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .rateLimit(2)
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            
            if (request.url.pathSegments.lastOrNull()?.contains("deferred") == true || request.url.toString().contains("/i/")) {
                response
            } else if (response.code == 200 && response.body.contentType()?.subtype == "json") {
                val bodyString = response.body.string()
                if (bodyString.contains("\"map\"") || bodyString.contains("\"pieces\"")) {
                    val interceptorClient = network.client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
                    val newBody = processJsonResponse(bodyString, interceptorClient, request.headers)
                    response.newBuilder().body(newBody.toResponseBody(response.body.contentType())).build()
                } else {
                    response.newBuilder().body(bodyString.toResponseBody(response.body.contentType())).build()
                }
            } else {
                response
            }
        }
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", "$baseUrl/")

    // Popular Manga
    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/api/series/filter".toHttpUrl().newBuilder()
            .addQueryStringParameter("page", page.toString())
            .addQueryStringParameter("sort", "views")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val res = json.decodeFromStream<FilterResponse>(response.body.byteStream())
        val mangas = res.data.map { dto ->
            SManga.create().apply {
                url = "/series/${dto.type}/${dto.id}/${dto.slug}"
                title = dto.title
                thumbnail_url = dto.cover?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            }
        }
        return MangasPage(mangas, res.data.size >= 24)
    }

    // Latest Updates
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/api/series/filter".toHttpUrl().newBuilder()
            .addQueryStringParameter("page", page.toString())
            .addQueryStringParameter("sort", "latest")
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/series/filter".toHttpUrl().newBuilder()
            .addQueryStringParameter("page", page.toString())
            .addQueryStringParameter("search", query)
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // Details
    override fun mangaDetailsParse(response: Response): SManga {
        val slug = response.request.url.pathSegments.lastOrNull() ?: throw Exception("Invalid URL")
        val detailUrl = "$baseUrl/api/series/$slug"
        val detailRes = client.newCall(GET(detailUrl, headers)).execute()
        val dto = json.decodeFromStream<MangaDetailResponse>(detailRes.body.byteStream()).data
            ?: throw Exception("Manga data empty")

        return SManga.create().apply {
            title = dto.title
            thumbnail_url = dto.cover?.let { if (it.startsWith("http")) it else "$baseUrl$it" }
            description = dto.summary
            status = when (dto.status?.lowercase()) {
                "ongoing", "publishing" -> SManga.ONGOING
                "completed", "finished" -> SManga.COMPLETED
                "on_hold" -> SManga.ON_HOLD
                "canceled" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters
    override fun chapterListParse(response: Response): List<SChapter> {
        val segments = response.request.url.pathSegments
        val id = segments.getOrNull(segments.size - 2) ?: throw Exception("Invalid Manga ID")
        val chaptersUrl = "$baseUrl/api/series/$id/chapters"
        val chaptersRes = client.newCall(GET(chaptersUrl, headers)).execute()
        val res = json.decodeFromStream<ChaptersResponse>(chaptersRes.body.byteStream())

        return res.data.map { dto ->
            SChapter.create().apply {
                url = "/series/${segments.getOrNull(1) ?: "manga"}/$id/${segments.last()}/${dto.id}"
                name = "الفصل ${dto.chapterNumber}" + if (!dto.title.isNullOrBlank()) " - ${dto.title}" else ""
                date_upload = 0L
            }
        }
    }

    // Page List
    override fun pageListParse(response: Response): List<Page> {
        val bodyString = response.body.string()
        val pages = mutableListOf<Page>()
        
        if (bodyString.startsWith("{") && bodyString.contains("\"pages\"")) {
            val res = json.decodeFromString<InterceptorResponse>(bodyString)
            res.pages.forEachIndexed { index, scrambledMap ->
                val jsonString = json.encodeToString(scrambledMap)
                val base64Data = Base64.encodeToString(jsonString.toByteArray(), Base64.NO_WRAP)
                pages.add(Page(index, "", "DECRYPTED_MAP:$base64Data"))
            }
        }
        return pages
    }

    override fun fetchImageUrl(page: Page): rx.Observable<String> = rx.Observable.just(page.url)

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    override fun imageRequest(page: Page): Request {
        val data = page.imageUrl
        if (data != null && data.startsWith("DECRYPTED_MAP:")) {
            val base64 = data.substringAfter("DECRYPTED_MAP:")
            val fakeUrl = "$baseUrl/reconstruct/page_${page.number}.jpg?data=$base64"
            return GET(fakeUrl, headers)
        }
        return super.imageRequest(page)
    }

    override fun fetchImage(page: Page): rx.Observable<Response> {
        val data = page.imageUrl
        if (data != null && data.startsWith("DECRYPTED_MAP:")) {
            return rx.Observable.fromCallable {
                val base64 = data.substringAfter("DECRYPTED_MAP:")
                val jsonString = String(Base64.decode(base64, Base64.DEFAULT))
                val map = json.decodeFromString<ScrambledMap>(jsonString)
                
                val baseClient = network.client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
                val imageBytes = ProComicImageReconstructor.reconstructPage(map, baseClient, headers) 
                    ?: throw Exception("Failed to reconstruct page")
                    
                val responseBody = imageBytes.toResponseBody("image/jpeg".toMediaType())
                Response.Builder()
                    .request(imageRequest(page))
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody)
                    .build()
            }
        }
        return super.fetchImage(page)
    }

    // معالجة الـ JSON التابع للـ Interceptor لدمج الآليتين (القديمة والجديدة JWT التراكمية)
    private fun processJsonResponse(bodyString: String, baseClient: OkHttpClient, reqHeaders: Headers): String {
        return try {
            if (bodyString.contains("\"map\"")) {
                val initialRes = json.decodeFromString<InitialProxyResponse>(bodyString)
                val mapObj = initialRes.data?.map ?: return bodyString
                
                val cdnBase = mapObj.pieces.firstOrNull()?.toHttpUrl()?.let { "${it.scheme}://${it.host}" } ?: "https://img1.procomic.pro"

                // إذا كان التوكن يحمل صيغة الـ JWT (الآلية التراكمية الجديدة)
                if (mapObj.token.isNotEmpty() && mapObj.method == "browser_session") {
                    val chapterId = bodyString.substringAfter("/chapter-map-proxy-plan/").substringBefore("\"").filter { it.isDigit() }
                    if (chapterId.isNotEmpty()) {
                        val deferredUrl = "$baseUrl/chapter-deferred-media/$chapterId?token=${mapObj.token}&split=3"
                        val defReq = Request.Builder().url(deferredUrl).headers(reqHeaders).get().build()
                        val defRes = baseClient.newCall(defReq).execute()
                        
                        if (defRes.code == 200) {
                            val defData = json.decodeFromStream<ChapterDeferredResponse>(defRes.body.byteStream())
                            val mapsList = defData.data?.maps
                            if (!mapsList.isNullOrEmpty()) {
                                val combinedPieces = mapObj.pieces.map { it.toAbsoluteUrl(cdnBase).decryptUrl() }.toMutableList()
                                val combinedOrder = mapObj.order.toMutableList()
                                val combinedRects = mapObj.rects?.toMutableList() ?: mutableListOf()

                                mapsList.forEach { dMap ->
                                    val decryptedPieces = dMap.pieces.map { it.toAbsoluteUrl(cdnBase).decryptUrl() }
                                    combinedPieces.addAll(decryptedPieces)
                                    combinedOrder.addAll(dMap.order)
                                    dMap.rects?.let { combinedRects.addAll(it) }
                                }

                                val finalMap = ScrambledMap(
                                    dim = mapObj.dim,
                                    mode = mapObj.mode,
                                    pieces = combinedPieces,
                                    order = combinedOrder,
                                    token = mapObj.token,
                                    rects = if (combinedRects.isNotEmpty()) combinedRects else null
                                )
                                
                                val interceptorResponse = InterceptorResponse(success = true, pages = listOf(finalMap))
                                return json.encodeToString(interceptorResponse)
                            }
                        }
                        defRes.close()
                    }
                }
                
                // إذا لم يكن JWT (الآلية القديمة المباشرة)
                val decryptedPieces = mapObj.pieces.map { it.toAbsoluteUrl(cdnBase).decryptUrl() }
                val finalMap = ScrambledMap(
                    dim = mapObj.dim,
                    mode = mapObj.mode,
                    pieces = decryptedPieces,
                    order = mapObj.order,
                    token = mapObj.token,
                    rects = mapObj.rects
                )
                val interceptorResponse = InterceptorResponse(success = true, pages = listOf(finalMap))
                json.encodeToString(interceptorResponse)
            } else {
                bodyString
            }
        } catch (e: Exception) {
            bodyString
        }
    }
}
