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
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ProComic : HttpSource(), ConfigurableSource {

    override val name = "ProComic"
    override val baseUrl = "https://procomic.pro"
    override val lang = "ar"
    override val supportsLatest = true

    private val json: Json = Injekt.get()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", Application.MODE_PRIVATE)
    }

    // الحفاظ على عميل الشبكة المزدوج مع دمج معترض الصور اللحظي لمنع تلف الروابط
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(2)
        .addInterceptor(::lazyImageInterceptor)
        .build()

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .apply {
            val session = getSessionCookie()
            if (session.isNotEmpty()) {
                add("Cookie", "procomic_session=$session")
            }
        }

    private fun pieceRequestHeaders(): Headers = Headers.Builder()
        .add("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
        .add("Referer", "$baseUrl/")
        .add("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        .add("Sec-Fetch-Dest", "image")
        .add("Sec-Fetch-Mode", "no-cors")
        .add("Sec-Fetch-Site", "same-site")
        .build()

    // --- الحفاظ على جميع دوال جلب القوائم والبحث والتفاصيل الأصلية بدقة ---
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/api/manga?page=$page&sort=views", headers)
    override fun popularMangaParse(response: Response): MangasPage = parseMangaList(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/manga?page=$page&sort=latest", headers)
    override fun latestUpdatesParse(response: Response): MangasPage = parseMangaList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/manga?page=$page&search=$query"
        return GET(url, headers)
    }
    override fun searchMangaParse(response: Response): MangasPage = parseMangaList(response)

    private fun parseMangaList(response: Response): MangasPage {
        val res = json.decodeFromString<MangaListResponse>(response.body.string())
        val mangas = res.data.map { dto ->
            SManga.create().apply {
                url = "/manga/${dto.id}"
                title = dto.title ?: "عنوان غير معروف"
                thumbnail_url = dto.coverImageApp?.url ?: dto.coverImage
            }
        }
        return MangasPage(mangas, res.total > mangas.size)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val dto = json.decodeFromString<MangaDetailResponse>(response.body.string()).data
        return SManga.create().apply {
            title = dto.title ?: ""
            author = dto.author
            artist = dto.artist
            description = dto.synopsis ?: dto.description
            status = when (dto.status) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            thumbnail_url = dto.coverImageApp?.url ?: dto.coverImage
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val res = json.decodeFromString<ChaptersResponse>(response.body.string())
        return res.data.map { dto ->
            SChapter.create().apply {
                url = "/chapter/${dto.id}"
                name = "الفصل ${dto.chapterNumber}" + (if (dto.title.isNullOrEmpty()) "" else " - ${dto.title}")
                date_upload = 0L // يمكن تحسين الطابع الزمني لاحقاً إذا لزم الأمر
            }
        }
    }

    // --- دالة الفصول المعدلة برمجياً لمنع تلف الروابط (Lazy Architecture) ---
    override fun pageListParse(response: Response): List<Page> {
        val chapterId = response.request.url.pathSegments.lastOrNull() ?: throw IOException("تعذر تحديد معرف الفصل")
        val responseBody = response.body.string()

        val jwtToken = extractJwtToken(responseBody) ?: throw IOException("لم يتم العثور على توكن الحماية الأساسي")
        val totalSplits = jwtSplitValue(jwtToken)

        val pages = mutableListOf<Page>()
        var overallPageIndex = 0

        // نقوم بالمرور السريع على الـ Splits لحساب عدد الصفحات فقط دون تحميل بايتات الصور لحمايتها من التلف المبكر
        for (splitIndex in 0 until totalSplits) {
            val splitUrl = "$baseUrl/chapter-deferred-media/$chapterId?token=$jwtToken&split=$splitIndex"
            val splitResponse = client.newCall(GET(splitUrl, headers)).execute()
            if (!splitResponse.isSuccessful) continue

            val splitData = json.decodeFromString<ChapterDeferredResponse>(splitResponse.body.string()).data ?: continue

            for (indexInSplit in splitData.maps.indices) {
                // ننشئ رابط داخلي وهمي مشفر يحمل البارامترات المطلوبة للبناء اللحظي عند التمرير
                val lazyUrl = "https://procomic.pro/lazy-page"
                    .toHttpUrl().newBuilder()
                    .addQueryParameter("chapterId", chapterId)
                    .addQueryParameter("splitIndex", splitIndex.toString())
                    .addQueryParameter("indexInSplit", indexInSplit.toString())
                    .addQueryParameter("jwt", jwtToken)
                    .build().toString()

                pages.add(Page(overallPageIndex, "", lazyUrl))
                overallPageIndex++
            }
        }
        return pages
    }

    // المعترض الاحترافي المسؤول عن التقاط طلب الصفحة الحالية وتوليد روابط وقطع "طازجة" فوراً
    private fun lazyImageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        if (url.host == "procomic.pro" && url.encodedPath == "/lazy-page") {
            val chapterId = url.queryParameter("chapterId") ?: throw IOException("Missing chapterId")
            val splitIndex = url.queryParameter("splitIndex") ?: throw IOException("Missing splitIndex")
            val indexInSplit = url.queryParameter("indexInSplit")?.toIntOrNull() ?: throw IOException("Missing indexInSplit")
            val jwtToken = url.queryParameter("jwt") ?: throw IOException("Missing jwt")

            // 1. جلب خريطة حية من السيرفر في جزء الثانية الحالي لمنع أي خطأ انتهاء صلاحية (Expiration)
            val freshSplitUrl = "$baseUrl/chapter-deferred-media/$chapterId?token=$jwtToken&split=$splitIndex"
            val freshResponse = client.newCall(GET(freshSplitUrl, headers)).execute()
            if (!freshResponse.isSuccessful) throw IOException("فشل جلب خريطة الصفحة الحية من السيرفر")

            val splitData = json.decodeFromString<ChapterDeferredResponse>(freshResponse.body.string()).data
                ?: throw IOException("بيانات الخريطة فارغة")

            val targetMap = splitData.maps.getOrNull(indexInSplit)
                ?: throw IOException("تعذر العثور على خريطة التوزيع للصفحة المطلوبة")

            // 2. إعادة بناء وتجميع الصورة عبر الـ Canvas
            val bitmap = reconstructPageWithFallbacks(chapterId, targetMap)

            // 3. ضخ البايتات للمستعرض مباشرة
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            val imageBytes = outputStream.toByteArray()
            bitmap.recycle() // تفريغ الذاكرة فوراً لمنع الـ OOM في الأجهزة الضعيفة

            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(imageBytes.toResponseBody("image/jpeg".toMediaType()))
                .build()
        }

        return chain.proceed(request)
    }

    // دالة التجميع مع الحفاظ على آليات الـ Proxy Plan الأصلية في حال حدوث أي تلف شبكي طارئ
    private fun reconstructPageWithFallbacks(chapterId: String, initialMap: DeferredPageMap): Bitmap {
        var map = initialMap
        var piecesBitmaps = downloadAllPieces(map)

        // إذا فشل تحميل أي قطعة، نقوم بتفعيل خط الدفاع الاحترافي الأصلي الخاص بك (Proxy Plan) لإعادة إحياء التوكنات
        if (piecesBitmaps.any { it == null }) {
            val updatedMap = resolveMapViaProxyPlan(chapterId, map)
            if (updatedMap != null) {
                map = updatedMap
                piecesBitmaps = downloadAllPieces(map)
            }
        }

        if (piecesBitmaps.any { it == null }) {
            throw IOException("تعذر فك تشفير الصفحة وتحميل قطعها الأمنية")
        }

        return assembleBitmaps(piecesBitmaps.filterNotNull(), map)
    }

    private fun downloadAllPieces(map: DeferredPageMap): List<Bitmap?> {
        return runBlocking(Dispatchers.IO) {
            map.pieces.map { pieceUrl ->
                async {
                    val fullUrl = if (map.token.isNotEmpty()) "$pieceUrl?token=${map.token}" else pieceUrl
                    val request = Request.Builder().url(fullUrl).headers(pieceRequestHeaders()).build()
                    try {
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) BitmapFactory.decodeStream(response.body.byteStream()) else null
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }
    }

    // الحفاظ على دالة الـ Proxy Plan الأصلية الخاصة بك بالكامل دون أي تبسيط
    private fun resolveMapViaProxyPlan(chapterId: String, failedMap: DeferredPageMap): DeferredPageMap? {
        return try {
            val rawMapJson = json.encodeToString(failedMap)
            val base64Map = Base64.encodeToString(rawMapJson.toByteArray(), Base64.DEFAULT or Base64.NO_WRAP)
            
            val proxyUrl = "$baseUrl/chapter-map-proxy-plan/$chapterId"
            val payload = "{\"map\":\"$base64Map\"}"
            val request = Request.Builder()
                .url(proxyUrl)
                .post(payload.toResponseBody("application/json".toMediaType()))
                .headers(headers)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val resData = json.decodeFromString<ProxyPlanResponse>(response.body.string())
                if (resData.success) resData.data?.map else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // دالة الـ Canvas والهندسة الرياضية لترتيب القطع المبعثرة (مستوحاة من ملفات الـ Smali للتطبيق)
    private fun assembleBitmaps(pieces: List<Bitmap>, map: DeferredPageMap): Bitmap {
        val width = map.dim.getOrNull(0) ?: 800
        val height = map.dim.getOrNull(1) ?: 1200

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)

        val cols = 5
        val rows = if (pieces.isNotEmpty()) (pieces.size + cols - 1) / cols else 1

        val tileWidth = width / cols
        val tileHeight = height / rows

        for (i in pieces.indices) {
            val correctIndex = map.order.getOrNull(i) ?: i
            val srcX = (i % cols) * tileWidth
            val srcY = (i / cols) * tileHeight

            val destX = (correctIndex % cols) * tileWidth
            val destY = (correctIndex / cols) * tileHeight

            val piece = pieces.getOrNull(i) ?: continue
            canvas.drawBitmap(piece, srcX.toFloat(), srcY.toFloat(), null)
        }
        return resultBitmap
    }

    // --- الحفاظ على دالات استخراج البيانات والتوكنات المساعدة ---
    private fun extractJwtToken(html: String): String? {
        val regex = """\"token\"\s*:\s*\"([^\"]+)\"""".toRegex()
        return regex.find(html)?.groupValues?.get(1)
    }

    private fun jwtSplitValue(token: String): Int {
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return 1
            val payload = String(Base64.decode(parts[1], Base64.DEFAULT or Base64.NO_WRAP))
            if (payload.contains("\"split\":")) {
                val regex = """\"split\"\s*:\s*(\d+)""".toRegex()
                regex.find(payload)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            } else 1
        } catch (e: Exception) {
            1
        }
    }

    private fun getSessionCookie(): String {
        return preferences.getString("session_cookie", "") ?: ""
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    // --- الحفاظ على شاشة الإعدادات وحفظ الجلسات الأصلية الخاصة بك كاملة ---
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val sessionPref = EditTextPreference(screen.context).apply {
            key = "session_cookie"
            title = "Session Cookie (procomic_session)"
            summary = "أدخل قيمة الكوكيز يدوياً للوصول للفصول المحمية والمدفوعة الخاصة بحسابك"
            dialogTitle = "procomic_session"
            setOnPreferenceChangeListener { _, newValue ->
                Toast.makeText(screen.context, "تم حفظ الجلسة بنجاح، يرجى تحديث القائمة", Toast.LENGTH_SHORT).show()
                true
            }
        }
        screen.addPreference(sessionPref)
    }
}

// --- الحفاظ على كافة كائنات نقل البيانات (DTOs) دون المساس بها أو حذف أي حقل ---
@Serializable data class MangaListResponse(val data: List<MangaDto> = emptyList(), val total: Int = 0)
@Serializable data class MangaDto(val id: Int, val title: String? = null, @SerialName("cover_image") val coverImage: String? = null, @SerialName("cover_image_app") val coverImageApp: CoverImageApp? = null)
@Serializable data class CoverImageApp(val url: String? = null)
@Serializable data class MangaDetailResponse(val data: MangaDetailDto)
@Serializable data class MangaDetailDto(val id: Int, val title: String? = null, @SerialName("cover_image") val coverImage: String? = null, @SerialName("cover_image_app") val coverImageApp: CoverImageApp? = null, val author: String? = null, val artist: String? = null, val description: String? = null, val synopsis: String? = null, val status: String? = null)
@Serializable data class ChaptersResponse(val data: List<ChapterDto> = emptyList(), val total: Int = 0)
@Serializable data class ChapterDto(val id: Int = 0, @SerialName("chapter_number") val chapterNumber: String = "0", val title: String? = null, @SerialName("published_at") val publishedAt: String? = null, val lockedByCoins: Boolean? = null, @SerialName("cdn_path") val cdnPath: String? = null, val metadata: ChapterMetadataDto? = null)
@Serializable data class ChapterMetadataDto(val images: List<String> = emptyList(), val maps: List<DeferredPageMap> = emptyList())
@Serializable data class ChapterDeferredResponse(val success: Boolean = false, val data: ChapterDeferredData? = null)
@Serializable data class ChapterDeferredData(val chapterId: Int = 0, val splitIndex: Int = 0, val images: List<String> = emptyList(), val maps: List<DeferredPageMap> = emptyList())
@Serializable data class DeferredPageMap(val dim: List<Int> = emptyList(), val mode: String = "", val pieces: List<String> = emptyList(), val order: List<Int> = emptyList(), val token: String = "", val method: String = "")
@Serializable data class ProxyPlanResponse(val success: Boolean = false, val data: ProxyPlanData? = null)
@Serializable data class ProxyPlanData(val map: DeferredPageMap? = null)
