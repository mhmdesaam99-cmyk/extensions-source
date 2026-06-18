package eu.kanade.tachiyomi.extension.ar.procomic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Base64
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.decoder.ImageDecoder
import java.io.ByteArrayOutputStream

object ProComicImageReconstructor {

    fun reconstructPage(map: ScrambledMap, baseClient: OkHttpClient, requestHeaders: Headers): ByteArray? {
        if (map.pieces.isEmpty()) return null

        val bitmaps = arrayOfNulls<Bitmap>(map.pieces.size)

        for (targetIdx in 0 until map.pieces.size) {
            val srcIdx = if (map.order.size == map.pieces.size) map.order[targetIdx] else targetIdx
            val basePieceUrl = map.pieces.getOrNull(srcIdx) ?: continue
            
            val pieceUrl = if (map.token.isNotEmpty() && !basePieceUrl.contains("token=")) {
                val separator = if (basePieceUrl.contains("?")) "&" else "?"
                "$basePieceUrl${separator}token=${map.token}"
            } else {
                basePieceUrl
            }

            try {
                val reqHeaders = requestHeaders.newBuilder()
                    .set("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    .build()
                val req = Request.Builder().url(pieceUrl).headers(reqHeaders).get().build()
                val res = baseClient.newCall(req).execute()
                if (res.code == 200) {
                    var bodyBytes = res.body.bytes()
                    val bodyStr = String(bodyBytes, 0, minOf(bodyBytes.size, 100)).trim()
                    if (bodyStr.startsWith("data:image/")) {
                        val base64Str = bodyStr.substringAfter("base64,")
                        bodyBytes = Base64.decode(base64Str, Base64.DEFAULT)
                    }
                    
                    val bmp = if (ImageDecoder.isSupportedAndEnabled()) {
                        ImageDecoder.decode(bodyBytes)
                    } else {
                        BitmapFactory.decodeByteArray(bodyBytes, 0, bodyBytes.size)
                    }
                    bitmaps[targetIdx] = bmp
                }
                res.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return try {
            if (!map.rects.isNullOrEmpty()) {
                val totalW = map.dim.getOrNull(0) ?: 800
                val totalH = map.dim.getOrNull(1) ?: 5000
                val result = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(result)

                map.rects.forEachIndexed { index, rect ->
                    val bmp = bitmaps.getOrNull(index)
                    if (bmp != null) {
                        canvas.drawBitmap(bmp, rect.left.toFloat(), rect.top.toFloat(), null)
                        bmp.recycle()
                    }
                }

                val out = ByteArrayOutputStream()
                result.compress(Bitmap.CompressFormat.JPEG, 85, out)
                result.recycle()
                out.toByteArray()
            } else {
                val validBitmaps = bitmaps.filterNotNull()
                if (validBitmaps.isEmpty()) return null

                val pieceW = validBitmaps[0].width
                val pieceH = validBitmaps[0].height

                val (cols, rows) = parseMode(map.mode, validBitmaps.size)
                val totalW = pieceW * cols
                val totalH = pieceH * rows

                val result = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(result)

                for (idx in bitmaps.indices) {
                    val bmp = bitmaps[idx] ?: continue
                    val c = idx % cols
                    val r = idx / cols
                    canvas.drawBitmap(bmp, (c * pieceW).toFloat(), (r * pieceH).toFloat(), null)
                    bmp.recycle()
                }

                val out = ByteArrayOutputStream()
                result.compress(Bitmap.CompressFormat.JPEG, 85, out)
                result.recycle()
                out.toByteArray()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseMode(mode: String, totalPieces: Int): Pair<Int, Int> {
        if (mode.startsWith("grid_")) {
            val parts = mode.removePrefix("grid_").split("x")
            val cols = parts.getOrNull(0)?.toIntOrNull() ?: 1
            val rows = parts.getOrNull(1)?.toIntOrNull() ?: 1
            return Pair(cols, rows)
        }
        if (mode.startsWith("vertical_")) {
            val rows = mode.removePrefix("vertical_").toIntOrNull() ?: totalPieces
            return Pair(1, rows)
        }
        return Pair(1, totalPieces)
    }
}
