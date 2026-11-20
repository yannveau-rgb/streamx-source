package eu.streamx.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Une vidéo telle que l'onglet Tube la manipule (cartes, cœurs, file de lecture). */
data class TVideo(
    val id: String,
    val title: String,
    val channel: String,
    val channelUrl: String,
    val durationSec: Long,
    val views: Long,
    val at: Long = 0L, // date de mise en ligne (ms), 0 si inconnue
    val live: Boolean = false,
) {
    val thumb: String get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
    val url: String get() = "https://www.youtube.com/watch?v=$id"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("t", title).put("c", channel).put("cu", channelUrl)
        .put("d", durationSec).put("v", views).put("a", at).put("l", live)

    companion object {
        fun fromJson(o: JSONObject) = TVideo(
            o.getString("id"), o.optString("t"), o.optString("c"), o.optString("cu"),
            o.optLong("d"), o.optLong("v"), o.optLong("a"), o.optBoolean("l")
        )
    }
}

class TShelf(val key: String, val title: String, val videos: List<TVideo>)

/** Comme runCatching, mais laisse passer l'annulation d'une coroutine (sinon une page annulée écrase la page suivante). */
suspend fun <T> tubeSafe(default: T, block: suspend () -> T): T =
    try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { default }

/** Les centres d'intérêt à cocher au premier lancement : chacun alimente une étagère de l'accueil. */
class TInterest(val key: String, val fr: String, val en: String, val qFr: String, val qEn: String) {
    fun label(c: Context) = if (isFrench(c)) fr else en
    fun query(c: Context) = if (isFrench(c)) qFr else qEn
}

private fun isFrench(c: Context) = c.resources.configuration.locales[0].language == "fr"

object TubeInterests {
    val all = listOf(
        TInterest("music", "Musique", "Music", "clips musique nouveautés", "new music videos"),
        TInterest("games", "Jeux vidéo", "Video games", "jeux vidéo gameplay nouveautés", "video game gameplay new"),
        TInterest("movies", "Films & bandes-annonces", "Movies & trailers", "bande-annonce officielle film", "official movie trailer"),
        TInterest("series", "Séries", "TV series", "bande-annonce série saison", "tv series official trailer season"),
        TInterest("news", "Actualités", "News", "actualités du jour journal", "world news today"),
        TInterest("sport", "Sport", "Sports", "résumé match sport", "sports highlights"),
        TInterest("humor", "Humour", "Comedy", "humour sketch drôle", "funny comedy sketch"),
        TInterest("cooking", "Cuisine", "Cooking", "recette cuisine facile", "easy cooking recipe"),
        TInterest("science", "Sciences", "Science", "science vulgarisation", "science explained"),
        TInterest("tech", "Technologie", "Tech", "test high-tech nouveautés", "tech review new"),
        TInterest("docs", "Documentaires", "Documentaries", "documentaire complet", "full documentary"),
        TInterest("travel", "Voyage", "Travel", "voyage découverte pays", "travel guide destination"),
        TInterest("auto", "Auto & moto", "Cars & bikes", "essai voiture nouveautés", "car review new"),
        TInterest("kids", "Enfants", "Kids", "dessin animé enfants", "kids cartoons"),
        TInterest("diy", "Bricolage & déco", "DIY & home", "bricolage tuto maison", "diy home project"),
        TInterest("fitness", "Sport & bien-être", "Fitness & wellness", "séance sport maison yoga", "home workout yoga"),
    )
    fun byKey(k: String) = all.firstOrNull { it.key == k }
}

/** Mémoire locale de l'onglet Tube : cœurs, centres d'intérêt, historique. Rien ne quitte la télé. */
object TubeStore {
    private const val PREFS = "streamx_tube"
    // Un jeu de cœurs / thèmes / historique par profil, comme Favoris et Reprendre la lecture.
    private fun p(c: Context) = c.getSharedPreferences(Profile.prefs(PREFS), Context.MODE_PRIVATE)

    fun interests(c: Context): Set<String> = p(c).getStringSet("interests", emptySet()) ?: emptySet()
    fun setInterests(c: Context, s: Set<String>) = p(c).edit().putStringSet("interests", s).putBoolean("onboarded", true).apply()
    fun onboarded(c: Context) = p(c).getBoolean("onboarded", false)

