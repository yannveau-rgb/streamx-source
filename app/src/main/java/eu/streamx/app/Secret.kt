package eu.streamx.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chiffre les mots de passe enregistrés avec une clé qui reste dans le coffre matériel d'Android (Keystore) :
 * lire le fichier de préférences de l'application ne suffit plus pour récupérer le mot de passe.
 */
object SecretBox {
    private const val ALIAS = "streamx_secret_v1"
    private const val PREFIX = "enc1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Renvoie le texte chiffré, ou null si le coffre d'Android est indisponible sur cet appareil. */
    fun encrypt(plain: String): String? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        PREFIX + Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    fun isEncrypted(s: String) = s.startsWith(PREFIX)

    /** Déchiffre. Un ancien texte non chiffré est rendu tel quel ; null si le déchiffrement échoue. */
    fun decrypt(s: String): String? {
        if (!isEncrypted(s)) return s
        return try {
            val raw = Base64.decode(s.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}

/** Masque identifiants et mots de passe dans un texte destiné à être lu ou copié (traces d'incident, infos de dépannage). */
object Redact {
    private val query = Regex("(?i)(password|username)=[^&\\s\"']+")
    private val path = Regex("(/(?:live|movie|series)/)[^/\\s\"']+/[^/\\s\"']+/")

    fun text(s: String, secrets: List<String> = emptyList()): String {
        var out = path.replace(query.replace(s) { it.groupValues[1] + "=***" }) { it.groupValues[1] + "***/***/" }
        for (x in secrets) if (x.length >= 3) out = out.replace(x, "***")
        return out
    }
}
