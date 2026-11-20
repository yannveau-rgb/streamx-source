package eu.streamx.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Suivi des séries en favoris : à chaque vérification, on compare le nombre de saisons et d'épisodes avec la dernière fois.
 * Une nouveauté devient une notification dans la cloche. La première vérification d'une série ne fait que mémoriser l'état.
 */
object SeriesWatch {
    class Note(val seriesId: String, val name: String, val image: String?, val cat: String, val season: Int, val newEpisodes: Int, val ts: Long) {
        val id: String get() = "ep:$seriesId:$ts"
        fun toItem() = Item(id = seriesId, name = name, image = image, ext = null, cat = cat, rating = 0f, kind = Kind.SERIES)
    }

    var version by mutableIntStateOf(0)
    private const val PREFS = "streamx_serieswatch"
    private const val MAX_NOTES = 20
    private const val KEEP_MS = 30L * 24 * 3600 * 1000
    private const val MIN_GAP_MS = 30L * 60 * 1000
    @Volatile private var lastCheck = 0L

    private fun sp(c: Context) = c.getSharedPreferences(Profile.prefs(PREFS), Context.MODE_PRIVATE)

    fun notes(c: Context): List<Note> = try {
        val a = JSONArray(sp(c).getString("notes", "[]"))
        List(a.length()) {
            val o = a.getJSONObject(it)
            Note(o.getString("id"), o.optString("name"), o.optString("image").ifEmpty { null }, o.optString("cat"), o.optInt("season"), o.optInt("eps"), o.optLong("ts"))
        }
    } catch (_: Exception) { emptyList() }

    /** Compare chaque série en favoris à ce qui était connu ; renvoie le nombre de nouvelles notifications. */
    suspend fun check(c: Context, account: Account, force: Boolean = false): Int {
        if (account.isM3u) return 0
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < MIN_GAP_MS) return 0
        lastCheck = now
        val favs = Favorites.load(c).filter { it.kind == Kind.SERIES.key }
        if (favs.isEmpty()) return 0
        val base = try { JSONObject(sp(c).getString("base", "{}")) } catch (_: Exception) { JSONObject() }
        val notes = notes(c).filter { now - it.ts < KEEP_MS }.toMutableList()
        var added = 0
        for (f in favs) {
            EpisodesCache.remove(f.id)
            val eps = XtreamApi.episodes(account, f.id) ?: continue
            val total = eps.values.sumOf { it.size }
            val seasons = eps.keys.maxOrNull() ?: 0
            if (total == 0) continue
            val old = base.optString(f.id)
            if (old.isNotEmpty()) {
                val parts = old.split(",")
                val oldSeasons = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val oldTotal = parts.getOrNull(1)?.toIntOrNull() ?: 0
                if (total > oldTotal || seasons > oldSeasons) {
                    notes.add(0, Note(f.id, f.name, f.image, f.cat, if (seasons > oldSeasons) seasons else 0, (total - oldTotal).coerceAtLeast(1), now + added))
                    added++
                }
            }
            base.put(f.id, "$seasons,$total")
            delay(250)
        }
        val a = JSONArray()
        notes.take(MAX_NOTES).forEach {
            a.put(JSONObject().put("id", it.seriesId).put("name", it.name).put("image", it.image ?: "").put("cat", it.cat)
                .put("season", it.season).put("eps", it.newEpisodes).put("ts", it.ts))
        }
        sp(c).edit().putString("base", base.toString()).putString("notes", a.toString()).apply()
        if (added > 0) { version++; NotifState.tick++ }
        return added
    }
}
