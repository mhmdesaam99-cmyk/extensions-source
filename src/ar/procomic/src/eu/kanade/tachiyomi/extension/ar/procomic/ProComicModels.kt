package eu.kanade.tachiyomi.extension.ar.procomic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EncryptedPayload(
    val v: Int,
    val iv: String,
    val tag: String = "",
    val data: String
)

@Serializable
data class FilterResponse(val data: List<MangaDto> = emptyList())

@Serializable
data class MangaDto(
    val id: Int,
    val title: String,
    val slug: String,
    val type: String,
    val cover: String? = null
)

@Serializable
data class MangaDetailResponse(val data: MangaDetailDto? = null)

@Serializable
data class MangaDetailDto(
    val id: Int,
    val title: String,
    val summary: String? = null,
    val cover: String? = null,
    val status: String? = null
)

@Serializable
data class ChaptersResponse(
    val data: List<ChapterDto> = emptyList(),
    val total: Int = 0
)

@Serializable
data class ChapterDto(
    val id: Int = 0,
    @SerialName("chapter_number") val chapterNumber: String = "0",
    val title: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val lockedByCoins: Boolean? = null,
    @SerialName("cdn_path") val cdnPath: String? = null,
    val metadata: ChapterMetadataDto? = null
)

@Serializable
data class ChapterMetadataDto(
    val images: List<String> = emptyList(),
    val maps: List<DeferredPageMap> = emptyList()
)

@Serializable
data class ChapterDeferredResponse(
    val success: Boolean = false,
    val data: ChapterDeferredData? = null
)

@Serializable
data class ChapterDeferredData(
    val chapterId: Int = 0,
    val splitIndex: Int = 0,
    val images: List<String> = emptyList(),
    val maps: List<DeferredPageMap> = emptyList()
)

@Serializable
data class ImageRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
)

@Serializable
data class DeferredPageMap(
    val dim: List<Int> = emptyList(),
    val mode: String = "",
    val pieces: List<String> = emptyList(),
    val order: List<Int> = emptyList(),
    val token: String = "",
    val method: String = "",
    val rects: List<ImageRect>? = null
)

@Serializable
data class InitialProxyResponse(
    val success: Boolean,
    val data: ProxyData? = null
)

@Serializable
data class ProxyData(
    val map: DeferredPageMap? = null
)

@Serializable
data class ScrambledMap(
    val dim: List<Int> = emptyList(),
    val mode: String = "",
    val pieces: List<String> = emptyList(),
    val order: List<Int> = emptyList(),
    val token: String = "",
    val rects: List<ImageRect>? = null
)

@Serializable
data class InterceptorResponse(
    val success: Boolean,
    val pages: List<ScrambledMap> = emptyList()
)
