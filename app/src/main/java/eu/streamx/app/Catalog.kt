package eu.streamx.app

import android.util.JsonReader
import android.util.JsonToken
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.Normalizer

data class Entry(
    val kind: Kind,
    val id: String,
    val name: String,
    val cat: String,
    val image: String?,
    val ext: String?,
    val added: Long,
    val rating: Float
)

fun Entry.toItem() = Item(id, name, image, ext, cat, rating, kind)

/** Incrémenté à chaque changement de catégories masquées : l'accueil et la recherche se recalculent. */
object HiddenVersion {
    var v by mutableIntStateOf(0)
}

private val prefixRe = Regex("^[A-Z0-9+]{1,6}\\s*[-|]\\s+")
private val yearSuffixRe = Regex("\\s*\\(\\d{4}\\)\\s*(\\([A-Za-z]{2,3}\\))?\\s*$")
private val yearRe = Regex("\\((\\d{4})\\)")

/** « TOP - Verity (2026) » devient « Verity ». */
fun cleanTitle(n: String): String {
    val s = yearSuffixRe.replace(prefixRe.replace(n.trim(), ""), "").trim()
    return s.ifBlank { n.trim() }
}

fun yearOf(n: String): String? = yearRe.find(n)?.groupValues?.get(1)

private val diacriticsRe = Regex("\\p{M}+")