    private fun readList(c: Context, key: String): List<TVideo> = runCatching {
        val a = JSONArray(p(c).getString(key, "[]"))
        (0 until a.length()).map { TVideo.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun writeList(c: Context, key: String, l: List<TVideo>) =
        p(c).edit().putString(key, JSONArray(l.map { it.toJson() }).toString()).apply()

    /** Cœurs, du plus récent au plus ancien. */
    fun likes(c: Context): List<TVideo> = readList(c, "likes")
    fun isLiked(c: Context, id: String) = likes(c).any { it.id == id }

    /** Ajoute ou retire le cœur ; renvoie true si la vidéo est désormais aimée. */
    @Synchronized
    fun toggleLike(c: Context, v: TVideo): Boolean {
        val l = likes(c)
        return if (l.any { it.id == v.id }) {
            writeList(c, "likes", l.filter { it.id != v.id }); false
        } else {
            writeList(c, "likes", (listOf(v) + l).take(500)); true
        }
    }

    /** Dernières recherches (les plus récentes d'abord), proposées quand la barre de recherche est vide. */
    fun recents(c: Context): List<String> = runCatching {
        val a = JSONArray(p(c).getString("recents", "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    @Synchronized
    fun addRecent(c: Context, q: String) {
        val t = q.trim()
        if (t.length < 2) return
        val l = (listOf(t) + recents(c).filter { !it.equals(t, true) }).take(10)
        p(c).edit().putString("recents", JSONArray(l).toString()).apply()
    }

    /** Dernières vidéos regardées (pour ne pas les reproposer et pour affiner les suggestions). */
    fun history(c: Context): List<TVideo> = readList(c, "history")

    @Synchronized
    fun markWatched(c: Context, v: TVideo) {
        writeList(c, "history", (listOf(v) + history(c).filter { it.id != v.id }).take(200))
    }

    /** Efface l'historique des vidéos regardées et les dernières recherches (télé partagée). */
    /** Déconnexion d'un profil : tout ce que Tube a retenu pour lui disparaît. */
    @Synchronized
    fun clearAll(c: Context) = p(c).edit().clear().apply()

    @Synchronized
    fun clearHistory(c: Context) = p(c).edit().remove("history").remove("recents").apply()
}

/** Téléchargeur HTTP pour NewPipeExtractor (OkHttp). */
private class TubeDownloader : Downloader() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun execute(request: Request): Response {
        val body = request.dataToSend()
        val rb = okhttp3.Request.Builder().url(request.url())
        val m = request.httpMethod()
        val reqBody = if (body != null) body.toRequestBody(null, 0, body.size) else if (m == "POST") ByteArray(0).toRequestBody(null) else null
        rb.method(m, reqBody)
        rb.header("User-Agent", UA)
        var hasCookie = false
        for ((k, vs) in request.headers()) {
            if (k.equals("Cookie", true)) hasCookie = true
            rb.removeHeader(k)
            for (v in vs) rb.addHeader(k, v)
        }
        // Évite la page de consentement européenne de YouTube.
        if (!hasCookie && request.url().contains("youtube.com")) rb.header("Cookie", "SOCS=CAI")
        client.newCall(rb.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            val text = r.body?.string() ?: ""
            return Response(r.code, r.message, r.headers.toMultimap(), text, r.request.url.toString())
        }
    }

    companion object {
        const val UA = Tube.UA
    }
}

/** Ce qu'il faut au lecteur pour démarrer une vidéo. */
class TPlayable(
    val video: TVideo,
    val videoUrl: String?,
    val audioUrl: String?,
    val muxedUrl: String?,
    val hlsUrl: String?,
    val quality: String,
    val related: List<TVideo>,
    /** Qualités disponibles (hauteur, lien image seule), de la meilleure à la plus modeste : sert à baisser la qualité quand ça rame. */
    val ladder: List<Pair<Int, String>> = emptyList(),
)

object Tube {
    private const val TAG = "StreamXTube"
    const val UA = "Mozilla/5.0 (Windows NT 10.0; rv:140.0) Gecko/20100101 Firefox/140.0"
    @Volatile private var initLang: String? = null

    /** Initialise l'extracteur dans la langue de l'appli ; relancé si l'utilisateur change de langue. */
    @Synchronized
    fun init(c: Context) {
        val fr = isFrench(c)
        val lang = if (fr) "fr" else "en"
        if (initLang == lang) return
        val loc = if (fr) Localization("fr", "FR") else Localization("en", "GB")
        NewPipe.init(TubeDownloader(), loc, ContentCountry(if (fr) "FR" else "GB"))
        initLang = lang
        memo.clear()
        invalidate()
    }

    private val idRx = Regex("""(?:v=|youtu\.be/|/shorts/|/live/)([A-Za-z0-9_-]{11})""")
    fun idOf(url: String): String? = idRx.find(url)?.groupValues?.get(1)

    private fun toVideo(i: StreamInfoItem): TVideo? {
        val id = idOf(i.url) ?: return null
        return TVideo(
            id, i.name ?: "", i.uploaderName ?: "", i.uploaderUrl ?: "", i.duration.coerceAtLeast(0),
            i.viewCount.coerceAtLeast(0), runCatching { i.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() }.getOrNull() ?: 0L, i.streamType == StreamType.LIVE_STREAM || i.streamType == StreamType.AUDIO_LIVE_STREAM
        )
    }

    private fun List<InfoItem>.videos(): List<TVideo> = asSequence().filterIsInstance<StreamInfoItem>()
        .filter { !it.isShortFormContent }.mapNotNull { toVideo(it) }.distinctBy { it.id }.toList()

    suspend fun search(c: Context, query: String): List<TVideo> = withContext(Dispatchers.IO) {
        init(c)
        val yt = ServiceList.YouTube
        val h = yt.searchQHFactory.fromQuery(query, listOf("videos"), "")
        SearchInfo.getInfo(yt, h).relatedItems.videos()
    }

    /** Suggestions de saisie de YouTube (« auto-complétion »). */
    suspend fun suggestions(c: Context, q: String): List<String> = withContext(Dispatchers.IO) {
        init(c)
        ServiceList.YouTube.suggestionExtractor.suggestionList(q)
    }

    /** Classements officiels de YouTube Charts : « Trending », « Trending Music », « Trending Gaming »… (selon le pays de la télé). */
    private val netScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, Deferred<List<TVideo>>>()

    /** Petit cache mémoire (15 min) pour les listes qui ne dépendent ni des cœurs ni de l'historique. */
    private val memo = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<TVideo>>>()

    private suspend fun memoized(key: String, block: suspend () -> List<TVideo>): List<TVideo> {
        memo[key]?.let { (t, l) -> if (System.currentTimeMillis() - t < TTL) return l }
        val lang = initLang
        val out = block()
        if (out.isNotEmpty() && lang == initLang) memo[key] = System.currentTimeMillis() to out
        return out
    }

    private fun fetchKiosk(name: String): List<TVideo> = runCatching {
        val kl = ServiceList.YouTube.kioskList
        val id = kl.availableKiosks.firstOrNull { it.equals(name, true) } ?: run {
            android.util.Log.w(TAG, "kiosk '$name' absent, disponibles : ${kl.availableKiosks}")
            return@runCatching emptyList<TVideo>()
        }
        val k = kl.getExtractorById(id, null)
        k.fetchPage()
        k.initialPage.items.videos().filter { !it.live }
    }.onFailure { android.util.Log.w(TAG, "kiosk $name: ${it.javaClass.simpleName} ${it.message}") }.getOrDefault(emptyList())

    /** Classements officiels de YouTube Charts (« trending_music », « trending_gaming »…), selon le pays de la télé. */
    suspend fun kiosk(c: Context, name: String): List<TVideo> {
        init(c)
        val lang = initLang
        memo["k:$name"]?.let { (t, l) -> if (System.currentTimeMillis() - t < TTL) return l }
        // Une seule requête par classement (et par langue), même si l'accueil la demande deux fois en même temps.
        val key = "$lang:$name"
        val d = inflight.computeIfAbsent(key) {
            netScope.async(start = CoroutineStart.LAZY) { fetchKiosk(name) }.also { job -> job.invokeOnCompletion { inflight.remove(key, job) } }
        }
        d.start()
        val out = d.await()
        if (out.isNotEmpty() && lang == initLang) memo["k:$name"] = System.currentTimeMillis() to out
        return out
    }

    /** « Tendances » : mélange des classements officiels (musique, jeux vidéo, bandes-annonces) ; sinon une recherche populaire. */
    suspend fun trending(c: Context): List<TVideo> = withContext(Dispatchers.IO) {
        val lists = coroutineScope {
            listOf("trending_music", "trending_gaming", "trending_movies_and_shows").map { n -> async { kiosk(c, n) } }.awaitAll()
        }
        val mixed = ArrayList<TVideo>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (l in lists) l.getOrNull(i)?.let { mixed += it }
        val out = mixed.distinctBy { it.id }
        if (out.size >= 6) out
        else {
            val year = Calendar.getInstance().get(Calendar.YEAR)
            val q = if (isFrench(c)) "tendances youtube france $year" else "trending videos this week"
            tubeSafe(emptyList()) { search(c, q) }
        }
    }

    /** Vidéos proches d'une vidéo donnée (les « suggestions » de YouTube) : sert aussi à la lecture automatique. */
    suspend fun related(c: Context, videoId: String): List<TVideo> = withContext(Dispatchers.IO) {
        init(c)
        StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId").relatedItems.videos()
    }

    /** Résout une vidéo en flux lisibles : image et son séparés (jusqu'à 1080p) ou flux unique si besoin. */
    suspend fun resolve(c: Context, v: TVideo, maxHeight: Int = 1080): TPlayable = withContext(Dispatchers.IO) {
        init(c)
        val info = StreamInfo.getInfo(v.url)
        val vid = TVideo(
            v.id, info.name ?: v.title, info.uploaderName ?: v.channel, info.uploaderUrl ?: v.channelUrl,
            info.duration.coerceAtLeast(0), info.viewCount.coerceAtLeast(0), v.at, info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM
        )
        val related = info.relatedItems.videos().filter { it.id != v.id }
        val hls = info.hlsUrl?.takeIf { it.isNotBlank() }
        if (vid.live && hls != null) return@withContext TPlayable(vid, null, null, null, hls, "Live", related)

        // H.264 (avc1) d'abord : décodage matériel partout ; AV1/VP9 seulement s'il n'y a rien d'autre.
        val mp4 = info.videoOnlyStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.height in 1..maxHeight && it.format?.suffix == "mp4" }
        val vo = (mp4.filter { it.codec?.startsWith("avc1") == true }.ifEmpty { mp4 })
            .sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream> { it.height }.thenByDescending { it.bitrate })
        // Piste audio d'origine (pas le doublage automatique ni l'audiodescription), puis meilleur débit.
        val au = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format?.suffix == "m4a" }
            .sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.AudioStream> { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
                .thenByDescending { it.averageBitrate })
        val muxed = info.videoStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }.maxByOrNull { it.height }
        val ladder = vo.groupBy { it.height }.map { (h, l) -> h to l.first().content }.sortedByDescending { it.first }
        val bestV = vo.firstOrNull()
        val bestA = au.firstOrNull()
        android.util.Log.i(TAG, "resolve: videoOnly=${vo.size} audio=${au.size} muxed=${muxed?.height} hls=${hls != null}")
        when {
            bestV != null && bestA != null ->
                TPlayable(vid, bestV.content, bestA.content, muxed?.content, hls, "${bestV.height}p", related, ladder)
            muxed != null -> TPlayable(vid, null, null, muxed.content, hls, "${muxed.height}p", related)
            hls != null -> TPlayable(vid, null, null, null, hls, "HLS", related)
            else -> error("Aucun flux lisible")
        }
    }

