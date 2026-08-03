package eu.kanade.tachiyomi.extension.ar.procomic

import android.app.Application
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Base64
import android.util.LruCache
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.jsonArray
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

class ProComic : HttpSource(), ConfigurableSource {

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

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val SCRAMBLED_SCHEME = "https://procomic.pro/__scrambled_asset__/"
        private const val MAX_SAFE_HEIGHT = 6000
        private const val TYPE_PREF = "TypePref"
        private const val COOKIE_PREF = "SessionCookie"
        private val TYPE_PREF_ENTRIES = arrayOf("الكل", "مانجا", "مانهوا", "مانهوا صينية (Manhua)", "كوميكس")
        private val TYPE_PREF_ENTRY_VALUES = arrayOf("all", "manga", "manhwa", "manhua", "comic")
        private const val TYPE_PREF_DEFAULT = "all"

        private val pieceCache = object : LruCache<String, ByteArray>(50 * 1024 * 1024) {
            override fun sizeOf(key: String, value: ByteArray): Int = value.size
        }
        private val mapLocks = ConcurrentHashMap<String, Any>()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = TYPE_PREF
            title = "تصفية نوع السلاسل"
            summary = "حدد النوع الذي تريد عرضه في أقسام التطبيق (الأحدث/الشائع)"
            entries = TYPE_PREF_ENTRIES
            entryValues = TYPE_PREF_ENTRY_VALUES
            setDefaultValue(TYPE_PREF_DEFAULT)
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = COOKIE_PREF
            title = "🍪 Cookie الجلسة (مطلوب للمانهوا)"
            summary = "ضع الكوكي الخاص بك هنا لفتح الفصول المغلقة"
            dialogTitle = "قيمة الـ Cookie كاملةً"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    private fun getSelectedType(): String = preferences.getString(TYPE_PREF, TYPE_PREF_DEFAULT) ?: TYPE_PREF_DEFAULT
    private fun getSessionCookie(): String = preferences.getString(COOKIE_PREF, "") ?: ""

