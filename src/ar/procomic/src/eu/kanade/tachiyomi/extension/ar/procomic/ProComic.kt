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
import java.io.ByteArrayOutputStream
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
        private const val CHROME_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private fun String.toAbsoluteUrl(cdnBase: String, token: String): String {
        val cleanPiece = this.trim()
        if (cleanPiece.startsWith("http")) return cleanPiece

        val base = if (cdnBase.isBlank()) "https://img1.procomic.pro" else cdnBase

        val url = when {
            cleanPiece.startsWith("eyJ") -> "$base/i/$cleanPiece"
            cleanPiece.startsWith("/i/") -> "$base$cleanPiece"
            cleanPiece.startsWith("/") -> "$base$cleanPiece"
            else -> "$base/$cleanPiece"
        }

        if (token.isNotBlank() && !url.contains("/i/") && !url.contains("eyJ")) {
            return if (url.contains("?")) "$url&token=$token" else "$url?token=$token"
        }
        return url
    }

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .rateLimit(2, 1)
        // المُعترض الأول: دمج القوالب مع شبكة أمان تمنع انهيار التطبيق
        .addInterceptor { chain ->
            val request = chain.request()
            val urlStr = request.url.toString()

            if (urlStr.startsWith("procomic-map://")) {
                val mapJsonBase64 = urlStr.substringAfter("procomic-map://")
                val mapJsonStr = try {
                    String(Base64.decode(mapJsonBase64, Base64.URL_SAFE or Base64.DEFAULT))
                } catch (e: Exception) { "{}" }
                
                val mapData = try { json.decodeFromString<StitchInstruction>(mapJsonStr) } catch (e: Exception) { StitchInstruction(emptyList(), emptyList()) }

                val bitmaps = mutableMapOf<Int, Bitmap>()
                var pieceWidth = 0
                var pieceHeight = 0

                for (targetIdx in mapData.pieces.indices) {
                    val pieceUrl = mapData.pieces[targetIdx]
                    val req = Request.Builder().url(pieceUrl).headers(request.headers).build()
                    val resp = chain.proceed(req) 
                    val bytes = resp.body?.bytes() ?: continue
                    
                    var bitmap: Bitmap? = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch(e: Exception) { null }
                    
                    // محاولة استخدام محلل التطبيق بذكاء في حال كانت الصورة AVIF
                    if (bitmap == null) {
                        try {
                            val decoderClass = Class.forName("tachiyomi.decoder.ImageDecoder")
                            val methods = decoderClass.methods.filter { it.name == "newInstance" }
                            for (method in methods) {
                                try {
                                    val args = method.parameterTypes.map { type ->
                                        when (type) {
                                            java.io.InputStream::class.java -> bytes.inputStream()
                                            Boolean::class.javaPrimitiveType -> false
                                            Int::class.javaPrimitiveType -> 0
                                            else -> null
                                        }
                                    }.toTypedArray()
                                    val decoder = method.invoke(null, *args)
                                    if (decoder != null) {
                                        val decodeMethod = decoder.javaClass.methods.firstOrNull { it.name == "decode" }
                                        bitmap = decodeMethod?.invoke(decoder) as? Bitmap
                                        val recycleMethod = decoder.javaClass.methods.firstOrNull { it.name == "recycle" }
                                        recycleMethod?.invoke(decoder)
                                        if (bitmap != null) break
                                    }
                                } catch (e: Exception) {}
                            }
                        } catch (e: Exception) {}
                    }
                    
                    if (bitmap != null) {
                        bitmaps[targetIdx] = bitmap
                        if (pieceWidth == 0) pieceWidth = bitmap.width
                        if (pieceHeight == 0) pieceHeight = bitmap.height
                    }
                }

                // شبكة الأمان: إذا فشل دمج الصور، نعرض صفحة تنبيه بدلاً من تحطيم التطبيق
                if (bitmaps.isEmpty() || pieceWidth == 0 || pieceHeight == 0) {
                    val errorBmp = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(errorBmp)
                    canvas.drawColor(android.graphics.Color.DKGRAY)
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 30f
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    canvas.drawText("عذراً، فشل فك تشفير الصور (AVIF/Base64)", 400f, 450f, paint)
                    canvas.drawText("قد لا يدعم هاتفك أو القارئ هذه الصيغة", 400f, 500f, paint)
                    val os = ByteArrayOutputStream()
                    errorBmp.compress(Bitmap.CompressFormat.JPEG, 90, os)
                    return@addInterceptor Response.Builder()
                        .request(request).code(200).protocol(Protocol.HTTP_1_1).message("OK")
                        .body(os.toByteArray().toResponseBody("image/jpeg".toMediaType()))
                        .build()
                }

                val totalWidth = mapData.dim.getOrElse(0) { pieceWidth }
                val totalHeight = mapData.dim.getOrElse(1) { pieceHeight }

                return@addInterceptor try {
                    val cols = Math.max(1, Math.round(totalWidth.toFloat() / pieceWidth))
                    val resultBitmap = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(resultBitmap)

                    for ((targetIdx, bitmap) in bitmaps) {
                        val x = (targetIdx % cols) * pieceWidth
                        val y = (targetIdx / cols) * pieceHeight
                        canvas.drawBitmap(bitmap, x.toFloat(), y.toFloat(), null)
                        bitmap.recycle()
                    }

                    val outputStream = ByteArrayOutputStream()
                    resultBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                    val finalBytes = outputStream.toByteArray()
                    resultBitmap.recycle()

                    Response.Builder()
                        .request(request)
                        .code(200)
                        .protocol(Protocol.HTTP_1_1)
                        .message("OK")
                        .body(finalBytes.toResponseBody("image/jpeg".toMediaType()))
                        .build()
                } catch (e: Throwable) { // حماية إضافية للذاكرة
                    val errorBmp = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(errorBmp)
                    canvas.drawColor(android.graphics.Color.DKGRAY)
                    val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 35f; textAlign = android.graphics.Paint.Align.CENTER }
                    canvas.drawText("حجم الصورة كبير جداً على الذاكرة", 400f, 500f, paint)
                    val os = ByteArrayOutputStream()
                    errorBmp.compress(Bitmap.CompressFormat.JPEG, 90, os)
                    Response.Builder().request(request).code(200).protocol(Protocol.HTTP_1_1).message("OK")
                        .body(os.toByteArray().toResponseBody("image/jpeg".toMediaType())).build()
                }
            }
            chain.proceed(request)
        }
        // المُعترض الثاني: فك تشفير نصوص Base64 بأمان تام
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val urlStr = response.request.url.toString()
            
            if (urlStr.contains("/i/")) {
                val body = response.body
                if (body != null && response.isSuccessful) {
                    val contentType = body.contentType()
                    val bytes = body.bytes() 
                    
                    val headStr = String(bytes.take(100).toByteArray(), Charsets.US_ASCII).trim()
                    if (headStr.startsWith("data:image/")) {
                        val bodyString = String(bytes, Charsets.UTF_8).trim()
                        val base64Data = bodyString.substringAfter("base64,").trim()
                        try {
                            val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                            return@addInterceptor response.newBuilder()
                                .body(decodedBytes.toResponseBody(contentType))
                                .build()
                        } catch (e: Exception) {
                            return@addInterceptor response.newBuilder()
                                .body(bytes.toResponseBody(contentType))
                                .build()
                        }
                    } else {
                        return@addInterceptor response.newBuilder()
                            .body(bytes.toResponseBody(contentType))
                            .build()
                    }
                }
            }
            response
        }
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("User-Agent", CHROME_USER_AGENT)

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/api/public/content/latest-updates?limit=30&category=comics&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<LatestUpdatesResponse>()
        val mangas = data.data.filter { it.type != "novel" }.map { it.toSManga() }
        return MangasPage(mangas, mangas.size >= 30)
    }

    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) = GET(
        "$baseUrl/api/public/content/latest-updates?limit=30&category=comics&page=$page" + (if (query.isNotBlank()) "&q=$query" else ""), headers)

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
                description = data.synopsis ?: data.description
                status = when (data.status?.lowercase()) {
                    "ongoing", "مستمر" -> SManga.ONGOING
                    "completed", "مكتمل" -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
            }
        } catch (e: Exception) { SManga.create() }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val p = manga.url.split("/")
        return GET("$baseUrl/api/public/${p[0]}/${p[1]}/chapters?page=1&limit=500&order=desc", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        return response.parseAs<ChaptersResponse>().data.map { ch ->
            val parts = response.request.url.pathSegments
            val idx = parts.indexOf("public")
            val seriesType = parts.getOrElse(idx + 1) { "manga" }
            val seriesId = parts.getOrElse(idx + 2) { "0" }
            val cdn = ch.cdnPath ?: "img1"
            
            SChapter.create().apply {
                url = "$seriesType/$seriesId/${ch.id}/${ch.chapterNumber}#$cdn"
                name = "الفصل ${ch.chapterNumber}" + (if (!ch.title.isNullOrBlank()) " - ${ch.title}" else "")
                date_upload = runCatching { dateFormat.parse(ch.publishedAt ?: "")?.time }.getOrNull() ?: 0L
                chapter_number = ch.chapterNumber.toFloatOrNull() ?: 0f
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val cleanUrl = chapter.url.substringBeforeLast("#")
        val cdnPath = chapter.url.substringAfterLast("#", "img1")
        
        val parts = cleanUrl.split("/")
        val seriesType = parts.getOrElse(0) { "manga" }
        val seriesId = parts.getOrElse(1) { "0" }
        val chapterId = parts.getOrElse(2) { "0" }

        val url = "$baseUrl/api/public/$seriesType/$seriesId/chapters".toHttpUrl()
            .newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("limit", "500")
            .addQueryParameter("order", "desc")
            .addQueryParameter("_cid", chapterId)
            .addQueryParameter("cdn", cdnPath)
            .build()

        return GET(url, headers.newBuilder().set("Accept", "application/json").build())
    }

    override fun pageListParse(response: Response): List<Page> {
        val chapterId = response.request.url.queryParameter("_cid") ?: return emptyList()
        val cdnPathFromUrl = response.request.url.queryParameter("cdn") ?: "img1"

        val apiHeaders = headers.newBuilder().set("Accept", "application/json").build()
        val pages = mutableListOf<Page>()
        val seenUrls = mutableSetOf<String>()

        var metadataImages = emptyList<String>()
        val mapsList = mutableListOf<DeferredPageMap>()

        var cachedSessionKey: String? = null
        var sessionKeyAttempted = false
        
        val getSessionKey = {
            if (!sessionKeyAttempted) {
                sessionKeyAttempted = true
                try {
                    val req1 = client.newCall(GET("$baseUrl/chapter-map-session-key/$chapterId", apiHeaders)).execute()
                    if (req1.isSuccessful) cachedSessionKey = req1.parseAs<SessionKeyResponse>().data?.key
                } catch (e: Exception) {}
                if (cachedSessionKey.isNullOrBlank()) {
                    try {
                        val req2 = client.newCall(GET("$baseUrl/chapter-map-session-key/$chapterId?legacy=1", apiHeaders)).execute()
                        if (req2.isSuccessful) cachedSessionKey = req2.parseAs<SessionKeyResponse>().data?.key
                    } catch (e: Exception) {}
                }
            }
            cachedSessionKey
        }

        val currentData = try { response.parseAs<ChaptersResponse>() } catch (e: Exception) { ChaptersResponse() }
        for (ch in currentData.data) {
            if (ch.id.toString() == chapterId) {
                metadataImages = ch.metadata?.images ?: emptyList()
                ch.metadata?.maps?.let { mapsList.addAll(it) }
                break
            }
        }

        val cdnBase = when {
            cdnPathFromUrl.startsWith("http") -> cdnPathFromUrl
            cdnPathFromUrl.contains(".") -> "https://$cdnPathFromUrl"
            cdnPathFromUrl.isNotBlank() -> "https://$cdnPathFromUrl.procomic.pro"
            else -> "https://img1.procomic.pro"
        }
        
        val mapTokens = mutableListOf<String>()

        metadataImages.forEach { imgPath ->
            val fullUrl = imgPath.toAbsoluteUrl(cdnBase, "")
            if (seenUrls.add(fullUrl)) pages.add(Page(pages.size, imageUrl = fullUrl))
        }

        mapsList.forEach { map ->
            if (map.token.isNotBlank() && map.pieces.isEmpty()) {
                if (map.token.startsWith("eyJhbGci")) {
                    mapTokens.add(map.token) 
                } else {
                    val resolved = resolveMap(map, chapterId, apiHeaders, getSessionKey)
                    if (resolved != null && resolved.pieces.isNotEmpty()) {
                        processMap(resolved, cdnBase, pages, seenUrls)
                    }
                }
            } else if (map.pieces.isNotEmpty()) {
                processMap(map, cdnBase, pages, seenUrls)
            }
        }

        for (jwtToken in mapTokens) {
            try {
                val newPages = fetchDeferredPages(chapterId, jwtToken, apiHeaders, seenUrls, cdnBase, getSessionKey)
                pages.addAll(newPages)
            } catch (e: Exception) {}
        }

        return pages
    }

    private fun resolveMap(
        map: DeferredPageMap, chapterId: String, apiHeaders: Headers, getSessionKey: () -> String?
    ): DeferredPageMap? {
        if (map.pieces.isNotEmpty()) return map

        if (map.token.isNotBlank()) {
            var dec: DeferredPageMap? = null
            val sk = getSessionKey()
            if (!sk.isNullOrBlank()) dec = decryptMap(map.token, sk)
            
            if (dec == null || dec.pieces.isEmpty()) {
                try {
                    val mtd = if (map.method.isNotBlank()) map.method else "browser_session"
                    val bodyStr = """{"token":"${map.token}","method":"$mtd"}"""
                    val body = bodyStr.toRequestBody("application/json".toMediaType())
                    val proxyReq = POST("$baseUrl/chapter-map-proxy-plan/$chapterId", apiHeaders, body)
                    val proxyResp = client.newCall(proxyReq).execute()
                    if (proxyResp.isSuccessful) {
                        dec = proxyResp.parseAs<ProxyPlanResponse>().data?.map
                    }
                } catch (e: Exception) {}
            }
            return dec
        }
        return null
    }

    private fun processMap(
        map: DeferredPageMap, cdnBase: String, pages: MutableList<Page>, seenUrls: MutableSet<String>
    ) {
        if (map.pieces.isEmpty()) return

        val absolutePieces = map.pieces.map { it.toAbsoluteUrl(cdnBase, map.token) }

        if (map.dim.size >= 2 && map.pieces.size > 1 && map.order.isNotEmpty()) {
            val instructionPieces = mutableListOf<String>()
            for (targetIdx in map.pieces.indices) {
                val srcIdx = if (map.order.size == map.pieces.size) map.order[targetIdx] else targetIdx
                instructionPieces.add(absolutePieces[srcIdx])
            }
            
            val instruction = StitchInstruction(map.dim, instructionPieces)
            val jsonStr = json.encodeToString(instruction)
            val base64Str = Base64.encodeToString(jsonStr.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
            
            val finalUrl = "procomic-map://$base64Str"
            if (seenUrls.add(finalUrl)) {
                pages.add(Page(pages.size, imageUrl = finalUrl))
            }
        } else {
            for (targetIdx in absolutePieces.indices) {
                val srcIdx = if (map.order.size == absolutePieces.size) map.order[targetIdx] else targetIdx
                val pieceUrl = absolutePieces[srcIdx]
                if (seenUrls.add(pieceUrl)) pages.add(Page(pages.size, imageUrl = pieceUrl))
            }
        }
    }

    private fun fetchDeferredPages(
        chapterId: String, jwtToken: String, apiHeaders: Headers, seenUrls: MutableSet<String>, cdnBase: String, getSessionKey: () -> String?,
    ): List<Page> {
        val pages = mutableListOf<Page>()
        try {
            val first = client.newCall(GET("$baseUrl/chapter-deferred-media/$chapterId?token=$jwtToken&split=0", apiHeaders)).execute()
            if (!first.isSuccessful) return pages

            val firstData = first.parseAs<ChapterDeferredResponse>()
            if (!firstData.success || firstData.data == null) return pages

            val splits = mutableListOf(firstData.data)
            for (s in 1..firstData.data.splitIndex) {
                try {
                    val r = client.newCall(GET("$baseUrl/chapter-deferred-media/$chapterId?token=$jwtToken&split=$s", apiHeaders)).execute()
                    if (!r.isSuccessful) break
                    val d = r.parseAs<ChapterDeferredResponse>()
                    if (d.success && d.data != null) splits.add(d.data)
                } catch (e: Exception) { break }
            }

            for (split in splits) {
                val decryptedMaps = mutableListOf<DeferredPageMap>()
                val absolutePieceUrls = mutableSetOf<String>()

                split.maps.forEach { map ->
                    val resolved = resolveMap(map, chapterId, apiHeaders, getSessionKey)
                    if (resolved != null && resolved.pieces.isNotEmpty()) {
                        decryptedMaps.add(resolved)
                        absolutePieceUrls.addAll(resolved.pieces.map { it.toAbsoluteUrl(cdnBase, resolved.token) })
                    }
                }

                split.images.forEach { url ->
                    val fullUrl = url.toAbsoluteUrl(cdnBase, "")
                    if (fullUrl !in absolutePieceUrls && seenUrls.add(fullUrl)) {
                        pages.add(Page(pages.size, imageUrl = fullUrl))
                    }
                }

                decryptedMaps.forEach { map -> processMap(map, cdnBase, pages, seenUrls) }
            }
        } catch (e: Exception) {}
        return pages
    }

    override fun imageUrlParse(response: Response) = ""

    override fun imageRequest(page: Page): Request {
        return GET(page.imageUrl!!, headersBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()
        )
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
            val parameterSpec = GCMParameterSpec(128, ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec)

            val decryptedBytes = cipher.doFinal(cipherTextBytes + tagBytes)
            json.decodeFromString<DeferredPageMap>(String(decryptedBytes))
        } catch (e: Exception) { null }
    }

    private inline fun <reified T> Response.parseAs(): T =
        json.decodeFromStream(body.byteStream())
}

@Serializable data class StitchInstruction(val dim: List<Int>, val pieces: List<String>)
@Serializable data class SessionKeyResponse(val success: Boolean = false, val data: SessionKeyData? = null)
@Serializable data class SessionKeyData(val key: String = "")
@Serializable data class EncryptedToken(val v: Int = 3, val m: String = "", val cid: Int = 0, val iv: String = "", val tag: String = "", val data: String = "")
@Serializable data class LatestUpdatesResponse(val success: Boolean = false, val data: List<SeriesDto> = emptyList())
@Serializable data class SeriesDto(@SerialName("mangaId") val id: Int = 0, @SerialName("mangaSlug") val slug: String = "", @SerialName("mangaTitle") val title: String = "", val coverImage: String? = null, val type: String = "manga", val coverImageApp: CoverImageApp? = null) { fun toSManga() = SManga.create().apply { url = "$type/$id/$slug"; title = this@SeriesDto.title; thumbnail_url = coverImageApp?.card?.mobile ?: coverImageApp?.desktop ?: coverImage } }
@Serializable data class CoverImageApp(val desktop: String? = null, val card: CardImages? = null)
@Serializable data class CardImages(val mobile: String? = null, val desktop: String? = null)
@Serializable data class SeriesDetailResponse(val id: Int = 0, val title: String? = null, val slug: String? = null, val coverImage: String? = null, val coverImageApp: CoverImageApp? = null, val author: String? = null, val artist: String? = null, val description: String? = null, val synopsis: String? = null, val status: String? = null)
@Serializable data class ChaptersResponse(val data: List<ChapterDto> = emptyList(), val total: Int = 0)
@Serializable data class ChapterDto(val id: Int = 0, @SerialName("chapter_number") val chapterNumber: String = "0", val title: String? = null, @SerialName("published_at") val publishedAt: String? = null, val lockedByCoins: Boolean? = null, @SerialName("cdn_path") val cdnPath: String? = null, val metadata: ChapterMetadataDto? = null)
@Serializable data class ChapterMetadataDto(val images: List<String> = emptyList(), val maps: List<DeferredPageMap> = emptyList())
@Serializable data class ChapterDeferredResponse(val success: Boolean = false, val data: ChapterDeferredData? = null)
@Serializable data class ChapterDeferredData(val chapterId: Int = 0, val splitIndex: Int = 0, val images: List<String> = emptyList(), val maps: List<DeferredPageMap> = emptyList())
@Serializable data class DeferredPageMap(val dim: List<Int> = emptyList(), val mode: String = "", val pieces: List<String> = emptyList(), val order: List<Int> = emptyList(), val token: String = "", val method: String = "")
@Serializable data class ProxyPlanResponse(val success: Boolean = false, val data: ProxyPlanData? = null)
@Serializable data class ProxyPlanData(val map: DeferredPageMap? = null)
