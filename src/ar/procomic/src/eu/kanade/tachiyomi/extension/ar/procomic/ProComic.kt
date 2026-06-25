package eu.kanade.tachiyomi.extension.ar.procomic

import android.app.Application
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Base64
import android.util.LruCache
import android.widget.Toast
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

        // ─── Preferences Keys ───────────────────────────────────────────────
        private const val TYPE_PREF = "TypePref"
        private const val COOKIE_PREF = "SessionCookie"

        private val TYPE_PREF_ENTRIES = arrayOf(
            "الكل", "مانجا", "مانهوا", "مانهوا صينية (Manhua)", "كوميكس",
        )
        private val TYPE_PREF_ENTRY_VALUES = arrayOf(
            "all", "manga", "manhwa", "manhua", "comic",
        )
        private const val TYPE_PREF_DEFAULT = "all"

        // ─── Cache & Locks ───────────────────────────────────────────────────
        private val pieceCache = object : LruCache<String, ByteArray>(50 * 1024 * 1024) {
            override fun sizeOf(key: String, value: ByteArray): Int = value.size
        }
        private val mapLocks = ConcurrentHashMap<String, Any>()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Preferences
    // ════════════════════════════════════════════════════════════════════════

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // نوع السلسلة
        ListPreference(screen.context).apply {
            key = TYPE_PREF
            title = "تصفية نوع السلاسل"
            summary = "حدد النوع الذي تريد عرضه في أقسام التطبيق (الأحدث/الشائع)"
            entries = TYPE_PREF_ENTRIES
            entryValues = TYPE_PREF_ENTRY_VALUES
            setDefaultValue(TYPE_PREF_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                val index = findIndexOfValue(newValue as String)
                Toast.makeText(
                    screen.context,
                    "قم بتحديث الصفحة لتطبيق عرض: ${entries[index]}",
                    Toast.LENGTH_LONG,
                ).show()
                true
            }
        }.also(screen::addPreference)

        // ─── FIX ١: إعداد الـ Cookie ─────────────────────────────────────
        // هذا هو الحل الجذري لمشكلة "forbidden" في الـ manhwa/manhua
        // السيرفر يحتاج pc_viewer_id + connect.sid لمنح الصور
        EditTextPreference(screen.context).apply {
            key = COOKIE_PREF
            title = "🍪 Cookie الجلسة (مطلوب للمانهوا/المانهوا الصينية)"
            summary = """
                |إذا واجهت خطأ forbidden أو صور سوداء في المانهوا:
                |١. افتح المتصفح وادخل procomic.pro
                |٢. سجّل دخول بحسابك
                |٣. افتح أي فصل مانهوا → F12 → Network
                |٤. اضغط على أي صورة → انسخ قيمة "cookie" من Headers
                |٥. الصق القيمة هنا
            """.trimMargin()
            dialogTitle = "قيمة الـ Cookie"
            dialogMessage = "انسخ القيمة الكاملة من المتصفح"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    private fun getSelectedType(): String =
        preferences.getString(TYPE_PREF, TYPE_PREF_DEFAULT) ?: TYPE_PREF_DEFAULT

    // ─── FIX ١: جلب الـ Cookie المحفوظ ──────────────────────────────────
    private fun getSessionCookie(): String =
        preferences.getString(COOKIE_PREF, "") ?: ""

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private fun String.toAbsoluteUrl(cdnBase: String): String = when {
        startsWith("http") -> this
        startsWith("eyJ2IjoxLCJpdiI6I") -> "$cdnBase/i/$this"
        startsWith("/") -> "$cdnBase$this"
        else -> "$cdnBase/$this"
    }

    private fun String.isJwt(): Boolean =
        startsWith("eyJhbGci") && count { it == '.' } == 2

    // ─── FIX ٢: استخراج Base64 بشكل موثوق (يعالج data:image/avif;base64,)
    // المشكلة الأصلية: content-type = image/avif لكن body = "data:image/avif;base64,..."
    // الكود القديم كان يفشل لأنه يعتمد على content-type لا على محتوى الـ body
    private fun extractBase64FromBytes(bytes: ByteArray): ByteArray? {
        if (bytes.size < 10) return null

        val prefixLimit = minOf(150, bytes.size)
        val prefix = String(bytes, 0, prefixLimit, Charsets.US_ASCII)

        // ابحث عن base64, في أي مكان من الـ prefix
        val base64Marker = prefix.indexOf("base64,")
        if (base64Marker == -1) return null

        val dataStart = base64Marker + 7
        var dataEnd = bytes.size

        // تنظيف نهاية القيمة من أي أحرف غير Base64
        while (dataEnd > dataStart) {
            when (bytes[dataEnd - 1].toInt().toChar()) {
                '"', '\'', '\n', '\r', ' ', '`', ';' -> dataEnd--
                else -> break
            }
        }

        if (dataEnd <= dataStart) return null

        return try {
            Base64.decode(bytes, dataStart, dataEnd - dataStart, Base64.DEFAULT)
        } catch (_: Exception) {
            null
        }
    }

    // ─── FIX ٣: التحقق أن الـ bytes صورة حقيقية (JPEG/PNG/AVIF/WebP)
    private fun isValidImageBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        // JPEG magic bytes: FF D8 FF
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return true
        // PNG magic bytes: 89 50 4E 47
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) return true
        // AVIF/MP4: offset 4 = "ftyp"
        if (bytes.size > 8) {
            val ftyp = String(bytes, 4, 4, Charsets.US_ASCII)
            if (ftyp == "ftyp") return true
        }
        // WebP: RIFF....WEBP
        if (bytes.size > 12) {
            val riff = String(bytes, 0, 4, Charsets.US_ASCII)
            val webp = String(bytes, 8, 4, Charsets.US_ASCII)
            if (riff == "RIFF" && webp == "WEBP") return true
        }
        return false
    }

    // ════════════════════════════════════════════════════════════════════════
    // HTTP Clients
    // ════════════════════════════════════════════════════════════════════════

    // عميل سريع بدون RateLimit لتحميل القطع بالتوازي
    private val innerClient: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .dispatcher(
            okhttp3.Dispatcher().apply {
                maxRequests = 100
                maxRequestsPerHost = 40
            },
        )
        .build()

    override val client: OkHttpClient = innerClient.newBuilder()
        .rateLimit(2)
        .addInterceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()

            // ── معالجة الصور المُجمَّعة (Scrambled) ────────────────────────
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

            // ─── FIX ٢: كشف أشمل لـ Base64 ──────────────────────────────
            // القديم: يعتمد على url.contains("/i/") فقط → يفوّت كثير من الحالات
            // الجديد: يفحص أي رد من procomic يحتمل أن يكون data URL
            val mightBeDataUrl = response.isSuccessful &&
                request.method == "GET" &&
                url.contains("procomic")

            if (mightBeDataUrl) {
                val responseBody = response.body ?: return@addInterceptor response
                val bytes = responseBody.bytes()

                // ─── FIX ٢: استخدام extractBase64FromBytes الجديدة ────────
                val decoded = extractBase64FromBytes(bytes)
                if (decoded != null) {
                    // استخراج الـ mimeType من "data:image/avif;base64,"
                    val prefixStr = String(bytes, 0, minOf(100, bytes.size), Charsets.US_ASCII)
                    val mimeType = prefixStr
                        .substringAfter("data:", "image/jpeg")
                        .substringBefore(";", "image/jpeg")
                        .trim()
                        .ifBlank { "image/jpeg" }

                    return@addInterceptor response.newBuilder()
                        .body(decoded.toResponseBody(mimeType.toMediaType()))
                        .build()
                }

                // الـ body ليس data URL، أرجعه كما هو
                return@addInterceptor response.newBuilder()
                    .body(bytes.toResponseBody(responseBody.contentType()))
                    .build()
            }

            response
        }.build()

    // ════════════════════════════════════════════════════════════════════════
    // Headers
    // ════════════════════════════════════════════════════════════════════════

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept-Language", "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7")
        .add(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36",
        )

    // ─── FIX ١: headers مع Cookie للقطع الخاصة بالمانهوا ────────────────
    private fun pieceHeaders(): Headers {
        val cookie = getSessionCookie()
        return headersBuilder().apply {
            set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            set("Sec-Fetch-Dest", "image")
            set("Sec-Fetch-Mode", "no-cors")
            set("Sec-Fetch-Site", "same-site")
            if (cookie.isNotBlank()) set("Cookie", cookie)
        }.build()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Image Request & Page List
    // ════════════════════════════════════════════════════════════════════════

    override fun imageRequest(page: Page): Request {
        val request = super.imageRequest(page)
        val fragmentData = page.url.substringAfter("#", "")
        if (fragmentData.isNotBlank()) {
            try {
                val mapJson = String(Base64.decode(fragmentData, Base64.URL_SAFE or Base64.NO_WRAP))
                val pageMap = json.decodeFromString<ScrambledMap>(mapJson)
                return request.newBuilder()
                    .tag(ScrambledMap::class.java, pageMap)
                    .build()
            } catch (_: Exception) {}
        }
        return request
    }

    override fun imageUrlParse(response: Response): String = ""

    // ════════════════════════════════════════════════════════════════════════
    // Manga Browsing
    // ════════════════════════════════════════════════════════════════════════

    override fun popularMangaRequest(page: Int) =
        GET("$baseUrl/api/public/content/latest-updates?limit=30&category=comics&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<LatestUpdatesResponse>()
        val selectedType = getSelectedType()
        val mangas = data.data
            .filter { it.type != "novel" }
            .filter { selectedType == "all" || it.type.equals(selectedType, ignoreCase = true) }
            .map { it.toSManga() }
        return MangasPage(mangas, data.data.size >= 30)
    }

    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        GET(
            "$baseUrl/api/public/content/latest-updates?limit=30&category=comics&page=$page" +
                if (query.isNotBlank()) "&q=$query" else "",
            headers,
        )

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // ════════════════════════════════════════════════════════════════════════
    // Manga Details & Chapters
    // ════════════════════════════════════════════════════════════════════════

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
                url = "${parts.getOrElse(idx + 1) { "manga" }}/" +
                    "${parts.getOrElse(idx + 2) { "0" }}/${data.slug ?: ""}"
                title = data.title ?: ""
                thumbnail_url = data.coverImageApp?.card?.mobile
                    ?: data.coverImageApp?.desktop
                    ?: data.coverImage
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
        } catch (_: Exception) {
            SManga.create()
        }
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
                name = "الفصل ${ch.chapterNumber}" +
                    if (!ch.title.isNullOrBlank()) " - ${ch.title}" else ""
                date_upload = runCatching {
                    dateFormat.parse(ch.publishedAt ?: "")?.time
                }.getOrNull() ?: 0L
                chapter_number = ch.chapterNumber.toFloatOrNull() ?: 0f
                scanlator = if (ch.lockedByCoins == true) "🔒 مدفوع" else null
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Page List
    // ════════════════════════════════════════════════════════════════════════

    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.split("/")
        val seriesType = parts.getOrElse(0) { "manga" }
        val seriesId = parts.getOrElse(1) { "0" }
        val chapterId = parts.getOrElse(2) { "0" }

        val url = "$baseUrl/api/public/$seriesType/$seriesId/chapters"
            .toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("limit", "500")
            .addQueryParameter("order", "desc")
            .addQueryParameter("_cid", chapterId)
            .build()

        return GET(url, headers.newBuilder().set("Accept", "application/json").build())
    }

    override fun pageListParse(response: Response): List<Page> {
        val chapterId = response.request.url.queryParameter("_cid") ?: return emptyList()
        val pathParts = response.request.url.pathSegments
        val pubIdx = pathParts.indexOf("public")
        val seriesType = pathParts.getOrElse(pubIdx + 1) { "manga" }
        val seriesId = pathParts.getOrElse(pubIdx + 2) { "0" }
        val apiHeaders = headers.newBuilder().set("Accept", "application/json").build()

        val pages = mutableListOf<Page>()
        val seenUrls = mutableSetOf<String>()
        var cdnPath = "cdn1"
        var metadataImages = emptyList<String>()
        val mapsList = mutableListOf<DeferredPageMap>()
        var cachedSessionKey: String? = null
        var sessionKeyAttempted = false

        val getSessionKey = {
            if (!sessionKeyAttempted) {
                sessionKeyAttempted = true
                try {
                    val req = innerClient.newCall(
                        GET("$baseUrl/chapter-map-session-key/$chapterId?legacy=1", apiHeaders),
                    ).execute()
                    if (req.isSuccessful) {
                        cachedSessionKey = req.parseAs<SessionKeyResponse>().data?.key
                    }
                } catch (_: Exception) {}
            }
            cachedSessionKey
        }

        // ── تحليل بيانات الفصل ─────────────────────────────────────────────
        fun extractChapterData(data: ChaptersResponse): Boolean {
            for (ch in data.data) {
                if (ch.id.toString() == chapterId) {
                    cdnPath = ch.cdnPath ?: "cdn1"
                    metadataImages = ch.metadata?.images ?: emptyList()
                    ch.metadata?.maps?.let { mapsList.addAll(it) }
                    return true
                }
            }
            return false
        }

        val currentData = try {
            response.parseAs<ChaptersResponse>()
        } catch (_: Exception) {
            ChaptersResponse()
        }

        if (!extractChapterData(currentData)) {
            // البحث في الصفحات التالية
            for (pg in 2..10) {
                try {
                    val resp = innerClient.newCall(
                        GET(
                            "$baseUrl/api/public/$seriesType/$seriesId/chapters" +
                                "?limit=600&page=$pg&order=desc",
                            apiHeaders,
                        ),
                    ).execute()
                    if (!resp.isSuccessful) break
                    val data = resp.parseAs<ChaptersResponse>()
                    if (data.data.isEmpty()) break
                    if (extractChapterData(data)) break
                } catch (_: Exception) {
                    break
                }
            }
        }

        val cdnBase = "https://$cdnPath.procomic.pro"
        val jwtTokens = mutableListOf<String>()

        // ── الصور العادية ─────────────────────────────────────────────────
        metadataImages.forEach { imgPath ->
            val fullUrl = imgPath.toAbsoluteUrl(cdnBase)
            if (seenUrls.add(fullUrl)) pages.add(Page(pages.size, imageUrl = fullUrl))
        }

        // ── معالجة الـ maps ───────────────────────────────────────────────
        mapsList.forEach { map ->
            when {
                // JWT token → يحتاج fetch لاحق
                map.token.isNotBlank() && map.pieces.isEmpty() && map.token.isJwt() ->
                    jwtTokens.add(map.token)

                // token مشفر → حاول فك التشفير
                map.token.isNotBlank() && map.pieces.isEmpty() -> {
                    val originalMapBase64 = Base64.encodeToString(
                        json.encodeToString(map).toByteArray(),
                        Base64.NO_WRAP or Base64.URL_SAFE,
                    )
                    val resolved = resolveMap(map, chapterId, apiHeaders, getSessionKey)
                    if (resolved != null && resolved.pieces.isNotEmpty()) {
                        processMap(
                            resolved.dim, resolved.mode,
                            resolved.pieces.map { it.toAbsoluteUrl(cdnBase) },
                            resolved.order, resolved.token,
                            pages, seenUrls, chapterId, originalMapBase64,
                        )
                    }
                }

                // pieces جاهزة مباشرة
                map.pieces.isNotEmpty() ->
                    processMap(
                        map.dim, map.mode,
                        map.pieces.map { it.toAbsoluteUrl(cdnBase) },
                        map.order, map.token,
                        pages, seenUrls, chapterId, "",
                    )
            }
        }

        // ── JWT Deferred Pages ────────────────────────────────────────────
        for (jwtToken in jwtTokens) {
            try {
                pages.addAll(
                    fetchDeferredPages(chapterId, jwtToken, apiHeaders, seenUrls, cdnBase, getSessionKey),
                )
            } catch (_: Exception) {}
        }

        return pages
    }

    // ════════════════════════════════════════════════════════════════════════
    // Deferred / Proxy Plan
    // ════════════════════════════════════════════════════════════════════════

    private fun jwtSplitValue(jwtToken: String): Int {
        return try {
            val payload = jwtToken.split(".").getOrNull(1) ?: return DEFAULT_SPLIT
            val padded = payload.padEnd(payload.length + (4 - payload.length % 4) % 4, '=')
            json.decodeFromString<JwtPayload>(String(Base64.decode(padded, Base64.URL_SAFE))).split
        } catch (_: Exception) {
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
        val splitResponses = mutableListOf<ChapterDeferredData>()

        for (s in 0..jwtSplitValue(jwtToken)) {
            try {
                val resp = innerClient.newCall(
                    GET(
                        "$baseUrl/chapter-deferred-media/$chapterId?token=$jwtToken&split=$s",
                        apiHeaders,
                    ),
                ).execute()
                if (!resp.isSuccessful) continue
                val parsed = resp.parseAs<ChapterDeferredResponse>()
                if (parsed.success && parsed.data != null) splitResponses.add(parsed.data)
            } catch (_: Exception) {
                continue
            }
        }

        for (splitData in splitResponses) {
            val absolutePieceUrls = mutableSetOf<String>()
            splitData.maps.forEach { map ->
                if (map.token.isNotBlank() && map.pieces.isEmpty() && map.token.isJwt()) return@forEach
                val originalMapBase64 = Base64.encodeToString(
                    json.encodeToString(map).toByteArray(),
                    Base64.NO_WRAP or Base64.URL_SAFE,
                )
                val resolved = resolveMap(map, chapterId, apiHeaders, getSessionKey)
                if (resolved != null && resolved.pieces.isNotEmpty()) {
                    val absPieces = resolved.pieces.map { it.toAbsoluteUrl(cdnBase) }
                    absolutePieceUrls.addAll(absPieces)
                    processMap(
                        resolved.dim, resolved.mode, absPieces,
                        resolved.order, resolved.token,
                        pages, seenUrls, chapterId, originalMapBase64,
                    )
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
        if (map.token.isBlank()) return null

        // محاولة ١: فك التشفير بالـ session key
        var dec = getSessionKey()?.let { decryptMap(map.token, it) }
        if (dec != null && dec.pieces.isNotEmpty()) return dec

        // محاولة ٢: proxy plan
        try {
            val body = json.encodeToString(map).toRequestBody("application/json".toMediaType())
            val proxyResp = innerClient.newCall(
                POST(
                    "$baseUrl/chapter-map-proxy-plan/$chapterId",
                    apiHeaders.newBuilder()
                        .set("Origin", baseUrl)
                        .set("Referer", "$baseUrl/")
                        .build(),
                    body,
                ),
            ).execute()
            if (proxyResp.isSuccessful) {
                dec = proxyResp.parseAs<ProxyPlanResponse>().data?.map
            }
        } catch (_: Exception) {}

        return dec
    }

    private fun processMap(
        dim: List<Int>,
        mode: String,
        pieces: List<String>,
        order: List<Int>,
        signedToken: String,
        pages: MutableList<Page>,
        seenUrls: MutableSet<String>,
        chapterId: String,
        originalMapBase64: String,
    ) {
        if (pieces.isEmpty() || !seenUrls.add(pieces.first())) return

        val estimatedTotalH = dim.getOrNull(1)?.takeIf { it > 0 } ?: 10000
        val parts = if (estimatedTotalH > MAX_SAFE_HEIGHT) {
            ceil(estimatedTotalH.toDouble() / MAX_SAFE_HEIGHT).toInt()
        } else {
            1
        }

        for (p in 0 until parts) {
            val mapData = ScrambledMap(
                dim, mode, pieces, order, signedToken,
                p, parts, chapterId, originalMapBase64,
            )
            val encoded = Base64.encodeToString(
                json.encodeToString(mapData).toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP,
            )
            val shortUrl = "$SCRAMBLED_SCHEME${pages.size}_part_$p.jpg#$encoded"
            pages.add(Page(pages.size, url = shortUrl, imageUrl = shortUrl))
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Reconstruct Page (الجزء الأهم - تجميع القطع)
    // ════════════════════════════════════════════════════════════════════════

    private fun reconstructPage(map: ScrambledMap): ByteArray? {
        if (map.pieces.isEmpty()) return null

        val (cols, rows) = parseMode(map.mode, map.pieces.size)
        val bitmaps = arrayOfNulls<Bitmap>(map.pieces.size)

        var currentPieces = map.pieces
        var currentToken = map.signedToken

        val mapKey = map.pieces.firstOrNull() ?: return null
        val mapLock = mapLocks.getOrPut(mapKey) { Any() }

        synchronized(mapLock) {
            // تحقق هل نحتاج تحميل أي شيء
            val needsDownload = map.pieces.any { pieceCache.get(it) == null }

            if (needsDownload) {
                // ─── FIX ١ + ٢ + ٣: دالة التحميل المُصلحة ───────────────
                val success = fetchPiecesParallel(currentPieces, currentToken, map.pieces)

                // إذا فشل التحميل → جرب تجديد الـ map عبر proxy
                if (!success && map.chapterId.isNotEmpty() && map.originalMapBase64.isNotEmpty()) {
                    try {
                        val mapJson = String(
                            Base64.decode(map.originalMapBase64, Base64.URL_SAFE),
                        )
                        val proxyHeaders = headersBuilder()
                            .set("Origin", baseUrl)
                            .set("Referer", "$baseUrl/")
                            .set("Content-Type", "application/json")
                            .build()

                        innerClient.newCall(
                            POST(
                                "$baseUrl/chapter-map-proxy-plan/${map.chapterId}",
                                proxyHeaders,
                                mapJson.toRequestBody("application/json".toMediaType()),
                            ),
                        ).execute().use { proxyResp ->
                            if (proxyResp.isSuccessful) {
                                val proxyData = json.decodeFromStream<ProxyPlanResponse>(
                                    proxyResp.body!!.byteStream(),
                                )
                                proxyData.data?.map?.let { newMap ->
                                    currentPieces = newMap.pieces
                                    currentToken = newMap.token
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    // إعادة المحاولة بالـ map الجديد
                    fetchPiecesParallel(currentPieces, currentToken, map.pieces)
                }
            }
        }

        // ── استخراج الـ Bitmaps من الكاش ──────────────────────────────────
        for (targetIdx in map.pieces.indices) {
            val srcIdx = if (map.order.size == map.pieces.size) map.order[targetIdx] else targetIdx
            val cacheKey = map.pieces.getOrNull(srcIdx) ?: continue
            val bytes = pieceCache.get(cacheKey) ?: continue
            bitmaps[targetIdx] = decodeAvif(bytes)
        }

        return assembleBitmaps(bitmaps, map, cols, rows)
    }

    // ─── FIX الأساسي: دالة تحميل القطع المُصلحة بالكامل ─────────────────
    // إصلاحات:
    // ١. إضافة Cookie في الـ headers (يحل مشكلة forbidden في manhwa)
    // ٢. محاولة 3 صيغ مختلفة للـ URL (يحل مشكلة انتهاء التوكن)
    // ٣. قبول binary image مباشرة إذا لم يكن base64 (مرونة أكبر)
    private fun fetchPiecesParallel(
        pieces: List<String>,
        token: String,
        originalPieces: List<String>,
    ): Boolean {
        return runBlocking(Dispatchers.IO) {
            val results = pieces.indices.map { idx ->
                async {
                    val cacheKey = originalPieces.getOrNull(idx) ?: return@async false
                    if (pieceCache.get(cacheKey) != null) return@async true

                    val baseUrl = pieces.getOrNull(idx) ?: return@async false

                    // ─── FIX ٣: قائمة صيغ URL للمحاولة ─────────────────
                    // المشكلة القديمة: محاولة واحدة فقط → إذا فشلت انتهى الأمر
                    // الحل: ٣ صيغ مختلفة بالترتيب
                    val urlsToTry = buildList {
                        // صيغة ١: مع التوكن (الحالة المعتادة)
                        if (token.isNotBlank() && !baseUrl.contains("/i/eyJ")) {
                            add(
                                if (baseUrl.contains("?")) "$baseUrl&token=$token"
                                else "$baseUrl?token=$token",
                            )
                        }

                        // صيغة ٢: بدون توكن (المانهوا التي الـ URL نفسه مشفر وكافٍ)
                        add(baseUrl)

                        // صيغة ٣: مع /i/ prefix إذا كان الـ URL بدونه
                        if (!baseUrl.contains("/i/") && baseUrl.contains(".procomic.pro/")) {
                            add(baseUrl.replace(".procomic.pro/", ".procomic.pro/i/"))
                        }
                    }

                    for (url in urlsToTry) {
                        try {
                            // ─── FIX ١: إضافة Cookie في كل طلب قطعة ──────
                            val req = Request.Builder()
                                .url(url)
                                .headers(pieceHeaders()) // يشمل Cookie تلقائياً
                                .build()

                            val bytes = innerClient.newCall(req).execute().use { resp ->
                                if (!resp.isSuccessful) return@use null
                                resp.body?.bytes()
                            } ?: continue

                            // ─── FIX ٢: فحص شامل للـ Base64 ───────────────
                            val decoded = extractBase64FromBytes(bytes)
                            if (decoded != null) {
                                pieceCache.put(cacheKey, decoded)
                                return@async true
                            }

                            // ─── FIX ٣: قبول binary image مباشرة ──────────
                            if (isValidImageBytes(bytes)) {
                                pieceCache.put(cacheKey, bytes)
                                return@async true
                            }

                            // إذا رجع JSON خطأ مثل {"error":"forbidden"} → جرب الصيغة التالية
                            val preview = String(bytes, 0, minOf(50, bytes.size), Charsets.UTF_8)
                            if (preview.contains("forbidden") || preview.contains("error")) {
                                continue
                            }
                        } catch (_: Exception) {
                            continue
                        }
                    }
                    false
                }
            }.awaitAll()
            results.all { it }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Bitmap Assembly
    // ════════════════════════════════════════════════════════════════════════

    private fun assembleBitmaps(
        bitmaps: Array<Bitmap?>,
        map: ScrambledMap,
        cols: Int,
        rows: Int,
    ): ByteArray? {
        return try {
            val validBitmaps = bitmaps.filterNotNull()
            if (validBitmaps.isEmpty()) return null

            // ── حساب الأبعاد الكلية ────────────────────────────────────────
            val calcTotalW: Int
            val calcTotalH: Int
            when {
                cols == 1 -> {
                    calcTotalW = map.dim.getOrNull(0)?.takeIf { it > 0 }
                        ?: validBitmaps.maxOf { it.width }
                    calcTotalH = map.dim.getOrNull(1)?.takeIf { it > 0 }
                        ?: validBitmaps.sumOf { it.height }
                }
                rows == 1 -> {
                    calcTotalW = map.dim.getOrNull(0)?.takeIf { it > 0 }
                        ?: validBitmaps.sumOf { it.width }
                    calcTotalH = map.dim.getOrNull(1)?.takeIf { it > 0 }
                        ?: validBitmaps.maxOf { it.height }
                }
                else -> {
                    val first = validBitmaps.first()
                    calcTotalW = map.dim.getOrNull(0)?.takeIf { it > 0 }
                        ?: (first.width * cols)
                    calcTotalH = map.dim.getOrNull(1)?.takeIf { it > 0 }
                        ?: (first.height * rows)
                }
            }

            // ── تقسيم الصفحة الطويلة ──────────────────────────────────────
            val totalParts = map.totalParts ?: 1
            val splitPart = map.splitPart ?: 0
            val partH = calcTotalH / totalParts
            val actualPartH = if (splitPart == totalParts - 1) {
                calcTotalH - (partH * splitPart)
            } else {
                partH
            }

            if (calcTotalW <= 0 || actualPartH <= 0) return null

            // ── إنشاء الـ Canvas ──────────────────────────────────────────
            val result = try {
                Bitmap.createBitmap(calcTotalW, actualPartH, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                Bitmap.createBitmap(calcTotalW, actualPartH, Bitmap.Config.RGB_565)
            }
            val canvas = Canvas(result)
            canvas.translate(0f, -(splitPart * partH).toFloat())

            // ── رسم القطع حسب الـ mode ────────────────────────────────────
            when {
                cols == 1 -> {
                    // vertical strip
                    var y = 0f
                    for (bmp in bitmaps) {
                        if (bmp != null) {
                            canvas.drawBitmap(bmp, 0f, y, null)
                            y += bmp.height
                            bmp.recycle()
                        }
                    }
                }
                rows == 1 -> {
                    // horizontal strip
                    var x = 0f
                    for (bmp in bitmaps) {
                        if (bmp != null) {
                            canvas.drawBitmap(bmp, x, 0f, null)
                            x += bmp.width
                            bmp.recycle()
                        }
                    }
                }
                else -> {
                    // grid (2x4, 8x4, 6x3 ...)
                    val tileW = validBitmaps.first().width
                    val tileH = validBitmaps.first().height
                    for (i in bitmaps.indices) {
                        val bmp = bitmaps[i] ?: continue
                        val col = i % cols
                        val row = i / cols
                        canvas.drawBitmap(bmp, (col * tileW).toFloat(), (row * tileH).toFloat(), null)
                        bmp.recycle()
                    }
                }
            }

            val out = ByteArrayOutputStream()
            result.compress(Bitmap.CompressFormat.JPEG, 85, out)
            result.recycle()
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Crypto & Decoding
    // ════════════════════════════════════════════════════════════════════════

    private fun decryptMap(tokenStr: String, sessionKeyBase64: String): DeferredPageMap? {
        return try {
            val tokenJsonStr = String(Base64.decode(tokenStr, Base64.URL_SAFE or Base64.DEFAULT))
            val tokenData = json.decodeFromString<EncryptedToken>(tokenJsonStr)
            val keyBytes = Base64.decode(sessionKeyBase64, Base64.URL_SAFE)
            val ivBytes = Base64.decode(tokenData.iv, Base64.URL_SAFE)
            val tagBytes = Base64.decode(tokenData.tag, Base64.URL_SAFE)
            val cipherBytes = Base64.decode(tokenData.data, Base64.URL_SAFE)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(128, ivBytes),
            )
            val decrypted = cipher.doFinal(cipherBytes + tagBytes)
            json.decodeFromString<DeferredPageMap>(String(decrypted))
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeAvif(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val decoder = ImageDecoder.newInstance(bytes.inputStream())
        return if (decoder != null) {
            try {
                decoder.decode()
            } catch (_: Exception) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } finally {
                decoder.recycle()
            }
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    private fun parseMode(mode: String, pieceCount: Int): Pair<Int, Int> = when {
        mode.startsWith("grid_") -> {
            val clean = mode.removePrefix("grid_")
            val parts = if (clean.contains("x")) clean.split("x") else clean.split("_")
            Pair(
                parts.getOrNull(0)?.toIntOrNull() ?: 1,
                parts.getOrNull(1)?.toIntOrNull() ?: 1,
            )
        }
        mode.startsWith("vertical_") -> Pair(
            mode.removePrefix("vertical_").toIntOrNull() ?: pieceCount, 1,
        )
        mode.startsWith("horizontal_") -> Pair(
            1, mode.removePrefix("horizontal_").toIntOrNull() ?: pieceCount,
        )
        else -> Pair(1, pieceCount)
    }

    private inline fun <reified T> Response.parseAs(): T =
        json.decodeFromStream(body.byteStream())
}

// ════════════════════════════════════════════════════════════════════════════
// Constants & Data Classes
// ════════════════════════════════════════════════════════════════════════════

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
data class ScrambledMap(
    val dim: List<Int> = emptyList(),
    val mode: String = "",
    val pieces: List<String> = emptyList(),
    val order: List<Int> = emptyList(),
    val signedToken: String = "",
    val splitPart: Int? = null,
    val totalParts: Int? = null,
    val chapterId: String = "",
    val originalMapBase64: String = "",
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
data class CoverImageApp(
    val desktop: String? = null,
    val card: CardImages? = null,
)

@Serializable
data class CardImages(
    val mobile: String? = null,
    val desktop: String? = null,
)

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
data class ProxyPlanData(val map: DeferredPageMap? = null)
