package eu.kanade.tachiyomi.extension.ar.procomic

import eu.kanade.tachiyomi.source.model.Filter

open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
    Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
    fun toUriPart() = vals[state].second
}

class TypeFilter : UriPartFilter(
    "نوع العمل",
    arrayOf(
        Pair("الكل", ""),
        Pair("مانجا (Manga)", "manga"),
        Pair("مانهوا (Manhwa)", "manhwa"),
        Pair("مانها (Manhua)", "manhua"),
        Pair("كوميكس (Comic)", "comic")
    )
)

class StatusFilter : UriPartFilter(
    "حالة العمل",
    arrayOf(
        Pair("الكل", ""),
        Pair("مستمر", "ongoing"),
        Pair("مكتمل", "completed"),
        Pair("متوقف", "hiatus"),
        Pair("ملغى", "canceled")
    )
)

class SortFilter : UriPartFilter(
    "الترتيب",
    arrayOf(
        Pair("آخر التحديثات", "desc"),
        Pair("الأقدم", "asc"),
        Pair("الأكثر شعبية", "popular"),
        Pair("الأعلى تقييماً", "rating")
    )
)

class GenreFilter : UriPartFilter(
    "التصنيف (Genre)",
    arrayOf(
        Pair("الكل", ""),
        Pair("أكشن (Action)", "1"),
        Pair("للكبار (Adult)", "2"),
        Pair("مغامرة (Adventure)", "3"),
        Pair("كوميديا (Comedy)", "4"),
        Pair("دراما (Drama)", "6"),
        Pair("إتشي (Ecchi)", "7")
    )
)

class TagFilter : UriPartFilter(
    "الوسم (Tag)",
    arrayOf(
        Pair("الكل", ""),
        Pair("أطفال متروكون", "1"),
        Pair("سرقة القدرات", "2"),
        Pair("أكاديمية", "5"),
        Pair("نمو متسارع", "6"),
        Pair("مقتبس من رواية", "18"),
        Pair("بطل غير اجتماعي", "42")
    )
)