    // ---- Accueil par étagères ----

    private class Cached(val at: Long, val shelves: List<TShelf>)
    @Volatile private var cache: Cached? = null
    private const val TTL = 15 * 60_000L

    @Volatile private var gen = 0
    @Volatile private var lastProfile: String? = null
    private val cacheLock = Any()

    /** Les cœurs ou l'historique ont changé : l'accueil sera recalculé (et un calcul en cours n'écrasera pas ce changement). */
    fun invalidate() = synchronized(cacheLock) { gen++; cache = null }

    suspend fun homeShelves(c: Context, force: Boolean = false): List<TShelf> {
        withContext(Dispatchers.IO) { init(c) }
        if (lastProfile != Profile.ns) { lastProfile = Profile.ns; invalidate() }
        val cc = cache
        if (!force && cc != null && System.currentTimeMillis() - cc.at < TTL) return cc.shelves
        val fr = isFrench(c)
        val ctx = c.applicationContext
        val startGen = gen
        // Lectures locales (JSON) hors du thread principal.
        val (likes, watched, interests) = withContext(Dispatchers.IO) {
            val lk = TubeStore.likes(ctx)
            Triple(lk, (TubeStore.history(ctx) + lk).map { it.id }.toSet(), TubeStore.interests(ctx).mapNotNull { TubeInterests.byKey(it) })
        }
        val shelves = coroutineScope {
            val jobs = ArrayList<kotlinx.coroutines.Deferred<TShelf?>>()
            if (likes.isNotEmpty()) jobs += async {
                // « Pour toi » : suggestions autour des derniers cœurs, mélangées entre elles.
                val lists = likes.take(3).map { l -> async { tubeSafe(emptyList()) { memoized("r:${l.id}") { related(c, l.id) } } } }.awaitAll()
                val merged = ArrayList<TVideo>()
                val max = lists.maxOfOrNull { it.size } ?: 0
                for (i in 0 until max) for (l in lists) l.getOrNull(i)?.let { merged += it }
                val out = merged.distinctBy { it.id }.filter { it.id !in watched }.take(20)
                if (out.isEmpty()) null else TShelf("foryou", if (fr) "Pour toi" else "For you", out)
            }
            jobs += async {
                val t = tubeSafe(emptyList()) { trending(c) }.filter { it.id !in watched }.take(24)
                if (t.isEmpty()) null else TShelf("trending", if (fr) "Tendances" else "Trending", t)
            }
            // Classements officiels pour les thèmes qui en ont un.
            val charts = mapOf(
                "music" to ("trending_music" to (if (fr) "Musique du moment" else "Music charts")),
                "games" to ("trending_gaming" to (if (fr) "Jeux vidéo du moment" else "Gaming charts")),
                "movies" to ("trending_movies_and_shows" to (if (fr) "Bandes-annonces du moment" else "Trailers charts")),
            )
            for (it in interests) {
                charts[it.key]?.let { (kioskName, title) ->
                    jobs += async {
                        val l = kiosk(c, kioskName).filter { v -> v.id !in watched }.take(24)
                        if (l.isEmpty()) null else TShelf("chart_${it.key}", title, l)
                    }
                }
                jobs += async {
                    val l = tubeSafe(emptyList()) { memoized("s:${it.query(c)}") { search(c, it.query(c)) } }.filter { v -> v.id !in watched }.take(20)
                    if (l.isEmpty()) null else TShelf(it.key, it.label(c), l)
                }
            }
            // Une vidéo n'apparaît qu'une fois sur l'accueil : les premières étagères (Pour toi, Tendances) ont la priorité.
            val seen = HashSet<String>()
            jobs.awaitAll().filterNotNull()
                .map { sh -> TShelf(sh.key, sh.title, sh.videos.filter { v -> seen.add(v.id) }) }
                .filter { sh -> sh.videos.size >= 3 }
        }
        // Les cœurs d'abord ont déjà leur propre étagère : « Pour toi » en tête, puis tendances, puis les thèmes.
        synchronized(cacheLock) { if (startGen == gen && shelves.isNotEmpty()) cache = Cached(System.currentTimeMillis(), shelves) }
        return shelves
    }

