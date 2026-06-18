package eu.kanade.tachiyomi.extension.ar.procomic

import android.util.Base64
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object ProComicCrypto {
    private val json = Json { ignoreUnknownKeys = true }

    fun String.toAbsoluteUrl(cdnBase: String): String {
        return when {
            this.startsWith("http") -> this
            this.startsWith("eyJ2IjoxLCJpdiI6I") -> "$cdnBase/i/$this"
            this.startsWith("/") -> "$cdnBase$this"
            else -> "$cdnBase/$this"
        }
    }

    fun String.decryptUrl(): String {
        return try {
            if (!this.startsWith("https://") && this.contains("eyJ2Ijox")) {
                val outerJson = json.decodeFromString<EncryptedPayload>(this)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                
                val keyBytes = MessageDigest.getInstance("MD5").digest("procomic-secret-salt-key".toByteArray())
                val secretKey = SecretKeySpec(keyBytes, "AES")
                val ivBytes = Base64.decode(outerJson.iv, Base64.DEFAULT)
                val ivSpec = IvParameterSpec(ivBytes)
                
                cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
                val encryptedBytes = Base64.decode(outerJson.data, Base64.DEFAULT)
                val decryptedBytes = cipher.doFinal(encryptedBytes)
                
                String(decryptedBytes)
            } else {
                this
            }
        } catch (e: Exception) {
            this
        }
    }
}
