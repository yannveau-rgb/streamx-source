package eu.streamx.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Vraies tendances de la semaine (The Movie Database). Sans clé dans l'application, tout reste silencieux :
 * l'accueil retombe alors sur les derniers ajouts de l'abonnement.
 */
object Tmdb {
    class Trend(val title: String, val original: String)

    val enabled: Boolean get() = BuildConfig.TMDB_KEY.isNotBlank()

    private val cache = HashMap<Kind, Pair<Long, List<Trend>>>()

    suspend fun trending(kind: Kind): List<Trend> {
        if (!enabled || kind == Kind.LIVE) return emptyList()
        synchronized(this) {
            cache[kind]?.let { (at, l) -> if (System.currentTimeMillis() - at < 6L * 3600_000L) return l }
        }
        val list = withContext(Dispatchers.IO) {
            try {
                val type = if (kind == Kind.SERIES) "tv" else "movie"
                val c = URL("https://api.themoviedb.org/3/trending/$type/week?language=fr-FR&api_key=" + BuildConfig.TMDB_KEY)
                    .openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 12000
                val arr = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONArray("results")
                if (arr == null) emptyList() else (0 until arr.length()).mapNotNull {
                    val o = arr.optJSONObject(it) ?: return@mapNotNull null
                    val t = o.optString(if (kind == Kind.SERIES) "name" else "title")
                    if (t.isBlank()) null else Trend(t, o.optString(if (kind == Kind.SERIES) "original_name" else "original_title"))
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        if (list.isNotEmpty()) synchronized(this) { cache[kind] = System.currentTimeMillis() to list }
        return list
    }

    private val trailers = HashMap<String, String>()

    private fun getJson(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 12000
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    }

    /**
     * Clé YouTube de la bande-annonce d'un film ou d'une série : recherche du titre (et de l'année) sur TMDB, puis ses vidéos.
     * Préfère une bande-annonce (sinon un teaser), en français puis en anglais, officielle d'abord. Null si rien n'est trouvé.
     */
    suspend fun trailerKey(kind: Kind, rawName: String): String? {
        if (!enabled || kind == Kind.LIVE) return null
        val title = cleanTitle(rawName)
        val year = yearOf(rawName)
        val ck = kind.key + "|" + normalize(title) + "|" + (year ?: "")
        synchronized(this) { trailers[ck]?.let { return it } }
        val found = withContext(Dispatchers.IO) {
            try {
                val type = if (kind == Kind.SERIES) "tv" else "movie"
                val k = BuildConfig.TMDB_KEY
                val q = java.net.URLEncoder.encode(title, "UTF-8")
                fun search(withYear: Boolean): Int? {
                    val y = if (withYear && year != null) (if (kind == Kind.SERIES) "&first_air_date_year=$year" else "&year=$year") else ""
                    val r = getJson("https://api.themoviedb.org/3/search/$type?query=$q$y&language=fr-FR&api_key=$k").optJSONArray("results")
                    return r?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
                }
                val id = search(true) ?: (if (year != null) search(false) else null) ?: return@withContext null
                val vids = getJson("https://api.themoviedb.org/3/$type/$id/videos?language=fr-FR&include_video_language=fr,en&api_key=$k").optJSONArray("results")
                    ?: return@withContext null
                (0 until vids.length()).mapNotNull { vids.optJSONObject(it) }
                    .filter { it.optString("site") == "YouTube" && it.optString("key").isNotBlank() && it.optString("type") in listOf("Trailer", "Teaser") }
                    .minByOrNull { v ->
                        (if (v.optString("type") == "Trailer") 0 else 100) +
                            (if (v.optString("iso_639_1") == "fr") 0 else 10) +
                            (if (v.optBoolean("official")) 0 else 1)
                    }?.optString("key")
            } catch (e: Exception) {
                null
            }
        }
        if (found != null) synchronized(this) { trailers[ck] = found }
        return found
    }
}

/** Ouvre la bande-annonce dans l'application YouTube de l'appareil (sinon dans le navigateur). */
fun openTrailer(ctx: android.content.Context, key: String) {
    val flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
    val watch = "https://www.youtube.com/watch?v=$key"
    // Le paquet est imposé : sans lui, YouTube TV s'ouvrait sur son accueil et la vidéo se perdait (vérifié sur la télé).
    val attempts = listOf("com.google.android.youtube.tv" to watch, "com.google.android.youtube" to watch, null to watch, null to "vnd.youtube:$key")
    for ((pkg, u) in attempts) {
        try {
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(flags)
            if (pkg != null) i.setPackage(pkg)
            ctx.startActivity(i)
            return
        } catch (_: Exception) {
        }
    }
    android.widget.Toast.makeText(ctx, ctx.getString(R.string.trailer_error), android.widget.Toast.LENGTH_LONG).show()
}

/** Retrouve, dans le catalogue de l'abonnement, les titres de la liste de tendances (dans l'ordre de popularité). */
fun Catalog.matchTrends(k: Kind, trends: List<Tmdb.Trend>, hidden: Set<String>, max: Int = 20): List<Entry> {
    if (trends.isEmpty()) return emptyList()
    val wanted = HashMap<String, Int>()
    trends.forEachIndexed { i, t ->
        wanted.putIfAbsent(normalize(t.title), i)
        if (t.original.isNotBlank()) wanted.putIfAbsent(normalize(t.original), i)
    }
    val best = HashMap<Int, Entry>()
    for (e in list(k)) {
        if (e.cat in hidden || e.image == null) continue
        val n = cleanTitle(e.name)
        if (n.length > 70) continue
        val key = if (n.any { it.code > 127 }) normalize(n) else n.lowercase()
        val rank = wanted[key] ?: continue
        val cur = best[rank]
        if (cur == null || e.added > cur.added) best[rank] = e
    }
    return best.entries.sortedBy { it.key }.map { it.value }.take(max)
}
