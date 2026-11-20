package eu.streamx.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object HistoryVersion {
    var v by mutableIntStateOf(0)
}

/**
 * Un titre commencé : où reprendre la lecture.
 * [url] est enregistrée avec des marqueurs {u} et {p} à la place de l'identifiant et du mot de passe ([maskUrl]).
 * [kind] et [cat] servent à masquer l'entrée si sa catégorie est masquée.
 */
data class Watch(
    val key: String, val title: String, val image: String?, val url: String, val pos: Long, val dur: Long,
    val kind: String = "", val cat: String = ""
)

fun maskUrl(a: Account?, url: String): String {
    if (a == null) return url
    val u = URLEncoder.encode(a.user, "UTF-8")
    val p = URLEncoder.encode(a.pass, "UTF-8")
    return url.replace("/$u/$p/", "/{u}/{p}/")
}

fun unmaskUrl(a: Account, url: String): String {
    val u = URLEncoder.encode(a.user, "UTF-8")
    val p = URLEncoder.encode(a.pass, "UTF-8")
    return url.replace("/{u}/{p}/", "/$u/$p/")
}

object History {
    private const val PREFS = "streamx_history"
    private val listCache = HashMap<String, List<Watch>>()
    private val seenCache = HashMap<String, Set<String>>()

    @Synchronized
    fun load(c: Context): List<Watch> {
        val ns = Profile.ns
        listCache[ns]?.let { return it }
        val loaded = try {
            val arr = JSONArray(c.getSharedPreferences(Profile.prefs(PREFS), Context.MODE_PRIVATE).getString("list", "[]"))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Watch(
                    o.getString("key"), o.getString("title"), o.optString("image").takeIf { s -> s.isNotBlank() },
                    o.getString("url"), o.optLong("pos"), o.optLong("dur"), o.optString("kind"), o.optString("cat")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
        listCache[ns] = loaded
        return loaded
    }

    @Synchronized
    private fun save(c: Context, l: List<Watch>) {
        listCache[Profile.ns] = l
        val arr = JSONArray()
        l.forEach {
            arr.put(
                JSONObject().put("key", it.key).put("title", it.title).put("image", it.image ?: "")
                    .put("url", it.url).put("pos", it.pos).put("dur", it.dur).put("kind", it.kind).put("cat", it.cat)
            )
        }
        c.getSharedPreferences(Profile.prefs(PREFS), Context.MODE_PRIVATE).edit().putString("list", arr.toString()).apply()
        HistoryVersion.v++
    }

    /** Titres vus en entier (plus de 95 %) : ils quittent « Reprendre » mais restent cochés dans la fiche. */
    @Synchronized
    fun seen(c: Context): Set<String> {
        val ns = Profile.ns
        seenCache[ns]?.let { return it }
        val loaded = c.getSharedPreferences(Profile.prefs("streamx_seen"), Context.MODE_PRIVATE).getStringSet("keys", emptySet())?.toSet() ?: emptySet()
        seenCache[ns] = loaded
        return loaded
    }

    @Synchronized
    fun markSeen(c: Context, key: String) {
        val next = HashSet(seen(c) + key)
        seenCache[Profile.ns] = next
        c.getSharedPreferences(Profile.prefs("streamx_seen"), Context.MODE_PRIVATE).edit().putStringSet("keys", next).apply()
    }

    @Synchronized
    fun upsert(c: Context, w: Watch) = save(c, (listOf(w) + load(c).filter { it.key != w.key }).take(12))

    @Synchronized
    fun remove(c: Context, key: String) = save(c, load(c).filter { it.key != key })

    @Synchronized
    fun posFor(c: Context, key: String): Long = load(c).firstOrNull { it.key == key }?.pos ?: 0L

    @Synchronized
    fun clear(c: Context) {
        val ns = Profile.ns
        listCache.remove(ns)
        seenCache.remove(ns)
        c.getSharedPreferences(Profile.prefs(PREFS), Context.MODE_PRIVATE).edit().clear().apply()
        c.getSharedPreferences(Profile.prefs("streamx_seen"), Context.MODE_PRIVATE).edit().clear().apply()
        HistoryVersion.v++
    }
}
