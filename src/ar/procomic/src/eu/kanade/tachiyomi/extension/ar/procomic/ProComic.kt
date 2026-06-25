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
        // مخزن الصور المجمّعة: key=chapterId_mapIndex → JPEG bytes
        private val assembledCache = object : LruCache<String, ByteArray>(80 * 1024 * 1024) {
            override fun sizeOf(key: String, value: ByteArray): Int = value.size
        }

        private const val ASSEMBLED_SCHEME = "https://procomic.pro/__assembled__/"
        private const val MAX_SAFE_HEIGHT = 6000
        private const val TYPE_PREF = "TypePref"
        private const val COOKIE_PREF = "SessionCookie"
        private val TYPE_PREF_ENTRIES = arrayOf("الكل", "مانجا", "مانهوا", "مانهوا صينية (Manhua)", "كوميكس")
        private val TYPE_PREF_ENTRY_VALUES = arrayOf("all", "manga", "manhwa", "manhua", "comic")
        private const val TYPE_PREF_DEFAULT = "all"
    }

    // ════════════════════════════════════════════════════════════════════════
    // Preferences
    // ════════════════════════════════════════════════════════════════════════

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = TYPE_PREF
            title = "تصفية نوع السلاسل"
            entries = TYPE_PREF_ENTRIES
            entryValues = TYPE_PREF_ENTRY_VALUES
            setDefaultValue(TYPE_PREF_DEFAULT)
            summary = "حدد النوع الذي تريد عرضه"
            setOnPreferenceChangeListener { _, newValue ->
                val index = findIndexOfValue(newValue as String)
                Toast.makeText(screen.context, "قم بتحديث الصفحة لتطبيق عرض: ${entries[index]}", Toast.LENGTH_LONG).show()
                true
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = COOKIE_PREF
            title = "🍪 Cookie الجلسة (مطلوب للمانهوا)"
            summary = "الصق Cookie من المتصفح بعد تسجيل الدخول في procomic.pro"
            dialogTitle = "قيمة Cookie"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    private fun getSelectedType() = preferences.getString(TYPE_PREF, TYPE_PREF_DEFAULT) ?: TYPE_PREF_DEFAULT

    private fun getSessionCookie(): String = preferences.getString(COOKIE_PREF, "")
        ?.trim()
        ?.removePrefix("\"")?.removeSuffix("\"")
        ?.removePrefix("'")?.removeSuffix("'")
        ?: ""

    // ════════════════════════════════════════════════════════════════════════
    // Clients
    // ════════════════════════════════════════════════════════════════════════

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

            // ── تقديم الصورة المجمّعة المخزّنة مسبقاً ──────────────────────
            // هذا الحل الجذري: الصورة تُجمَّع في pageListParse قبل انتهاء التوكن
            if (url.startsWith(ASSEMBLED_SCHEME)) {
                val cacheKey = url.removePrefix(ASSEMBLED_SCHEME)
                val imageBytes = assembledCache.get(cacheKey)
                    ?: return@addInterceptor Response.Builder()
                        .request(request).protocol(Protocol.HTTP_1_1)
                        .code(404).message("Not in cache")
                        .body("".toResponseBody(null)).build()

                return@addInterceptor Response.Builder()
                    .request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(imageBytes.toResponseBody("image/jpeg".toMediaType()))
                    .build()
            }

            // ── معالجة data:image/...;base64,... responses ──────────────────
            val response = chain.proceed(request)
            if (response.isSuccessful && request.method == "GET" && url.contains("procomic")) {
                val body = response.body ?: return@addInterceptor response
                val bytes = body.bytes()
                val decoded = extractBase64Image(bytes)
                if (decoded != null) {
                    return@addInterceptor response.newBuilder()
                        .body(decoded.toResponseBody(extractMimeType(bytes).toMediaType()))
                        .build()
                }
                return@addInterceptor response.newBuilder()
                    .body(bytes.toResponseBody(body.contentType()))
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
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

    private fun authedHeaders(accept: String = "application/json"): Headers {
        return headersBuilder().apply {
            set("Accept", accept)
            val cookie = getSessionCookie()
            if (cookie.isNotBlank()) set("Cookie", cookie)
        }.build()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Image Helpers
    // ════════════════════════════════════════════════════════════════════════

    private fun extractBase64Image(bytes: ByteArray): ByteArray? {
        if (bytes.size < 10) return null
        val prefix = String(bytes, 0, minOf(150, bytes.size), Charsets.US_ASCII)
        val marker = prefix.indexOf("base64,")
        if (marker == -1) return null
        val start = marker + 7
        var end = bytes.size
        while (end > start) {
            when (bytes[end - 1].toInt().toChar()) {
                '"', '\'', '\n', '\r', ' ' -> end--
                else -> break
            }
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

    private fun decodeBase64Safe(s: String): ByteArray {
        val clean = s.replace("\n", "").replace("\r", "").replace(" ", "").trim()
        val padLen = (4 - clean.length % 4) % 4
        val padded = if (padLen == 0) clean else clean.padEnd(clean.length + padLen, '=')
        return Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Image Request — يُقدّم الصور المجمّعة مباشرة
    // ════════════════════════════════════════════════════════════════════════

    override fun imageRequest(page: Page): Request {
        // الصور المجمّعة لها URL من نوع ASSEMBLED_SCHEME
        // الـ interceptor يخدمها من assembledCache مباشرة
        return Request.Builder()
            .url(page.imageUrl ?: page.url)
            .headers(headersBuilder().build())
            .build()
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
        GET("$baseUrl/api/public/content/latest-updates?limit=30&category=comics&page=$page" + if (query.isNotBlank()) "&q=$query" else "", headers)
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
                author = data.author; artist = data.artist
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
        return GET("$baseUrl/api/public/${p[0]}/${p[1]}/chapters?page=1&limit=600&order=desc", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val parts = response.request.url.pathSegments
        val idx = parts.indexOf("public")
        val seriesType = parts.getOrElse(idx + 1) { "manga" }
        val seriesId = parts.getOrElse(idx + 2) { "0" }
        return response.parseAs<ChaptersResponse>().data.map { ch ->
            SChapter.create().apply {
                url = "$seriesType/$seriesId/${ch.id}/${ch.chapterNumber}"
                name = "الفصل ${ch.chapterNumber}" + if (!ch.title.isNullOrBlank()) " - ${ch.title}" else ""
                date_upload = runCatching { dateFormat.parse(ch.publishedAt ?: "")?.time }.getOrNull() ?: 0L
                chapter_number = ch.chapterNumber.toFloatOrNull() ?: 0f
                scanlator = if (ch.lockedByCoins == true) "🔒 مدفوع" else null
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Page List — يُجمّع الصور فوراً قبل انتهاء التوكن
    // ════════════════════════════════════════════════════════════════════════

    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.split("/")
        val seriesType = parts.getOrElse(0) { "manga" }
        val seriesId = parts.getOrElse(1) { "0" }
        val chapterId = parts.getOrElse(2) { "0" }
        val url = "$baseUrl/api/public/$seriesType/$seriesId/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("limit", "500")
            .addQueryParameter("order", "desc")
            .addQueryParameter("_cid", chapterId)
            .build()
        return GET(url, authedHeaders())
    }

    override fun pageListParse(response: Response): List<Page> {
        val chapterId = response.request.url.queryParameter("_cid") ?: return emptyList()
        val pathParts = response.request.url.pathSegments
        val pubIdx = pathParts.indexOf("public")
        val seriesType = pathParts.getOrElse(pubIdx + 1) { "manga" }
        val seriesId = pathParts.getOrElse(pubIdx + 2) { "0" }
        val apiHeaders = authedHeaders()

        val pages = mutableListOf<Page>()
        val seenUrls = mutableSetOf<String>()
        var cdnPath = "cdn1"
        var metadataImages = emptyList<String>()
        val mapsList = mutableListOf<DeferredPageMap>()
        var cachedSessionKey: String? = null
        var sessionKeyAttempted = false

        val getSessionKey: () -> String? = {
            if (!sessionKeyAttempted) {
                sessionKeyAttempted = true
                try {
                    val req = innerClient.newCall(
                        GET("$baseUrl/chapter-map-session-key/$chapterId?legacy=1", apiHeaders)
                    ).execute()
                    if (req.isSuccessful) cachedSessionKey = req.parseAs<SessionKeyResponse>().data?.key
                } catch (_: Exception) {}
            }
            cachedSessionKey
        }

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

        val currentData = try { response.parseAs<ChaptersResponse>() } catch (_: Exception) { ChaptersResponse() }
        if (!extractChapterData(currentData)) {
            for (pg in 2..10) {
                try {
                    val resp = innerClient.newCall(
                        GET("$baseUrl/api/public/$seriesType/$seriesId/chapters?limit=600&page=$pg&order=desc", apiHeaders)
                    ).execute()
                    if (!resp.isSuccessful) break
                    val data = resp.parseAs<ChaptersResponse>()
                    if (data.data.isEmpty()) break
                    if (extractChapterData(data)) break
                } catch (_: Exception) { break }
            }
        }

        val cdnBase = "https://$cdnPath.procomic.pro"

        // ── الصور العادية (3 صفحات كاملة) ───────────────────────────────
        metadataImages.forEach { imgPath ->
            val fullUrl = imgPath.toAbsoluteUrl(cdnBase)
            if (seenUrls.add(fullUrl)) pages.add(Page(pages.size, imageUrl = fullUrl))
        }

        // ── معالجة الـ maps: تُجمَّع الصور الآن فوراً! ──────────────────
        // هذا هو الحل الجذري — بدل تأجيل التجميع للـ interceptor،
        // نجمّع الصورة الآن بينما التوكن لا يزال صالحاً
        mapsList.forEachIndexed { mapIdx, map ->
            if (map.token.isBlank() && map.pieces.isEmpty()) return@forEachIndexed

            val resolved = if (map.pieces.isNotEmpty()) {
                map
            } else {
                resolveMap(map, chapterId, apiHeaders, getSessionKey)
            } ?: return@forEachIndexed

            if (resolved.pieces.isEmpty()) return@forEachIndexed

            val absolutePieces = resolved.pieces.map { it.toAbsoluteUrl(cdnBase) }
            val mapKey = "${chapterId}_map_${mapIdx}"

            // تحميل وتجميع فوري بينما التوكن ساخن
            val assembled = downloadAndAssemble(
                pieces = absolutePieces,
                token = resolved.token,
                dim = resolved.dim,
                mode = resolved.mode,
                order = resolved.order,
            )

            if (assembled != null) {
                // تقسيم الصورة الطويلة إذا لزم
                val totalH = assembled.height
                val parts = if (totalH > MAX_SAFE_HEIGHT) ceil(totalH.toDouble() / MAX_SAFE_HEIGHT).toInt() else 1

                for (partIdx in 0 until parts) {
                    val partKey = "${mapKey}_part_${partIdx}"
                    val partBytes = if (parts == 1) {
                        compressBitmap(assembled)
                    } else {
                        val partH = totalH / parts
                        val actualPartH = if (partIdx == parts - 1) totalH - partH * partIdx else partH
                        val partBitmap = Bitmap.createBitmap(assembled, 0, partIdx * partH, assembled.width, actualPartH)
                        compressBitmap(partBitmap).also { partBitmap.recycle() }
                    }

                    if (seenUrls.add(partKey)) {
                        assembledCache.put(partKey, partBytes)
                        val fakeUrl = "$ASSEMBLED_SCHEME$partKey"
                        pages.add(Page(pages.size, imageUrl = fakeUrl))
                    }
                }
                assembled.recycle()
            }
        }

        return pages
    }

    // ════════════════════════════════════════════════════════════════════════
    // Download & Assemble — التحميل والتجميع الفوري
    // ════════════════════════════════════════════════════════════════════════

    private fun downloadAndAssemble(
        pieces: List<String>,
        token: String,
        dim: List<Int>,
        mode: String,
        order: List<Int>,
    ): Bitmap? {
        // تحميل كل القطع بالتوازي — فوري قبل انتهاء التوكن
        val rawBitmaps = runBlocking(Dispatchers.IO) {
            pieces.indices.map { idx ->
                async {
                    val pieceUrl = pieces.getOrNull(idx) ?: return@async null
                    downloadPieceAsBitmap(pieceUrl, token)
                }
            }.awaitAll()
        }

        val (cols, rows) = parseMode(mode, pieces.size)

        // ترتيب البتماب حسب order
        val orderedBitmaps = Array<Bitmap?>(pieces.size) { targetIdx ->
            val srcIdx = if (order.size == pieces.size) order[targetIdx] else targetIdx
            rawBitmaps.getOrNull(srcIdx) ?: null
        }

        return assembleToBitmap(orderedBitmaps, dim, cols, rows)
    }

    private fun downloadPieceAsBitmap(pieceUrl: String, token: String): Bitmap? {
        val urlsToTry = buildList {
            if (token.isNotBlank() && !pieceUrl.contains("/i/eyJ")) {
                add(if (pieceUrl.contains("?")) "$pieceUrl&token=$token" else "$pieceUrl?token=$token")
            }
            add(pieceUrl)
        }

        for (url in urlsToTry) {
            try {
                val bytes = innerClient.newCall(
                    Request.Builder()
                        .url(url)
                        .headers(authedHeaders("image/avif,image/webp,image/*,*/*;q=0.8"))
                        .build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    resp.body?.bytes()
                } ?: continue

                val imageBytes = extractBase64Image(bytes) ?: if (isRawImageBytes(bytes)) bytes else null ?: continue
                return decodeAvif(imageBytes)
            } catch (_: Exception) { continue }
        }
        return null
    }

    private fun assembleToBitmap(bitmaps: Array<Bitmap?>, dim: List<Int>, cols: Int, rows: Int): Bitmap? {
        val valid = bitmaps.filterNotNull()
        if (valid.isEmpty()) return null

        val totalW: Int; val totalH: Int
        when {
            cols == 1 -> { totalW = dim.getOrNull(0)?.takeIf { it > 0 } ?: valid.maxOf { it.width }; totalH = dim.getOrNull(1)?.takeIf { it > 0 } ?: valid.sumOf { it.height } }
            rows == 1 -> { totalW = dim.getOrNull(0)?.takeIf { it > 0 } ?: valid.sumOf { it.width }; totalH = dim.getOrNull(1)?.takeIf { it > 0 } ?: valid.maxOf { it.height } }
            else -> { totalW = dim.getOrNull(0)?.takeIf { it > 0 } ?: (valid.first().width * cols); totalH = dim.getOrNull(1)?.takeIf { it > 0 } ?: (valid.first().height * rows) }
        }
        if (totalW <= 0 || totalH <= 0) return null

        val result = try { Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888) }
                     catch (_: OutOfMemoryError) { Bitmap.createBitmap(totalW, totalH, Bitmap.Config.RGB_565) }
        val canvas = Canvas(result)

        when {
            cols == 1 -> { var y = 0f; bitmaps.forEach { bmp -> bmp?.let { canvas.drawBitmap(it, 0f, y, null); y += it.height; it.recycle() } } }
            rows == 1 -> { var x = 0f; bitmaps.forEach { bmp -> bmp?.let { canvas.drawBitmap(it, x, 0f, null); x += it.width; it.recycle() } } }
            else -> {
                val tileW = valid.first().width; val tileH = valid.first().height
                bitmaps.forEachIndexed { i, bmp -> bmp?.let { canvas.drawBitmap(it, (i % cols * tileW).toFloat(), (i / cols * tileH).toFloat(), null); it.recycle() } }
            }
        }
        return result
    }

    private fun compressBitmap(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    // ════════════════════════════════════════════════════════════════════════
    // Resolve Map
    // ════════════════════════════════════════════════════════════════════════

    private fun resolveMap(
        map: DeferredPageMap, chapterId: String,
        apiHeaders: Headers, getSessionKey: () -> String?,
    ): DeferredPageMap? {
        if (map.pieces.isNotEmpty()) return map
        if (map.token.isBlank()) return null

        // محاولة ١: فك التشفير بـ session key
        var dec = getSessionKey()?.let { decryptMap(map.token, it) }
        if (dec != null && dec.pieces.isNotEmpty()) return dec

        // محاولة ٢: proxy plan
        try {
            val body = json.encodeToString(map).toRequestBody("application/json".toMediaType())
            val proxyResp = innerClient.newCall(
                POST("$baseUrl/chapter-map-proxy-plan/$chapterId", apiHeaders, body)
            ).execute()
            if (proxyResp.isSuccessful) dec = proxyResp.parseAs<ProxyPlanResponse>().data?.map
        } catch (_: Exception) {}

        return dec
    }

    // ════════════════════════════════════════════════════════════════════════
    // Crypto & Decode
    // ════════════════════════════════════════════════════════════════════════

    private fun decryptMap(tokenStr: String, sessionKeyBase64: String): DeferredPageMap? {
        return try {
            val tokenData = json.decodeFromString<EncryptedToken>(String(decodeBase64Safe(tokenStr)))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(decodeBase64Safe(sessionKeyBase64), "AES"),
                GCMParameterSpec(128, decodeBase64Safe(tokenData.iv))
            )
            // Java AES/GCM: ciphertext + authTag معاً في doFinal
            val decrypted = cipher.doFinal(decodeBase64Safe(tokenData.data) + decodeBase64Safe(tokenData.tag))
            json.decodeFromString<DeferredPageMap>(String(decrypted).trimEnd('\u0000'))
        } catch (_: Exception) { null }
    }

    private fun decodeAvif(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val decoder = ImageDecoder.newInstance(bytes.inputStream())
        return if (decoder != null) {
            try { decoder.decode() } catch (_: Exception) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } finally { decoder.recycle() }
        } else { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
    }

    private fun parseMode(mode: String, pieceCount: Int): Pair<Int, Int> = when {
        mode.startsWith("grid_") -> { val p = mode.removePrefix("grid_").let { if (it.contains("x")) it.split("x") else it.split("_") }; Pair(p.getOrNull(0)?.toIntOrNull() ?: 1, p.getOrNull(1)?.toIntOrNull() ?: 1) }
        mode.startsWith("vertical_") -> Pair(mode.removePrefix("vertical_").toIntOrNull() ?: pieceCount, 1)
        mode.startsWith("horizontal_") -> Pair(1, mode.removePrefix("horizontal_").toIntOrNull() ?: pieceCount)
        else -> Pair(1, pieceCount)
    }

    private fun String.toAbsoluteUrl(cdnBase: String) = when {
        startsWith("http") -> this
        startsWith("eyJ") -> "$cdnBase/i/$this"
        startsWith("/") -> "$cdnBase$this"
        else -> "$cdnBase/$this"
    }

    private inline fun <reified T> Response.parseAs(): T = json.decodeFromStream(body.byteStream())
}

// ════════════════════════════════════════════════════════════════════════════
// Data Classes
// ════════════════════════════════════════════════════════════════════════════

@Serializable data class SessionKeyResponse(val success: Boolean = false, val data: SessionKeyData? = null)
@Serializable data class SessionKeyData(val key: String = "")
@Serializable data class EncryptedToken(val v: Int = 3, val m: String = "", val cid: Int = 0, val iv: String = "", val tag: String = "", val data: String = "")
@Serializable data class LatestUpdatesResponse(val success: Boolean = false, val data: List<SeriesDto> = emptyList())
@Serializable data class SeriesDto(@SerialName("mangaId") val id: Int = 0, @SerialName("mangaSlug") val slug: String = "", @SerialName("mangaTitle") val title: String = "", val coverImage: String? = null, val type: String = "manga", val coverImageApp: CoverImageApp? = null) {
    fun toSManga() = SManga.create().apply { url = "$type/$id/$slug"; title = this@SeriesDto.title; thumbnail_url = coverImageApp?.card?.mobile ?: coverImageApp?.desktop ?: coverImage }
}
@Serializable data class CoverImageApp(val desktop: String? = null, val card: CardImages? = null)
@Serializable data class CardImages(val mobile: String? = null, val desktop: String? = null)
@Serializable data class SeriesDetailResponse(val id: Int = 0, val title: String? = null, val slug: String? = null, val coverImage: String? = null, val coverImageApp: CoverImageApp? = null, val author: String? = null, val artist: String? = null, val description: String? = null, val synopsis: String? = null, val status: String? = null)
@Serializable data class ChaptersResponse(val data: List<ChapterDto> = emptyList(), val total: Int = 0)
@Serializable data class ChapterDto(val id: Int = 0, @SerialName("chapter_number") val chapterNumber: String = "0", val title: String? = null, @SerialName("published_at") val publishedAt: String? = null, val lockedByCoins: Boolean? = null, @SerialName("cdn_path") val cdnPath: String? = null, val metadata: ChapterMetadataDto? = null)
@Serializable data class ChapterMetadataDto(val images: List<String> = emptyList(), val maps: List<DeferredPageMap> = emptyList())
@Serializable data class DeferredPageMap(val dim: List<Int> = emptyList(), val mode: String = "", val pieces: List<String> = emptyList(), val order: List<Int> = emptyList(), val token: String = "", val method: String = "")
@Serializable data class ProxyPlanResponse(val success: Boolean = false, val data: ProxyPlanData? = null)
@Serializable data class ProxyPlanData(val map: DeferredPageMap? = null)