    fun formatViews(c: Context, n: Long): String {
        if (n <= 0) return ""
        val fr = isFrench(c)
        val loc = if (fr) Locale.FRANCE else Locale.US
        fun fmt(x: Double) = String.format(loc, "%.1f", x).let { if (it.endsWith(if (fr) ",0" else ".0")) it.dropLast(2) else it }
        // On arrondit avant de choisir l'unité : 999 960 vues donnent « 1 M » et non « 1000 k ».
        val s = when {
            n >= 999_950_000 -> fmt(n / 1e9) + (if (fr) " Md" else "B")
            n >= 999_950 -> fmt(n / 1e6) + (if (fr) " M" else "M")
            n >= 1_000 -> fmt(n / 1e3) + (if (fr) " k" else "K")
            else -> n.toString()
        }
        return if (fr) (if (n == 1L) "1 vue" else "$s vues") else (if (n == 1L) "1 view" else "$s views")
    }

    /** « il y a 3 jours » / « 3 days ago », calculé ici pour suivre la langue de l'appli. */
    fun formatAgo(c: Context, at: Long): String {
        if (at <= 0) return ""
        val fr = isFrench(c)
        val min = (System.currentTimeMillis() - at) / 60_000
        if (min < 0) return ""
        val (n, fu, eu) = when {
            min < 60 -> Triple(min.coerceAtLeast(1), "minute", "minute")
            min < 60 * 24 -> Triple(min / 60, "heure", "hour")
            min < 60 * 24 * 7 -> Triple(min / (60 * 24), "jour", "day")
            min < 60 * 24 * 30 -> Triple(min / (60 * 24 * 7), "semaine", "week")
            min < 60 * 24 * 365 -> Triple(min / (60 * 24 * 30), "mois", "month")
            else -> Triple(min / (60 * 24 * 365), "an", "year")
        }
        return if (fr) "il y a $n ${if (fu == "mois" || n < 2) fu else fu + "s"}" else "$n $eu${if (n > 1) "s" else ""} ago"
    }

    fun formatDuration(sec: Long): String {
        if (sec <= 0) return ""
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }
}