    private val innerClient: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 100
            maxRequestsPerHost = 40
        })
        .build()

    override val client: OkHttpClient = innerClient.newBuilder()
        .rateLimit(2)
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
            if (response.isSuccessful && request.method == "GET" && url.contains("procomic")) {
                val body = response.body ?: return@addInterceptor response
                val bytes = body.bytes()
                val decoded = extractBase64Image(bytes)
                if (decoded != null) {
                    val mime = extractMimeType(bytes)
                    return@addInterceptor response.newBuilder()
                        .body(decoded.toResponseBody(mime.toMediaType()))
                        .build()
                }
                return@addInterceptor response.newBuilder()
                    .body(bytes.toResponseBody(body.contentType()))
                    .build()
            }
            response
        }.build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept-Language", "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7")
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

    private fun pieceRequestHeaders(): Headers {
        return headersBuilder()
            .set("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .set("Sec-Fetch-Dest", "image")
            .set("Sec-Fetch-Mode", "no-cors")
            .set("Sec-Fetch-Site", "same-site")
            .apply {
                val cookie = getSessionCookie()
                if (cookie.isNotBlank()) set("Cookie", cookie)
            }.build()
    }

    private fun extractBase64Image(bytes: ByteArray): ByteArray? {
        if (bytes.size < 10) return null
        val prefixLen = minOf(150, bytes.size)
        val prefix = String(bytes, 0, prefixLen, Charsets.US_ASCII)
        val marker = prefix.indexOf("base64,")
        if (marker == -1) return null
        val start = marker + 7
        var end = bytes.size
        while (end > start) {
            val c = bytes[end - 1].toInt().toChar()
            if (c == '"' || c == '\'' || c == '\n' || c == '\r' || c == ' ') end-- else break
        }
        return try { Base64.decode(bytes, start, end - start, Base64.DEFAULT) } catch (_: Exception) { null }
    }

    private fun extractMimeType(bytes: ByteArray): String {
        val prefix = String(bytes, 0, minOf(100, bytes.size), Charsets.US_ASCII)
        return prefix.substringAfter("data:", "image/jpeg").substringBefore(";", "image/jpeg").trim().ifBlank { "image/jpeg" }
    }

    private fun isRawImageBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return true
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) return true
        if (bytes.size > 8 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp") return true
        if (bytes.size > 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") return true
        return false
    }

    private fun downloadAndCachePiece(pieceUrl: String, token: String, cacheKey: String): Boolean {
        val urlsToTry = buildList {
            if (token.isNotBlank() && !pieceUrl.contains("/i/eyJ")) {
                add(if (pieceUrl.contains("?")) "$pieceUrl&token=$token" else "$pieceUrl?token=$token")
            }
            add(pieceUrl)
            if (!pieceUrl.contains("/i/") && pieceUrl.contains(".procomic.pro/")) {
                add(pieceUrl.replace(".procomic.pro/", ".procomic.pro/i/"))
            }
        }
        for (url in urlsToTry) {
            try {
                val req = Request.Builder().url(url).headers(pieceRequestHeaders()).build()
                val rawBytes = innerClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.bytes()
                } ?: continue

                val imageBytes = extractBase64Image(rawBytes) ?: if (isRawImageBytes(rawBytes)) rawBytes else null ?: continue
                pieceCache.put(cacheKey, imageBytes)
                return true
            } catch (_: Exception) { continue }
        }
        return false
    }

    override fun imageRequest(page: Page): Request {
        val referer = if (page.url.startsWith("http")) page.url else "$baseUrl/"
        val request = super.imageRequest(page)
            .newBuilder()
            .headers(pieceRequestHeaders().newBuilder().set("Referer", referer).build())
            .build()
        val fragment = page.url.substringAfter("#", "")
        if (fragment.isNotBlank()) {
            try {
                val mapJson = String(Base64.decode(fragment, Base64.URL_SAFE or Base64.NO_WRAP))
                val pageMap = json.decodeFromString<ScrambledMap>(mapJson)
                return request.newBuilder().tag(ScrambledMap::class.java, pageMap).build()
            } catch (_: Exception) {}
        }
        return request
    }

    override fun imageUrlParse(response: Response): String = ""

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/api/public/series/search?status=approved&limit=18&page=$page&sort=latest", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/api/public/content/latest-updates?limit=30&category=comics&page=$page", headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/android/feed/all-series?page=$page" + if (query.isNotBlank()) "&q=$query" else ""
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body?.string() ?: return MangasPage(emptyList(), false)
        val element = json.parseToJsonElement(body)
        
        val dataArray = when (element) {
            is JsonObject -> {
                element["data"]?.jsonArray
                    ?: element["items"]?.jsonArray
                    ?: element["library"]?.jsonArray
                    ?: JsonArray(emptyList())
            }
            is JsonArray -> element
            else -> JsonArray(emptyList())
        }

        val selectedType = getSelectedType()
        
        val mangas = dataArray.mapNotNull { 
            try {
                val dto = json.decodeFromJsonElement<SeriesDto>(it)
                if (dto.type == "novel") return@mapNotNull null
                if (selectedType != "all" && !dto.type.equals(selectedType, ignoreCase = true)) return@mapNotNull null
                dto.toSManga()
            } catch (e: Exception) {
                null
            }
        }

        return MangasPage(mangas, mangas.size >= 15)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)
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
        } catch (_: Exception) { SManga.create() }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val p = manga.url.split("/")
        val slug = p.getOrElse(2) { "" }
        return GET("$baseUrl/api/public/${p[0]}/${p[1]}/chapters?page=1&limit=600&order=desc&language=AR&_slug=$slug", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val parts = response.request.url.pathSegments
        val idx = parts.indexOf("public")
        val seriesType = parts.getOrElse(idx + 1) { "manga" }
        val seriesId = parts.getOrElse(idx + 2) { "0" }
        val seriesSlug = response.request.url.queryParameter("_slug") ?: ""
        return response.parseAs<ChaptersResponse>().data
            .filter { ch ->
                val chLang = ch.language ?: ch.lang ?: ch.locale
                chLang == null || chLang.equals("ar", ignoreCase = true)
            }
            .map { ch ->
            SChapter.create().apply {
                url = "$seriesType/$seriesId/$seriesSlug/${ch.id}/${ch.chapterNumber}"
                name = "الفصل ${ch.chapterNumber}" + if (!ch.title.isNullOrBlank()) " - ${ch.title}" else ""
                date_upload = runCatching { dateFormat.parse(ch.publishedAt ?: "")?.time }.getOrNull() ?: 0L
                chapter_number = ch.chapterNumber.toFloatOrNull() ?: 0f
                scanlator = if (ch.lockedByCoins == true) "🔒 مدفوع" else null
            }
        }
    }

    // الموقع أصبح (يوليو 2026) يضمّن بيانات صفحات الفصل مباشرة داخل صفحة القارئ (Next.js RSC payload)
    // بدل توفيرها عبر /api/public/.../chapters. لذلك نجلب صفحة القارئ نفسها كـ HTML ونستخرج البيانات منها.
    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.split("/")
        val seriesType = parts.getOrElse(0) { "manga" }
        val seriesId = parts.getOrElse(1) { "0" }
        val seriesSlug = parts.getOrElse(2) { "" }
        val chapterId = parts.getOrElse(3) { "0" }
        val chapterSlug = parts.getOrElse(4) { chapterId }
        val url = "$baseUrl/ar/series/$seriesType/$seriesId/$seriesSlug/$chapterId/$chapterSlug"
        val pageHeaders = headers.newBuilder()
            .apply {
                val cookie = getSessionCookie()
                if (cookie.isNotBlank()) set("Cookie", cookie)
            }.build()
        return GET(url, pageHeaders)
    }

    override fun pageListParse(response: Response): List<Page> {
        val segments = response.request.url.pathSegments
        val seriesIdx = segments.indexOf("series")
        val chapterId = segments.getOrNull(seriesIdx + 4) ?: return emptyList()

        val rawHtml = response.body?.string() ?: return emptyList()
        // يفكّ التهريب (\" -> ") الموجود داخل self.__next_f.push([1,"...json..."]) ليسهل عمل regex عليه
        val html = rawHtml.replace("\\\"", "\"")

        val apiHeaders = headers.newBuilder()
            .set("Accept", "application/json")
            .apply {
                val cookie = getSessionCookie()
                if (cookie.isNotBlank()) set("Cookie", cookie)
            }.build()

        val pages = mutableListOf<Page>()
        val seenUrls = mutableSetOf<String>()

        val cdnPath = Regex(""""cdnPath"\s*:\s*"([a-zA-Z0-9_]+)"""").find(html)?.groupValues?.get(1) ?: "cdn1"
        val cdnBase = "https://$cdnPath.procomic.pro"
        val chapterPageUrl = response.request.url.toString()

        // الصور المباشرة (غير المقطّعة): نستخدم روابط appImages[].desktop الفعلية
        // (وليست images[] الخام من cdn3 والتي لا تعمل مباشرة وتعطي 403)
        Regex(""""desktop"\s*:\s*"(https?://[^"]+)"""").findAll(html).forEach { m ->
            val url = m.groupValues[1]
            if (seenUrls.add(url)) pages.add(Page(pages.size, url = chapterPageUrl, imageUrl = url))
        }

        var cachedSessionKey: String? = null
        var sessionKeyAttempted = false
        val getSessionKey: () -> String? = {
            if (!sessionKeyAttempted) {
                sessionKeyAttempted = true
                try {
                    val req = innerClient.newCall(GET("$baseUrl/chapter-map-session-key/$chapterId?legacy=1", apiHeaders)).execute()
                    if (req.isSuccessful) cachedSessionKey = req.parseAs<SessionKeyResponse>().data?.key
                } catch (_: Exception) {}
            }
            cachedSessionKey
        }

        // بقية الصفحات (المحمية/المقطّعة) تُجلب عبر توكن deferredMedia المضمّن في نفس الصفحة
        val deferredToken = Regex(""""deferredMedia"\s*:\s*\{\s*"token"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
        if (deferredToken != null && deferredToken.isJwt()) {
            try { pages.addAll(fetchDeferredPages(chapterId, deferredToken, apiHeaders, seenUrls, cdnBase, cdnPath, getSessionKey)) } catch (_: Exception) {}
        }

        if (pages.isEmpty()) {
            val isLocked = html.contains("\"lockedByCoins\":true") || html.contains("\"isLocked\":true") ||
                html.contains("requireTurnstile\":true") || html.contains("purchase") || html.contains("unlock")
            if (isLocked) {
                throw Exception("هذا الفصل مدفوع 🔒 — تأكد من إدخال كوكي حساب مسجّل الدخول ومالك للفصل في إعدادات الإضافة")
            }
        }

        return pages
    }

    private fun String.isJwt(): Boolean = startsWith("eyJhbGci") && count { it == '.' } == 2

    private fun jwtSplitValue(jwtToken: String): Int {
        return try {
            val payload = jwtToken.split(".").getOrNull(1) ?: return 20 
            val padLen = (4 - payload.length % 4) % 4
            val padded = payload + "=".repeat(padLen)
            json.decodeFromString<JwtPayload>(String(Base64.decode(padded, Base64.URL_SAFE))).split
        } catch (_: Exception) { 20 }
    }

    private fun fetchDeferredPages(chapterId: String, jwtToken: String, apiHeaders: Headers, seenUrls: MutableSet<String>, cdnBase: String, cdnPath: String, getSessionKey: () -> String?): List<Page> {
        val pages = mutableListOf<Page>()
        val splitResponses = mutableListOf<ChapterDeferredData>()
        // الموقع يتجاهل قيمة split في الرابط ويعتمد فقط على القيمة المضمّنة داخل التوكن نفسه،
        // لذا استدعاء واحد فقط كافٍ (تكرار الاستدعاء بقيم split مختلفة يرجع نفس المحتوى مكرراً).
        val split = jwtSplitValue(jwtToken)
        try {
            val resp = innerClient.newCall(GET("$baseUrl/chapter-deferred-media/$chapterId?token=$jwtToken&split=$split", apiHeaders)).execute()
            if (resp.isSuccessful) {
                val parsed = resp.parseAs<ChapterDeferredResponse>()
                if (parsed.success && parsed.data != null) splitResponses.add(parsed.data)
            }
        } catch (_: Exception) {}

        for (splitData in splitResponses) {
            val absolutePieceUrls = mutableSetOf<String>()
            
            splitData.maps.forEachIndexed { index, map ->
                val updatedMap = map.copy(cdnPath = map.cdnPath ?: cdnPath, pageIndex = map.pageIndex ?: index)
                
                if (updatedMap.token.isNotBlank() && updatedMap.pieces.isNullOrEmpty() && updatedMap.token.isJwt()) return@forEachIndexed
                
                val originalMapBase64 = Base64.encodeToString(json.encodeToString(updatedMap).toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
                val resolved = resolveMap(updatedMap, chapterId, apiHeaders, getSessionKey)
                if (resolved != null && !resolved.pieces.isNullOrEmpty()) {
                    val absPieces = resolved.pieces.map { it.toAbsoluteUrl(cdnBase) }
                    absolutePieceUrls.addAll(absPieces)
                    processMap(resolved.dim ?: emptyList(), resolved.mode ?: "", absPieces, resolved.order ?: emptyList(), resolved.token, pages, seenUrls, chapterId, originalMapBase64)
                }
            }
            splitData.images.forEach { url ->
                val fullUrl = url.toAbsoluteUrl(cdnBase)
                if (fullUrl !in absolutePieceUrls && seenUrls.add(fullUrl)) pages.add(Page(pages.size, imageUrl = fullUrl))
            }
        }
        return pages
    }

    private fun resolveMap(map: DeferredPageMap, chapterId: String, apiHeaders: Headers, getSessionKey: () -> String?): DeferredPageMap? {
        if (!map.pieces.isNullOrEmpty()) return map
        if (map.token.isBlank()) return null

        var dec = getSessionKey()?.let { decryptMap(map.token, it) }
        if (dec != null && !dec.pieces.isNullOrEmpty()) return dec

        try {
            val reqPayload = ProxyPlanRequest(
                cdnPath = map.cdnPath ?: "cdn1",
                method = map.method ?: "browser_session",
                pageIndex = map.pageIndex ?: 0,
                token = map.token
            )
            val body = json.encodeToString(reqPayload).toRequestBody("application/json".toMediaType())
            
            val proxyResp = innerClient.newCall(
                POST("$baseUrl/chapter-map-proxy-plan/$chapterId", apiHeaders.newBuilder().set("Origin", baseUrl).set("Referer", "$baseUrl/").build(), body)
            ).execute()
            if (proxyResp.isSuccessful) dec = proxyResp.parseAs<ProxyPlanResponse>().data?.map
        } catch (_: Exception) {}

        return dec
    }

    private fun processMap(dim: List<Int>, mode: String, pieces: List<String>, order: List<Int>, signedToken: String, pages: MutableList<Page>, seenUrls: MutableSet<String>, chapterId: String, originalMapBase64: String) {
        val mapKey = pieces.joinToString(",")
        if (pieces.isEmpty() || !seenUrls.add(mapKey)) return
        
        val estimatedTotalH = dim.getOrNull(1)?.takeIf { it > 0 } ?: 10000
        val parts = if (estimatedTotalH > MAX_SAFE_HEIGHT) ceil(estimatedTotalH.toDouble() / MAX_SAFE_HEIGHT).toInt() else 1
        for (p in 0 until parts) {
            val mapData = ScrambledMap(dim, mode, pieces, order, signedToken, p, parts, chapterId, originalMapBase64)
            val encoded = Base64.encodeToString(json.encodeToString(mapData).toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
            val shortUrl = "$SCRAMBLED_SCHEME${pages.size}_part_$p.jpg#$encoded"
            pages.add(Page(pages.size, url = shortUrl, imageUrl = shortUrl))
        }
    }

    private fun reconstructPage(map: ScrambledMap): ByteArray? {
        if (map.pieces.isEmpty()) return null

        val mapKey = map.pieces.first()
        val mapLock = mapLocks.getOrPut(mapKey) { Any() }

        synchronized(mapLock) {
            val needsDownload = map.pieces.any { pieceCache.get(it) == null }
            if (!needsDownload) return@synchronized

            var pieces = map.pieces
            var token = map.signedToken

            val success = downloadAllPieces(pieces, token, map.pieces)

            if (!success && map.chapterId.isNotEmpty() && map.originalMapBase64.isNotEmpty()) {
                runCatching {
                    val mapJson = String(Base64.decode(map.originalMapBase64, Base64.URL_SAFE))
                    val decodedMap = json.decodeFromString<DeferredPageMap>(mapJson)
                    
                    val reqPayload = ProxyPlanRequest(
                        cdnPath = decodedMap.cdnPath ?: "cdn1",
                        method = decodedMap.method ?: "browser_session",
                        pageIndex = decodedMap.pageIndex ?: 0,
                        token = decodedMap.token
                    )
                    
                    val proxyHeaders = headersBuilder().set("Origin", baseUrl).set("Referer", "$baseUrl/").set("Content-Type", "application/json")
                        .apply {
                            val cookie = getSessionCookie()
                            if (cookie.isNotBlank()) set("Cookie", cookie)
                        }.build()
                        
                    innerClient.newCall(POST("$baseUrl/chapter-map-proxy-plan/${map.chapterId}", proxyHeaders, json.encodeToString(reqPayload).toRequestBody("application/json".toMediaType()))).execute().use { resp ->
                        if (resp.isSuccessful) {
                            json.decodeFromStream<ProxyPlanResponse>(resp.body!!.byteStream()).data?.map?.let { newMap ->
                                pieces = newMap.pieces ?: emptyList()
                                token = newMap.token
                            }
                        }
                    }
                }
                downloadAllPieces(pieces, token, map.pieces)
            }
        }

        val (cols, rows) = parseMode(map.mode, map.pieces.size)
        val bitmaps = arrayOfNulls<Bitmap>(map.pieces.size)

        // ✅ الإصلاح الذكي والمرن للترتيب (يمنع التكرار ويتعامل مع الأخطاء بدون تجاهل الترتيب)
        for (targetIdx in map.pieces.indices) {
            val srcIdx = if (map.order.isNotEmpty() && targetIdx < map.order.size) {
                map.order[targetIdx]
            } else {
                targetIdx
            }
            
            // التأكد من أن الرقم المطلوب موجود فعلياً داخل القطع لمنع الانهيار
            val safeSrcIdx = if (srcIdx in map.pieces.indices) srcIdx else targetIdx
            
            val cacheKey = map.pieces.getOrNull(safeSrcIdx) ?: continue
            val bytes = pieceCache.get(cacheKey) ?: continue
            bitmaps[targetIdx] = decodeAvif(bytes)
        }

        return assembleBitmaps(bitmaps, map, cols, rows)
    }

    private fun downloadAllPieces(pieces: List<String>, token: String, cacheKeys: List<String>): Boolean {
        return runBlocking(Dispatchers.IO) {
            pieces.indices.map { idx ->
                async {
                    val cacheKey = cacheKeys.getOrNull(idx) ?: return@async false
                    if (pieceCache.get(cacheKey) != null) return@async true
                    val pieceUrl = pieces.getOrNull(idx) ?: return@async false
                    downloadAndCachePiece(pieceUrl, token, cacheKey)
                }
            }.awaitAll().all { it }
        }
    }

    private fun assembleBitmaps(bitmaps: Array<Bitmap?>, map: ScrambledMap, cols: Int, rows: Int): ByteArray? {
        return try {
            val valid = bitmaps.filterNotNull()
            if (valid.isEmpty()) return null
            val totalW: Int
            val totalH: Int
            when {
                cols == 1 -> {
                    totalW = map.dim.getOrNull(0)?.takeIf { it > 0 } ?: valid.maxOf { it.width }
                    totalH = map.dim.getOrNull(1)?.takeIf { it > 0 } ?: valid.sumOf { it.height }
                }
                rows == 1 -> {
                    totalW = map.dim.getOrNull(0)?.takeIf { it > 0 } ?: valid.sumOf { it.width }
                    totalH = map.dim.getOrNull(1)?.takeIf { it > 0 } ?: valid.maxOf { it.height }
                }
                else -> {
                    totalW = map.dim.getOrNull(0)?.takeIf { it > 0 } ?: (valid.first().width * cols)
                    totalH = map.dim.getOrNull(1)?.takeIf { it > 0 } ?: (valid.first().height * rows)
                }
            }

            val totalParts = map.totalParts ?: 1
            val splitPart = map.splitPart ?: 0
            val partH = totalH / totalParts
            val actualPartH = if (splitPart == totalParts - 1) totalH - partH * splitPart else partH

            if (totalW <= 0 || actualPartH <= 0) return null

            val result = try { Bitmap.createBitmap(totalW, actualPartH, Bitmap.Config.ARGB_8888) } catch (_: OutOfMemoryError) { Bitmap.createBitmap(totalW, actualPartH, Bitmap.Config.RGB_565) }
            val canvas = Canvas(result)
            canvas.translate(0f, -(splitPart * partH).toFloat())

            // ✅ إصلاح الرسم الديناميكي للشبكات (Grid) ليمنع التكرار لو اختلفت أحجام القطع
            when {
                cols == 1 -> { var y = 0f; bitmaps.forEach { bmp -> bmp?.let { canvas.drawBitmap(it, 0f, y, null); y += it.height; it.recycle() } } }
                rows == 1 -> { var x = 0f; bitmaps.forEach { bmp -> bmp?.let { canvas.drawBitmap(it, x, 0f, null); x += it.width; it.recycle() } } }
                else -> {
                    var currentY = 0f
                    var currentX = 0f
                    var rowMaxH = 0
                    bitmaps.forEachIndexed { i, bmp -> 
                        if (bmp != null) {
                            canvas.drawBitmap(bmp, currentX, currentY, null)
                            currentX += bmp.width
                            rowMaxH = maxOf(rowMaxH, bmp.height)
                            bmp.recycle()
                        }
                        if ((i + 1) % cols == 0) {
                            currentX = 0f
                            currentY += rowMaxH
                            rowMaxH = 0
                        }
                    }
                }
            }

            val out = ByteArrayOutputStream()
            result.compress(Bitmap.CompressFormat.JPEG, 85, out)
            result.recycle()
            out.toByteArray()
        } catch (_: Exception) { null }
    }

    private fun decryptMap(tokenStr: String, sessionKeyBase64: String): DeferredPageMap? {
        return try {
            val tokenData = json.decodeFromString<EncryptedToken>(String(Base64.decode(tokenStr, Base64.URL_SAFE or Base64.DEFAULT)))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(Base64.decode(sessionKeyBase64, Base64.URL_SAFE), "AES"), GCMParameterSpec(128, Base64.decode(tokenData.iv, Base64.URL_SAFE)))
            val decrypted = cipher.doFinal(Base64.decode(tokenData.data, Base64.URL_SAFE) + Base64.decode(tokenData.tag, Base64.URL_SAFE))
            json.decodeFromString<DeferredPageMap>(String(decrypted))
        } catch (_: Exception) { null }
    }

    private fun decodeAvif(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val decoder = ImageDecoder.newInstance(bytes.inputStream())
        return if (decoder != null) {
            try { decoder.decode() } catch (_: Exception) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } finally { decoder.recycle() }
        } else { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    }

    // إرجاع دالة parseMode كما كانت في النسخة الناجحة لديك
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

    private fun String.toAbsoluteUrl(cdnBase: String): String = when {
        startsWith("http") -> this
        startsWith("eyJ2IjoxLCJpdiI6I") -> "$cdnBase/i/$this"
        startsWith("/") -> "$cdnBase$this"
        else -> "$cdnBase/$this"
    }

    private inline fun <reified T> Response.parseAs(): T = json.decodeFromStream(body!!.byteStream())
}

@Serializable data class JwtPayload(val split: Int = 20, val cid: Int = 0, val p: String = "")
@Serializable data class SessionKeyResponse(val success: Boolean = false, val data: SessionKeyData? = null)
@Serializable data class SessionKeyData(val key: String = "")
@Serializable data class EncryptedToken(val v: Int = 3, val m: String = "", val cid: Int = 0, val iv: String = "", val tag: String = "", val data: String = "")
@Serializable data class ScrambledMap(val dim: List<Int> = emptyList(), val mode: String = "", val pieces: List<String> = emptyList(), val order: List<Int> = emptyList(), val signedToken: String = "", val splitPart: Int? = null, val totalParts: Int? = null, val chapterId: String = "", val originalMapBase64: String = "")
@Serializable data class LatestUpdatesResponse(val success: Boolean = false, val data: List<SeriesDto> = emptyList())
@Serializable data class SeriesDto(
    val id: Int? = null,
    @SerialName("mangaId") val mangaId: Int? = null,
    val slug: String? = null,
    @SerialName("mangaSlug") val mangaSlug: String? = null,
    val title: String? = null,
    @SerialName("mangaTitle") val mangaTitle: String? = null,
    val coverImage: String? = null, 
    val type: String = "manga", 
    val coverImageApp: CoverImageApp? = null
) {
    fun toSManga() = SManga.create().apply { 
        val finalId = id ?: mangaId ?: 0
        val finalSlug = slug ?: mangaSlug ?: ""
        url = "$type/$finalId/$finalSlug"
        title = this@SeriesDto.title ?: mangaTitle ?: ""
        thumbnail_url = coverImageApp?.card?.mobile ?: coverImageApp?.desktop ?: coverImage 
    }
}
@Serializable data class CoverImageApp(val desktop: String? = null, val card: CardImages? = null)
@Serializable data class CardImages(val mobile: String? = null, val desktop: String? = null)
@Serializable data class SeriesDetailResponse(val id: Int = 0, val title: String? = null, val slug: String? = null, val coverImage: String? = null, val coverImageApp: CoverImageApp? = null, val author: String? = null, val artist: String? = null, val description: String? = null, val synopsis: String? = null, val status: String? = null)
@Serializable data class ChaptersResponse(val data: List<ChapterDto> = emptyList(), val total: Int = 0)
@Serializable data class ChapterDto(val id: Int = 0, @SerialName("chapter_number") val chapterNumber: String = "0", val title: String? = null, @SerialName("published_at") val publishedAt: String? = null, val lockedByCoins: Boolean? = null, @SerialName("cdn_path") val cdnPath: String? = null, val metadata: ChapterMetadataDto? = null, val language: String? = null, val lang: String? = null, val locale: String? = null)
@Serializable data class ChapterMetadataDto(val images: List<String> = emptyList(), val maps: List<DeferredPageMap> = emptyList())
@Serializable data class ChapterDeferredResponse(val success: Boolean = false, val data: ChapterDeferredData? = null)
@Serializable data class ChapterDeferredData(val chapterId: Int = 0, val splitIndex: Int = 0, val images: List<String> = emptyList(), val maps: List<DeferredPageMap> = emptyList())
@Serializable data class DeferredPageMap(val dim: List<Int>? = emptyList(), val mode: String? = "", val pieces: List<String>? = emptyList(), val order: List<Int>? = emptyList(), val token: String = "", val method: String? = "", val cdnPath: String? = null, val pageIndex: Int? = null)
@Serializable data class ProxyPlanRequest(val cdnPath: String, val method: String, val pageIndex: Int, val token: String)
@Serializable data class ProxyPlanResponse(val success: Boolean = false, val data: ProxyPlanData? = null)
@Serializable data class ProxyPlanData(val map: DeferredPageMap? = null)
@Serializable data class SingleChapterResponse(val success: Boolean = false, val data: ChapterDto? = null)
