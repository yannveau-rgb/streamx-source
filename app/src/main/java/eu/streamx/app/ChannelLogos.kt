package eu.streamx.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

/**
 * Logos propres des chaînes françaises (base communautaire « tv-logos », PNG transparents).
 * Le nom de la chaîne (nettoyé) est comparé à la liste des fichiers ; sans correspondance on garde l'image du fournisseur.
 * La liste des fichiers est enregistrée sur la télé et rafraîchie toutes les deux semaines.
 */
object ChannelLogos {
    private const val BASE = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/france/"
    private const val LIST = "https://api.github.com/repos/tv-logo/tv-logos/contents/countries/france"
    private const val TTL_MS = 14L * 24 * 3600 * 1000

    var version by mutableIntStateOf(0)

    // nom « écrasé » (lettres et chiffres seulement) -> nom de fichier
    @Volatile private var files: Map<String, String> = emptyMap()
    private val cache = HashMap<String, String?>()
    private val diacritics = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val nonAlnum = Regex("[^a-z0-9]+")

    private fun file(c: Context) = File(c.filesDir, "channel_logos.json")

    private fun squash(s: String): String {
        val d = Normalizer.normalize(s.lowercase().replace("+", " plus "), Normalizer.Form.NFD)
        return nonAlnum.replace(diacritics.replace(d, ""), "")
    }

    private fun build(names: List<String>): Map<String, String> {
        val m = HashMap<String, String>()
        for (n in names) {
            if (!n.endsWith("-fr.png")) continue
            val base = n.removeSuffix("-fr.png")
            m.putIfAbsent(squash(base), n)
            if (base.endsWith("-french")) m.putIfAbsent(squash(base.removeSuffix("-french")), n)
        }
        return m
    }

    suspend fun ensure(c: Context) {
        val f = file(c)
        var fresh = false
        withContext(Dispatchers.IO) {
            try {
                if (f.exists()) {
                    val j = JSONObject(f.readText())
                    val arr = j.getJSONArray("f")
                    files = build(List(arr.length()) { arr.getString(it) })
                    fresh = System.currentTimeMillis() - j.optLong("ts") < TTL_MS
                }
            } catch (_: Exception) { f.delete() }
        }
        if (files.isNotEmpty()) { synchronized(cache) { cache.clear() }; version++ }
        if (fresh) return
        val names = withContext(Dispatchers.IO) {
            runCatching {
                val u = URL(LIST).openConnection() as HttpURLConnection
                u.connectTimeout = 8000
                u.readTimeout = 12000
                u.setRequestProperty("Accept", "application/vnd.github+json")
                val arr = JSONArray(u.inputStream.bufferedReader().use { it.readText() })
                List(arr.length()) { arr.getJSONObject(it).getString("name") }.filter { it.endsWith(".png") }
            }.getOrNull()
        } ?: return
        if (names.size < 20) return
        files = build(names)
        withContext(Dispatchers.IO) {
            runCatching { f.writeText(JSONObject().put("ts", System.currentTimeMillis()).put("f", JSONArray(names)).toString()) }
        }
        synchronized(cache) { cache.clear() }
        version++
    }

    /** Logo propre de la chaîne s'il existe, sinon l'image du fournisseur. Lit `version` pour se recomposer à l'arrivée de la liste. */
    fun logo(rawName: String, fallback: String?): String? {
        @Suppress("UNUSED_VARIABLE") val v = version
        val m = files
        if (m.isEmpty()) return fallback
        val hit = synchronized(cache) {
            cache.getOrPut(rawName) { m[squash(cleanChannelName(rawName))]?.let { BASE + it } }
        }
        return hit ?: fallback
    }
}