fun normalize(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(diacriticsRe, "").lowercase()

/**
 * Copie en mémoire du catalogue (films, séries, chaînes) et des catégories, lue en flux et mise en cache binaire.
 * Sert à l'accueil, aux pages Films/Séries/Direct (affichage instantané sans requête par rangée), aux compteurs et à la recherche.
 */
object Catalog {
    var version by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var failed by mutableStateOf(false)
    private val lists = HashMap<Kind, List<Entry>>()
    private val perCat = HashMap<Kind, Map<String, Int>>()
    private val byCat = HashMap<Kind, Map<String, List<Item>>>()
    // Classements prêts dès le chargement du catalogue : les rangées « Ajoutés récemment » et « Les mieux notés » s'affichent aussitôt.
    private val recentIdx = HashMap<Kind, List<Entry>>()
    private val ratedIdx = HashMap<Kind, List<Entry>>()
    private val cats = HashMap<Kind, List<Category>>()
    private var owner: String? = null

    @Synchronized fun list(k: Kind): List<Entry> = lists[k] ?: emptyList()
    @Synchronized fun loaded(k: Kind): Boolean = lists.containsKey(k)
    @Synchronized fun categoriesOf(k: Kind): List<Category>? = cats[k]

    /** Renvoie instantanément les éléments d'une catégorie depuis l'index en mémoire (null si le catalogue n'est pas encore prêt). */
    @Synchronized fun itemsInCategory(k: Kind, catId: String): List<Item>? {
        val m = byCat[k] ?: return null
        return m[catId] ?: emptyList()
    }

    @Synchronized fun clear() {
        lists.clear()
        perCat.clear()
        byCat.clear(); recentIdx.clear(); ratedIdx.clear()
        cats.clear()
        owner = null
        version++
    }

    @Synchronized fun visibleCount(k: Kind, hidden: Set<String>): Int? {
        val l = lists[k] ?: return null
        val m = perCat[k] ?: return null
        return l.size - hidden.sumOf { m[it] ?: 0 }
    }

    fun newest(k: Kind, hidden: Set<String>, n: Int): List<Entry> {
        val idx = synchronized(this) { recentIdx[k] }
        if (idx != null) return idx.filter { it.cat !in hidden }.take(n)
        return newestSlow(k, hidden, n)
    }

    private fun newestSlow(k: Kind, hidden: Set<String>, n: Int): List<Entry> =
        list(k).asSequence().filter { it.cat !in hidden && it.image != null }.sortedByDescending { it.added }
            .distinctBy { it.id }.take(n).toList()

    /** Les mieux notés (avec affiche), parmi les catégories visibles ; à note égale, les plus récents d'abord. */
    fun topRated(k: Kind, hidden: Set<String>, n: Int): List<Entry> {
        val idx = synchronized(this) { ratedIdx[k] }
        if (idx != null) return idx.filter { it.cat !in hidden }.take(n)
        return topRatedSlow(k, hidden, n)
    }

    /** Au-dessus, la note vient presque toujours d'un titre confidentiel noté par une ou deux personnes. */
    const val MAX_TRUSTED_RATING = 9.3f

    /** Ancienneté maximale (en années) d'un titre dans les rangées « Les mieux notés » : les vieux classiques les écrasaient. */
    const val TOP_MAX_AGE = 15

    /** Vrai si l'année du titre (lue dans son nom, « (2019) ») est récente ; sans année, le titre est conservé. */
    fun recentEnough(e: Entry): Boolean {
        val y = yearOf(e.name)?.toIntOrNull() ?: return true
        return y >= java.time.Year.now().value - TOP_MAX_AGE
    }

    private fun topRatedSlow(k: Kind, hidden: Set<String>, n: Int): List<Entry> =
        list(k).asSequence().filter { it.cat !in hidden && it.image != null && it.rating > 0f && it.rating <= MAX_TRUSTED_RATING && recentEnough(it) }
            .sortedWith(compareByDescending<Entry> { it.rating }.thenByDescending { it.added })
            .distinctBy { cleanTitle(it.name) }.take(n).toList()

    private fun cacheFile(dir: File, a: Account, k: Kind) =
        File(dir, "catalog_" + k.key + "_" + (a.server + "|" + a.user).hashCode().toString(16) + ".bin")

    private fun catsCacheFile(dir: File, a: Account, k: Kind) =
        File(dir, "cats_" + k.key + "_" + (a.server + "|" + a.user).hashCode().toString(16) + ".bin")

    /** Efface les copies du catalogue sur la télé (déconnexion). */
    fun wipe(dir: File) {
        dir.listFiles { f -> f.name.startsWith("catalog_") || f.name.startsWith("cats_") }?.forEach { it.delete() }
    }

    private fun publish(k: Kind, list: List<Entry>) {
        val counts = HashMap<String, Int>(256)
        val grouped = HashMap<String, ArrayList<Item>>(256)
        val seenIds = HashSet<String>(minOf(list.size, 65536))
        // Avant : 80 par catégorie, donc des chaînes injoignables (FRANCE HD en a 132). Les rangées ne composent que ce qui est visible.
        val limit = if (k == Kind.LIVE) 600 else 200
        for (e in list) {
            counts[e.cat] = (counts[e.cat] ?: 0) + 1
            val bucket = grouped.getOrPut(e.cat) { ArrayList() }
            if (bucket.size < limit && seenIds.add(e.id)) {
                bucket.add(e.toItem())
            }
        }
        val withImage = list.filter { it.image != null }
        val recentSorted = withImage.sortedByDescending { it.added }.distinctBy { it.id }.take(500)
        val ratedSorted = withImage.filter { it.rating > 0f && it.rating <= MAX_TRUSTED_RATING && recentEnough(it) }
            .sortedWith(compareByDescending<Entry> { it.rating }.thenByDescending { it.added })
            .distinctBy { cleanTitle(it.name) }.take(500)
        synchronized(this) {
            lists[k] = list
            perCat[k] = counts
            byCat[k] = grouped
            recentIdx[k] = recentSorted
            ratedIdx[k] = ratedSorted
        }
        version++
    }

    private fun publishCats(k: Kind, list: List<Category>) {
        synchronized(this) {
            cats[k] = list
        }
        version++
    }

    /** Charge les catégories depuis la mémoire, le disque ou le réseau. */
    suspend fun ensureCategories(a: Account, dir: File, k: Kind): List<Category>? {
        categoriesOf(k)?.let { return it }
        if (a.isM3u) {
            if (k == Kind.LIVE) {
                loadM3uCatalog(a, dir)
                return categoriesOf(Kind.LIVE)
            }
            return emptyList()
        }
        val cf = catsCacheFile(dir, a, k)
        if (cf.exists()) {
            val disk = withContext(Dispatchers.IO) { try { readCatsBin(cf) } catch (_: Exception) { cf.delete(); null } }
            if (disk != null) {
                publishCats(k, disk)
                return disk
            }
        }
        val net = XtreamApi.categories(a, k.action)
        if (net != null) {
            withContext(Dispatchers.IO) { runCatching { writeCatsBin(cf, net) } }
            publishCats(k, net)
        }
        return net
    }

    /**
     * Démarrage instantané : on affiche d'abord la copie enregistrée sur la télé, puis on la remplace par une version fraîche
     * (téléchargée au plus une fois toutes les 6 heures).
     */
    suspend fun load(a: Account, dir: File) {
        val key = a.server + "|" + a.user
        synchronized(this) {
            if (owner != key) {
                lists.clear()
                perCat.clear()
                byCat.clear(); recentIdx.clear(); ratedIdx.clear()
                cats.clear()
                owner = key
            }
        }
        loading = true
        failed = false
        dir.listFiles { x ->
            (x.name.startsWith("catalog_") || x.name.startsWith("cats_")) &&
                (x.name.endsWith(".json") || x.name.endsWith(".tmp") || x.name.endsWith(".part"))
        }?.forEach { it.delete() }
        try {
            if (a.isM3u) {
                loadM3uCatalog(a, dir)
                return
            }
            // 1. Chargement immédiat des caches disque (catégories + entrées) pour un affichage instantané
            for (k in listOf(Kind.VOD, Kind.SERIES, Kind.LIVE)) {
                val cf = catsCacheFile(dir, a, k)
                if (categoriesOf(k) == null && cf.exists()) {
                    val cDisk = withContext(Dispatchers.IO) { try { readCatsBin(cf) } catch (_: Exception) { cf.delete(); null } }
                    if (cDisk != null) publishCats(k, cDisk)
                }
                val f = cacheFile(dir, a, k)
                if (!loaded(k) && f.exists()) {
                    val cached = withContext(Dispatchers.IO) { try { readBin(f) } catch (_: Exception) { f.delete(); null } }
                    if (cached != null) publish(k, cached)
                }
            }
            // 2. Rafraîchissement réseau si nécessaire (plus de 6 heures ou premier lancement)
            for (k in listOf(Kind.VOD, Kind.SERIES, Kind.LIVE)) {
                val cf = catsCacheFile(dir, a, k)
                val catsFresh = cf.exists() && System.currentTimeMillis() - cf.lastModified() < 6L * 3600_000L
                if (!catsFresh || categoriesOf(k) == null) {
                    val netCats = XtreamApi.categories(a, k.action)
                    if (netCats != null) {
                        withContext(Dispatchers.IO) { runCatching { writeCatsBin(cf, netCats) } }
                        publishCats(k, netCats)
                    }
                }
                val f = cacheFile(dir, a, k)
                val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < 6L * 3600_000L
                if (fresh && loaded(k)) continue
                val ok = withContext(Dispatchers.IO) {
                    try {
                        val tmp = File(dir, f.name + ".json.tmp")
                        download(a, k, tmp)
                        val list = parse(tmp, k)
                        tmp.delete()
                        writeBin(f, list)
                        list
                    } catch (e: Exception) { null }
                }
                if (ok != null) publish(k, ok) else if (!loaded(k)) failed = true
            }
        } finally {
            loading = false
        }
    }

    private suspend fun loadM3uCatalog(a: Account, dir: File) {
        val cf = cacheFile(dir, a, Kind.LIVE)
        val catsF = catsCacheFile(dir, a, Kind.LIVE)
        if (categoriesOf(Kind.LIVE) == null && catsF.exists()) {
            val cDisk = withContext(Dispatchers.IO) { try { readCatsBin(catsF) } catch (_: Exception) { catsF.delete(); null } }
            if (cDisk != null) publishCats(Kind.LIVE, cDisk)
        }
        if (!loaded(Kind.LIVE) && cf.exists()) {
            val cached = withContext(Dispatchers.IO) { try { readBin(cf) } catch (_: Exception) { cf.delete(); null } }
            if (cached != null) publish(Kind.LIVE, cached)
        }
        val fresh = cf.exists() && System.currentTimeMillis() - cf.lastModified() < 6L * 3600_000L
        if (fresh && loaded(Kind.LIVE)) return

        val channels = parseM3u(a.m3uUrl)
        if (channels != null && channels.isNotEmpty()) {
            val liveGroups = channels.map { it.group.ifBlank { "Général" } }.distinct()
            val catList = liveGroups.map { Category(it, it) }
            val entries = channels.mapIndexed { idx, ch ->
                Entry(
                    kind = Kind.LIVE,
                    id = ch.url,
                    name = ch.name,
                    cat = ch.group.ifBlank { "Général" },
                    image = ch.logo,
                    ext = "m3u8",
                    added = System.currentTimeMillis() - idx,
                    rating = 0f
                )
            }
            withContext(Dispatchers.IO) {
                runCatching { writeCatsBin(catsF, catList) }
                runCatching { writeBin(cf, entries) }
            }
            publishCats(Kind.LIVE, catList)
            publish(Kind.LIVE, entries)
            publishCats(Kind.VOD, emptyList())
            publishCats(Kind.SERIES, emptyList())
            publish(Kind.VOD, emptyList())
            publish(Kind.SERIES, emptyList())
        } else if (!loaded(Kind.LIVE)) {
            failed = true
        }
    }

    private fun str(r: JsonReader): String = when (r.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean().toString()
        JsonToken.NULL -> { r.nextNull(); "" }
        else -> { r.skipValue(); "" }
    }

    private fun writeCatsBin(f: File, list: List<Category>) {
        val tmp = File(f.parentFile, f.name + ".part")
        java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream(), 16 * 1024)).use { o ->
            o.writeInt(0x53584343)
            o.writeInt(list.size)
            for (c in list) {
                o.writeUTF(c.id)
                o.writeUTF(c.name)
            }
        }
        f.delete()
        tmp.renameTo(f)
    }

    private fun readCatsBin(f: File): List<Category> {
        java.io.DataInputStream(java.io.BufferedInputStream(f.inputStream(), 16 * 1024)).use { i ->
            if (i.readInt() != 0x53584343) throw java.io.IOException("format")
            val n = i.readInt()
            val out = ArrayList<Category>(n)
            repeat(n) {
                out.add(Category(i.readUTF().intern(), i.readUTF()))
            }
            return out
        }
    }

    private fun writeBin(f: File, list: List<Entry>) {
        val tmp = File(f.parentFile, f.name + ".part")
        java.io.DataOutputStream(java.io.BufferedOutputStream(tmp.outputStream(), 64 * 1024)).use { o ->
            o.writeInt(0x53584332)
            o.writeInt(list.size)
            for (e in list) {
                o.writeUTF(e.id); o.writeUTF(e.name); o.writeUTF(e.cat)
                o.writeUTF(e.image ?: ""); o.writeUTF(e.ext ?: "")
                o.writeLong(e.added); o.writeFloat(e.rating)
            }
        }
        f.delete()
        tmp.renameTo(f)
    }

    private fun readBin(f: File): List<Entry> {
        java.io.DataInputStream(java.io.BufferedInputStream(f.inputStream(), 64 * 1024)).use { i ->
            if (i.readInt() != 0x53584332) throw java.io.IOException("format")
            val n = i.readInt()
            val kind = Kind.entries.firstOrNull { f.name.startsWith("catalog_" + it.key + "_") } ?: throw java.io.IOException("kind")
            val out = ArrayList<Entry>(n)
            repeat(n) {
                val id = i.readUTF(); val name = i.readUTF(); val cat = i.readUTF().intern()
                val img = i.readUTF().ifEmpty { null }; val ext = i.readUTF().ifEmpty { null }?.intern()
                out.add(Entry(kind, id, name, cat, img, ext, i.readLong(), i.readFloat()))
            }
            return out
        }
    }

    private fun download(a: Account, kind: Kind, to: File) {
        val action = when (kind) { Kind.LIVE -> "get_live_streams"; Kind.VOD -> "get_vod_streams"; Kind.SERIES -> "get_series" }
        val c = XtreamApi.open(XtreamApi.url(a, action), 300000)
        c.inputStream.use { i -> to.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
    }

    private fun parse(file: File, kind: Kind): List<Entry> {
        val idKey = if (kind == Kind.SERIES) "series_id" else "stream_id"
        val out = ArrayList<Entry>(if (kind == Kind.VOD) 170000 else 60000)
        JsonReader(BufferedReader(InputStreamReader(file.inputStream(), Charsets.UTF_8), 64 * 1024)).use { r ->
            r.beginArray()
            while (r.hasNext()) {
                r.beginObject()
                var id = ""; var name = ""; var cat = ""; var img: String? = null; var ext: String? = null
                var added = 0L; var rating = 0f
                while (r.hasNext()) {
                    when (r.nextName()) {
                        idKey -> id = str(r)
                        "name" -> name = str(r)
                        "category_id" -> cat = str(r)
                        "stream_icon", "cover", "cover_big", "movie_image", "poster" -> {
                            val v = str(r).trim()
                            if (v.isNotBlank() && v != "null") {
                                if (img.isNullOrBlank()) {
                                    img = v
                                } else if (TMDB_HASH_REGEX.containsMatchIn(v) && !TMDB_HASH_REGEX.containsMatchIn(img ?: "")) {
                                    img = v
                                }
                            }
                        }
                        "container_extension" -> ext = str(r).takeIf { it.isNotBlank() && it != "null" }
                        "added", "last_modified" -> added = maxOf(added, str(r).toLongOrNull() ?: 0L)
                        "rating" -> rating = str(r).toFloatOrNull() ?: 0f
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (id.isNotEmpty() && name.isNotBlank()) out.add(Entry(kind, id, name.trim(), cat.intern(), img, ext?.intern(), added, rating))
            }
            r.endArray()
        }
        out.trimToSize()
        return out
    }
}
