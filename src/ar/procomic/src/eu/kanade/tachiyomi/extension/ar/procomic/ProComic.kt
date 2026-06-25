package eu.kanade.tachiyomi.extension.ar.procomic

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
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

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
        // قفل التزامن لمنع تداخل طلبات التجديد
        private val refreshLock = Any()
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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .rateLimit(2, 1)
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url

            var response = chain.proceed(request)

            val isPotentialBase64Image = response.isSuccessful && request.method == "GET" &&
                url.encodedPath.contains("/i/") && url.host.contains("procomic")

            if (isPotentialBase64Image) {
                var responseBody = response.body
                var bytes = responseBody?.bytes() ?: ByteArray(0)

                var isBase64Text = bytes.size > 20 &&
                    bytes[0] == 'd'.code.toByte() &&
                    bytes[1] == 'a'.code.toByte() &&
                    bytes[2] == 't'.code.toByte() &&
                    bytes[3] == 'a'.code.toByte() &&
                    bytes[4] == ':'.code.toByte()

                // إذا اكتشفنا أن الرابط تالف (الصورة ليست Base64)
                val fragmentParts = url.fragment?.split("||")
                if (!isBase64Text && fragmentParts != null && fragmentParts.size == 3) {
                    
                    // هنا تكمن فكرتك الذكية: وضع الطابور للقطع لكي لا تتداخل الطلبات
                    synchronized(refreshLock) {
                        try {
                            // فترة انتظار بسيطة (150 جزء من الثانية) لتهدئة السيرفر وضمان الترتيب
                            Thread.sleep(150)
                            
                            val chapterId = fragmentParts[0]
                            val mapBase64 = fragmentParts[1]
                            val srcIdx = fragmentParts[2].toIntOrNull() ?: 0

                            val mapJson = String(Base64.decode(mapBase64, Base64.URL_SAFE))
                            
                            val proxyReq = POST(
                                "$baseUrl/chapter-map-proxy-plan/$chapterId",
                                headersBuilder()
                                    .set("Origin", baseUrl)
                                    .set("Referer", "$baseUrl/")
                                    .set("Content-Type", "application/json")
                                    .set("Accept", "application/json")
                                    .build(),
                                mapJson.toRequestBody("application/json".toMediaType())
                            )

                            val proxyResp = chain.proceed(proxyReq)
                            if (proxyResp.isSuccessful) {
                                val proxyData = json.decodeFromStream<ProxyPlanResponse>(proxyResp.body!!.byteStream())
                                val newPieces = proxyData.data?.map?.pieces
                                if (!newPieces.isNullOrEmpty() && srcIdx < newPieces.size) {
                                    val newPieceUrl = newPieces[srcIdx].let {
                                        if (it.startsWith("http")) it else "https://${url.host}$it"
                                    }
                                    
                                    // تحميل القطعة برابطها الجديد والطازج
                                    val newReq = GET(newPieceUrl, request.headers)
                                    val newResp = chain.proceed(newReq)
                                    if (newResp.isSuccessful) {
                                        bytes = newResp.body?.bytes() ?: bytes
                                        isBase64Text = bytes.size > 20 &&
                                            bytes[0] == 'd'.code.toByte() &&
                                            bytes[1] == 'a'.code.toByte() &&
                                            bytes[2] == 't'.code.toByte() &&
                                            bytes[3] == 'a'.code.toByte() &&
                                            bytes[4] == ':'.code.toByte()
                                    }
                                }
                            }
                        } catch (e: Exception) { 
                            // تجاهل الأخطاء لكي لا ينهار التطبيق
                        }
                    } // نهاية قفل التزامن (يسمح للقطعة التالية بالبدء)
                }

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
                        .body(bytes.toResponseBody(responseBody?.contentType()))
                        .build()
                }
            }
            response
        }
        .build()

    override fun imageUrlParse(response: Response): String = ""

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
        val mapTokens = mutableListOf<String>()

        metadataImages.forEach { imgPath ->
            val fullUrl = imgPath.toAbsoluteUrl(cdnBase)
            if (seenUrls.add(fullUrl)) pages.add(Page(pages.size, imageUrl = fullUrl))
        }

        mapsList.forEach { map ->
            when {
                map.token.isNotBlank() && map.pieces.isEmpty() && map.token.isJwt() -> {
                    mapTokens.add(map.token)
                }
                map.token.isNotBlank() && map.pieces.isEmpty() -> {
                    val originalMapBase64 = Base64.encodeToString(json.encodeToString(map).toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
                    val resolved = resolveMap(map, chapterId, apiHeaders, getSessionKey)
                    if (resolved != null && resolved.pieces.isNotEmpty()) {
                        val absolutePieces = resolved.pieces.map { it.toAbsoluteUrl(cdnBase) }
                        processMap(originalMapBase64, chapterId, resolved.order, absolutePieces, resolved.token, pages, seenUrls)
                    }
                }
                map.pieces.isNotEmpty() -> {
                    val absolutePieces = map.pieces.map { it.toAbsoluteUrl(cdnBase) }
                    processMap("", chapterId, map.order, absolutePieces, map.token, pages, seenUrls)
                }
            }
        }

        for (jwtToken in mapTokens) {
            try {
                val newPages = fetchDeferredPages(chapterId, jwtToken, apiHeaders, seenUrls, cdnBase, getSessionKey)
                pages.addAll(newPages)
            } catch (e: Exception) { }
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

    private fun fetchDeferredPages(
        chapterId: String,
        jwtToken: String,
        apiHeaders: Headers,
        seenUrls: MutableSet<String>,
        cdnBase: String,
        getSessionKey: () -> String?,
    ): List<Page> {
        val pages = mutableListOf<Page>()
        val jwtSplit = jwtSplitValue(jwtToken)
        val splitResponses = mutableListOf<ChapterDeferredData>()

        for (s in 0..jwtSplit) {
            try {
                val resp = client.newCall(
                    GET(
                        "$baseUrl/chapter-deferred-media/$chapterId?token=$jwtToken&split=$s",
                        apiHeaders,
                    ),
                ).execute()

                if (!resp.isSuccessful) continue

                val parsed = resp.parseAs<ChapterDeferredResponse>()
                if (parsed.success && parsed.data != null) splitResponses.add(parsed.data)
            } catch (e: Exception) {
                continue
            }
        }

        for (splitData in splitResponses) {
            val absolutePieceUrls = mutableSetOf<String>()

            splitData.maps.forEach { map ->
                when {
                    map.token.isNotBlank() && map.pieces.isEmpty() && map.token.isJwt() -> { }
                    else -> {
                        val originalMapBase64 = Base64.encodeToString(json.encodeToString(map).toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
                        val resolved = resolveMap(map, chapterId, apiHeaders, getSessionKey)
                        if (resolved != null && resolved.pieces.isNotEmpty()) {
                            val absolutePieces = resolved.pieces.map { it.toAbsoluteUrl(cdnBase) }
                            absolutePieceUrls.addAll(absolutePieces)
                            processMap(originalMapBase64, chapterId, resolved.order, absolutePieces, resolved.token, pages, seenUrls)
                        }
                    }
                }
            }

            splitData.images.forEach { url ->
                val fullUrl = url.toAbsoluteUrl(cdnBase)
                if (fullUrl !in absolutePieceUrls && seenUrls.add(fullUrl)) {
                    pages.add(Page(pages.size, imageUrl = fullUrl))
                }
            }
        }

        return pages
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
                    val proxyReq = POST(
                        "$baseUrl/chapter-map-proxy-plan/$chapterId",
                        apiHeaders.newBuilder()
                            .set("Origin", baseUrl)
                            .set("Referer", "$baseUrl/")
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

    private fun processMap(
        originalMapBase64: String,
        chapterId: String,
        order: List<Int>,
        pieces: List<String>,
        signedToken: String,
        pages: MutableList<Page>,
        seenUrls: MutableSet<String>,
    ) {
        if (pieces.isEmpty()) return

        for (targetIdx in pieces.indices) {
            val srcIdx = if (order.size == pieces.size) order[targetIdx] else targetIdx
            val basePieceUrl = pieces.getOrNull(srcIdx) ?: continue

            val pieceUrl = if (signedToken.isNotBlank() && !basePieceUrl.contains("/i/eyJ2IjoxLCJpdiI6I")) {
                if (basePieceUrl.contains("?")) "$basePieceUrl&token=$signedToken"
                else "$basePieceUrl?token=$signedToken"
            } else {
                basePieceUrl
            }

            val finalUrl = if (originalMapBase64.isNotEmpty() && pieceUrl.contains("/i/")) {
                "$pieceUrl#$chapterId||$originalMapBase64||$srcIdx"
            } else {
                pieceUrl
            }

            if (seenUrls.add(finalUrl)) {
                pages.add(Page(pages.size, url = finalUrl, imageUrl = finalUrl))
            }
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

    private inline fun <reified T> Response.parseAs(): T =
        json.decodeFromStream(body.byteStream())
}

private const val DEFAULT_SPLIT = 3

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
