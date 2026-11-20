package eu.streamx.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Une plateforme de streaming (Netflix, Paramount+…) telle qu'affichée en pastille ou en rangée. */
class PlatformInfo(val key: String, val label: String)

/**
 * Qui diffuse quoi en France, d'après TMDB (« watch providers »). Pour chaque plateforme, on récupère ses titres les plus
 * populaires (séries et films), puis on les retrouve dans le catalogue de l'abonnement par leur titre.
 * La liste est enregistrée sur la télé et rafraîchie une fois par semaine ; sans clé TMDB, rien n'est appelé.
 */
object ProviderIndex {
    class Provider(val key: String, val label: String, val ids: List<Int>)

    // Identifiants TMDB pour la France (la plateforme et ses « chaînes » Amazon / Apple regroupées).
    val PROVIDERS = listOf(
        Provider("netflix", "Netflix", listOf(8)),
        Provider("prime", "Prime Video", listOf(119)),
        Provider("disney", "Disney+", listOf(337)),
        Provider("apple", "Apple TV+", listOf(350)),
        Provider("paramount", "Paramount+", listOf(531, 2303, 1853, 582)),
        Provider("max", "HBO Max", listOf(1899, 1825)),
        Provider("canal", "Canal+", listOf(381, 345)),
        Provider("crunchyroll", "Crunchyroll", listOf(283, 1968)),
        Provider("ocs", "OCS", listOf(685))
    )

    var version by mutableIntStateOf(0)

    // plateforme -> (tv | movie) -> titre normalisé -> rang de popularité
    @Volatile private var data: Map<String, Map<String, Map<String, Int>>> = emptyMap()
    val ready: Boolean get() = data.isNotEmpty()

    private const val TTL_MS = 7L * 24 * 3600 * 1000
    private const val MAX_PAGES = 12
    private val normCache = HashMap<Kind, Pair<Int, List<String>>>()

    private fun file(c: Context) = File(c.filesDir, "tmdb_providers.json")

    /** Charge la copie enregistrée, puis la rafraîchit si elle a plus d'une semaine. */
    suspend fun ensure(c: Context) {
        if (!Tmdb.enabled) return
        val f = file(c)
        var fresh = false
        withContext(Dispatchers.IO) {
            try {
                if (f.exists()) {
                    val j = JSONObject(f.readText())
                    data = parse(j.getJSONObject("d"))
                    fresh = System.currentTimeMillis() - j.optLong("ts") < TTL_MS
                }
            } catch (_: Exception) { f.delete() }
        }
        if (data.isNotEmpty()) version++
        if (fresh) return
        val net = withContext(Dispatchers.IO) { runCatching { fetchAll() }.getOrNull() } ?: return
        if (net.isEmpty()) return
        data = net
        withContext(Dispatchers.IO) {
            runCatching {
                val d = JSONObject()
                net.forEach { (k, kinds) ->
                    val o = JSONObject()
                    kinds.forEach { (kind, titles) ->
                        o.put(kind, JSONArray(titles.entries.sortedBy { it.value }.map { it.key }))
                    }
                    d.put(k, o)
                }
                f.writeText(JSONObject().put("ts", System.currentTimeMillis()).put("d", d).toString())
            }
        }
        version++
    }

    private fun parse(d: JSONObject): Map<String, Map<String, Map<String, Int>>> {
        val out = HashMap<String, Map<String, Map<String, Int>>>()
        for (k in d.keys()) {
            val o = d.getJSONObject(k)
            val kinds = HashMap<String, Map<String, Int>>()
            for (kind in o.keys()) {
                val arr = o.getJSONArray(kind)
                val m = LinkedHashMap<String, Int>(arr.length() * 2)
                for (i in 0 until arr.length()) m.putIfAbsent(arr.getString(i), i)
                kinds[kind] = m
            }
            out[k] = kinds
        }
        return out
    }

    private suspend fun fetchAll(): Map<String, Map<String, Map<String, Int>>> = coroutineScope {
        PROVIDERS.map { p ->
            async(Dispatchers.IO) {
                p.key to mapOf("tv" to fetchTitles("tv", p.ids), "movie" to fetchTitles("movie", p.ids))
            }
        }.awaitAll().toMap()
    }

    private fun fetchTitles(type: String, ids: List<Int>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        var page = 1
        var total = 1
        while (page <= minOf(MAX_PAGES, total)) {
            val url = "https://api.themoviedb.org/3/discover/$type?language=fr-FR&sort_by=popularity.desc&watch_region=FR" +
                "&with_watch_providers=" + ids.joinToString("%7C") + "&with_watch_monetization_types=flatrate&page=$page" +
                "&api_key=" + BuildConfig.TMDB_KEY
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 12000
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            total = j.optInt("total_pages", 1)
            val arr = j.optJSONArray("results") ?: break
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                for (field in listOf(if (type == "tv") "name" else "title", if (type == "tv") "original_name" else "original_title")) {
                    val t = o.optString(field)
                    if (t.isNotBlank()) out.putIfAbsent(normalize(cleanTitle(t)), out.size)
                }
            }
            page++
        }
        return out
    }

    /**
     * Les mieux notés parmi les titres populaires sur au moins une plateforme : écarte les titres confidentiels notés 10 par deux personnes.
     * Renvoie null si les données TMDB manquent ou donnent trop peu de titres.
     */
    fun popularTop(kind: Kind, hidden: Set<String>, n: Int): List<Entry>? {
        val d = data
        if (d.isEmpty() || kind == Kind.LIVE) return null
        val type = if (kind == Kind.SERIES) "tv" else "movie"
        val known = HashSet<String>()
        d.values.forEach { m -> m[type]?.keys?.let { known.addAll(it) } }
        val entries = Catalog.list(kind)
        val norm = normalized(kind)
        if (entries.size != norm.size) return null
        val out = ArrayList<Entry>()
        for (i in entries.indices) {
            val e = entries[i]
            if (e.cat in hidden || e.image == null || e.rating <= 0f || e.rating > Catalog.MAX_TRUSTED_RATING || !Catalog.recentEnough(e)) continue
            if (norm[i] in known) out.add(e)
        }
        val list = out.sortedWith(compareByDescending<Entry> { it.rating }.thenByDescending { it.added })
            .distinctBy { normalize(cleanTitle(it.name)) }.take(n)
        return if (list.size >= 10) list else null
    }

    /** Titres normalisés du catalogue (même ordre que Catalog.list), recalculés seulement quand le catalogue change. */
    fun normalizedTitles(kind: Kind): List<String> = normalized(kind)

    private fun normalized(kind: Kind): List<String> {
        val cv = Catalog.version
        synchronized(normCache) { normCache[kind]?.takeIf { it.first == cv }?.let { return it.second } }
        val list = Catalog.list(kind).map { normalize(cleanTitle(it.name)) }
        synchronized(normCache) { normCache[kind] = cv to list }
        return list
    }

    /**
     * Pour un catalogue (films ou séries) : les titres de chaque plateforme présents dans l'abonnement, du plus populaire au moins
     * populaire. `exclude` = plateformes déjà couvertes par une vraie catégorie du fournisseur.
     */
    fun rows(kind: Kind, hidden: Set<String>, exclude: Set<String>): List<Pair<PlatformInfo, List<Entry>>> {
        val d = data
        if (d.isEmpty() || kind == Kind.LIVE) return emptyList()
        val type = if (kind == Kind.SERIES) "tv" else "movie"
        val entries = Catalog.list(kind)
        val norm = normalized(kind)
        if (entries.size != norm.size) return emptyList()
        return PROVIDERS.filter { it.key !in exclude }.mapNotNull { p ->
            val ranks = d[p.key]?.get(type) ?: return@mapNotNull null
            val hits = ArrayList<Pair<Int, Entry>>()
            for (i in entries.indices) {
                val e = entries[i]
                if (e.cat in hidden || e.image == null) continue
                val r = ranks[norm[i]] ?: continue
                hits.add(r to e)
            }
            val list = hits.sortedBy { it.first }.map { it.second }.distinctBy { normalize(cleanTitle(it.name)) }.take(60)
            if (list.size >= 3) PlatformInfo(p.key, p.label) to list else null
        }
    }
}
