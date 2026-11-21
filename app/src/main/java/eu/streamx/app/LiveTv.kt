package eu.streamx.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// --- Nettoyage des noms (« FR:beINSport4.HD » -> « beIN Sports 4 ») ---
// Le préfixe pays ou fournisseur exige un séparateur (« : » ou « | ») et des MAJUSCULES : sans cela, on rongeait le début de n'importe quel nom.
private val channelPrefixRe = Regex("^\\s*(?:\\|[A-Z]{2,4}\\||\\[[A-Z0-9+ -]{2,8}]|\\([A-Z0-9+ -]{2,8}\\)|(?:[A-Z]{2,5}|BiH|MyHD)\\s*[:|])\\s*")
private val channelDashLeadRe = Regex("^\\s*(?:FR|FRANCE|BE|CH|CA|UK|US|USA|AR|AF|TR|DE|ES|IT|PT)\\s+[-–]\\s+")
private val mixedLeadRe = Regex("^([A-Za-z]{2,6}):\\s*")
private val dotsRe = Regex("[._]+")
private val beinRe = Regex("(?i)^bein\\s*sports?(?=\\s*\\d|\\s|$)")
private val letterDigitRe = Regex("(?<=[a-z])(?=\\d)")
private val plusRe = Regex("\\+(?=[A-Za-z])")
private val formatRe = Regex("\\p{Cf}")
private val edgesRe = Regex("^[\\s\\-:|•]+|[\\s\\-:|•]+$")
private val qualityTagRe = Regex(
    "(?<![A-Za-z0-9])(?:4K|8K|UHD|2160P?|FHD|QHD|FULL\\s*HD|1080[PI]?|HEVC|H\\.?26[45]|HDR|HD|QSD|SD|720P?|LQ|RAW|50FPS|60FPS|BACKUP|BKP)(?![A-Za-z0-9])|[⁴ᴷᴴᴰᶠ]+",
    RegexOption.IGNORE_CASE
)
private val bracketNoiseRe = Regex("\\[(?:FHD|HD|SD|4K|UHD|HEVC|FR|MULTI|BACKUP|BKP)]|\\((?:FHD|HD|SD|4K|UHD|HEVC|FR|MULTI|BACKUP|BKP)\\)", RegexOption.IGNORE_CASE)
private val multiSpaceRe = Regex("\\s{2,}")
private val categoryPrefixRe = Regex("^\\s*(?:\\|?[A-Z]{2,3}\\|?\\s*[:|]|(?:FR|FRANCE)\\s+-\\s+)\\s*")

/** Extrait le badge de qualité (4K, FHD, HEVC, HD) d'un nom brut de chaîne IPTV. */
fun extractQualityBadge(raw: String): String? {
    val u = raw.uppercase()
    return when {
        u.contains("4K") || u.contains("UHD") || u.contains("2160") || raw.contains("⁴ᴷ") -> "4K"
        u.contains("FHD") || u.contains("1080") || u.contains("FULL HD") -> "FHD"
        u.contains("HEVC") || u.contains("H265") || u.contains("H.265") -> "HEVC"
        u.contains("HD") || u.contains("720") || raw.contains("ᴴᴰ") -> "HD"
        else -> null
    }
}

/** Nettoie le nom d'une chaîne (« FR:Canal+Foot.SD » -> « Canal+ Foot », « |FR| TF1 FHD HEVC » -> « TF1 »). */
fun cleanChannelName(raw: String): String {
    var s = formatRe.replace(raw, "").trim()
    s = channelPrefixRe.replaceFirst(s, "")
    s = channelDashLeadRe.replaceFirst(s, "")
    s = mixedLeadRe.replaceFirst(s, "$1 ")
    s = dotsRe.replace(s, " ")
    s = beinRe.replace(s, "beIN Sports ")
    s = letterDigitRe.replace(s, " ")
    s = plusRe.replace(s, "+ ")
    s = bracketNoiseRe.replace(s, " ")
    s = qualityTagRe.replace(s, " ")
    s = edgesRe.replace(s, "")
    return multiSpaceRe.replace(s, " ").trim().ifBlank { raw.trim() }
}

/** Titre d'une catégorie : on n'enlève qu'un vrai préfixe (« FR: », « |FR| », « FR - »), jamais le début du nom. */
fun cleanLiveCategoryName(raw: String): String {
    val s = categoryPrefixRe.replaceFirst(raw.trim(), "").trim()
    return s.ifBlank { raw.trim() }
}
private class FrenchChannelSpec(
    val number: Int,
    val canonicalName: String,
    val matchRx: Regex,
    val excludeRx: Regex? = null
)

/**
 * Les grandes chaînes françaises dans l'ordre officiel TNT + grands bouquets (Canal+, Sport, Cinéma).
 */
private val FAMOUS_FRENCH_CHANNELS = listOf(
    FrenchChannelSpec(1, "TF1", Regex("(^|[^a-z0-9])tf\\s*1([^a-z0-9+]|$)"), Regex("series|films|info|\\+1|kids|doc|(^|\\s)(s\\s*f|f\\s*s)(\\s|$)")),
    FrenchChannelSpec(2, "France 2", Regex("(^|[^a-z0-9])(france\\s*2|fr\\s*2)([^a-z0-9]|$)"), Regex("24|info")),
    FrenchChannelSpec(3, "France 3", Regex("(^|[^a-z0-9])(france\\s*3|fr\\s*3)([^a-z0-9]|$)"), Regex("alpes|alsace|aquitaine|auvergne|basse|bourgogne|bretagne|centre|champagne|corse|cote|franche|haut|languedoc|limousin|lorraine|midi|nord|normandie|nouvelle|occitanie|ouest|paris|pays|picardie|poitou|provence|rhone|via\\s*stella")),
    FrenchChannelSpec(4, "Canal+", Regex("(^|[^a-z0-9])canal\\s*\\+?([^a-z0-9]|$)"), Regex("foot|sport|cinema|series|docs|kids|grand\\s*ecran|box\\s*office|family|decale|fiesta|premier|live\\s*\\d")),
    FrenchChannelSpec(5, "France 5", Regex("(^|[^a-z0-9])(france\\s*5|fr\\s*5)([^a-z0-9]|$)")),
    FrenchChannelSpec(6, "M6", Regex("(^|[^a-z0-9])m\\s*6([^a-z0-9]|$)"), Regex("music|boutique|\\+1")),
    FrenchChannelSpec(7, "Arte", Regex("(^|[^a-z0-9])arte([^a-z0-9]|$)"), Regex("de|allemagne|german")),
    FrenchChannelSpec(8, "C8", Regex("(^|[^a-z0-9])c\\s*8([^a-z0-9]|$)")),
    FrenchChannelSpec(9, "W9", Regex("(^|[^a-z0-9])w\\s*9([^a-z0-9]|$)")),
    FrenchChannelSpec(10, "TMC", Regex("(^|[^a-z0-9])tmc([^a-z0-9]|$)")),
    FrenchChannelSpec(11, "TFX", Regex("(^|[^a-z0-9])tfx([^a-z0-9]|$)")),
    FrenchChannelSpec(12, "NRJ 12", Regex("(^|[^a-z0-9])nrj\\s*12([^a-z0-9]|$)")),
    FrenchChannelSpec(13, "LCP", Regex("(^|[^a-z0-9])(lcp|public\\s*senat)([^a-z0-9]|$)")),
    FrenchChannelSpec(14, "France 4", Regex("(^|[^a-z0-9])(france\\s*4|fr\\s*4)([^a-z0-9]|$)")),
    FrenchChannelSpec(15, "BFM TV", Regex("(^|[^a-z0-9])bfm\\s*(tv)?([^a-z0-9]|$)"), Regex("business|paris|lyon|marseille|lille|nice|alsace|normandie|var|dici|grand")),
    FrenchChannelSpec(16, "CNews", Regex("(^|[^a-z0-9])c\\s*news([^a-z0-9]|$)")),
    FrenchChannelSpec(17, "CStar", Regex("(^|[^a-z0-9])c\\s*star([^a-z0-9]|$)"), Regex("hits")),
    FrenchChannelSpec(18, "Gulli", Regex("(^|[^a-z0-9])gulli([^a-z0-9]|$)"), Regex("africa|bil|arab")),
    FrenchChannelSpec(20, "TF1 Séries Films", Regex("(^|[^a-z0-9])tf\\s*1\\s*(series(\\s*films)?|s\\s*f|f\\s*s)([^a-z0-9]|$)")),
    FrenchChannelSpec(21, "L'Équipe", Regex("(^|[^a-z0-9])(l\\s*'?\\s*)?equipe(\\s*21|\\s*tv)?([^a-z0-9]|$)"), Regex("live\\s*\\d")),
    FrenchChannelSpec(22, "6ter", Regex("(^|[^a-z0-9])6\\s*ter([^a-z0-9]|$)")),
    FrenchChannelSpec(23, "RMC Story", Regex("(^|[^a-z0-9])rmc\\s*story([^a-z0-9]|$)")),
    FrenchChannelSpec(24, "RMC Découverte", Regex("(^|[^a-z0-9])rmc\\s*decouverte([^a-z0-9]|$)")),
    FrenchChannelSpec(25, "Chérie 25", Regex("(^|[^a-z0-9])cherie\\s*25([^a-z0-9]|$)")),
    FrenchChannelSpec(26, "LCI", Regex("(^|[^a-z0-9])lci([^a-z0-9]|$)")),
    FrenchChannelSpec(27, "franceinfo:", Regex("(^|[^a-z0-9])france\\s*info([^a-z0-9]|$)")),
    FrenchChannelSpec(28, "Canal+ Cinéma", Regex("(^|[^a-z0-9])canal\\s*\\+?\\s*(box\\s*office|cinema)([^a-z0-9]|$)")),
    FrenchChannelSpec(29, "Canal+ Grand Écran", Regex("(^|[^a-z0-9])canal\\s*\\+?\\s*grand\\s*ecran([^a-z0-9]|$)")),
    FrenchChannelSpec(30, "Canal+ Sport", Regex("(^|[^a-z0-9])canal\\s*\\+?\\s*sport([^a-z0-9]|$)"), Regex("360|\\d")),
    FrenchChannelSpec(31, "Canal+ Foot", Regex("(^|[^a-z0-9])canal\\s*\\+?\\s*foot([^a-z0-9]|$)")),
    FrenchChannelSpec(32, "beIN Sports 1", Regex("(^|[^a-z0-9])bein\\s*sports?\\s*1([^a-z0-9]|$)"), Regex("max|arab|en|es|us|tr")),
    FrenchChannelSpec(33, "RMC Sport 1", Regex("(^|[^a-z0-9])rmc\\s*sport\\s*1([^a-z0-9]|$)")),
    FrenchChannelSpec(34, "Eurosport 1", Regex("(^|[^a-z0-9])eurosport\\s*1([^a-z0-9]|$)")),
    FrenchChannelSpec(35, "Paris Première", Regex("(^|[^a-z0-9])paris\\s*premiere([^a-z0-9]|$)")),
    FrenchChannelSpec(36, "Téva", Regex("(^|[^a-z0-9])teva([^a-z0-9]|$)")),
    FrenchChannelSpec(37, "RTL9", Regex("(^|[^a-z0-9])rtl\\s*9([^a-z0-9]|$)"))
)

internal val nonFrTagRx = Regex("(^|[^a-z0-9])(uk|us|usa|de|es|it|pt|ar|arab|tr|nl|pl|ro|ru|al|af|be|ch|ca)([^a-z0-9]|$)")

private fun streamQualityScore(rawName: String, isFrCat: Boolean): Int {
    val u = rawName.uppercase()
    var score = if (isFrCat) 100 else 0
    when {
        u.contains("FHD") || u.contains("1080") -> score += 50
        u.contains("HEVC") || u.contains("H265") -> score += 45
        u.contains("4K") || u.contains("UHD") -> score += 42
        u.contains("HD") || u.contains("720") -> score += 35
        u.contains("SD") || u.contains("LQ") -> score += 10
        else -> score += 25
    }
    if (u.contains("BACKUP") || u.contains("BKP") || u.contains(" SECOURS")) score -= 30
    if (u.contains("+1")) score -= 40
    return score
}

private class LiveFacet(val key: String, val labelRes: Int, val rx: Regex)

// Facettes calculées sur les noms de catégories (sans accents, en minuscules). Corrigées d'après le catalogue réel :
// « TNT » = uniquement les bouquets FRANCE SD/HD/FHD/4K ; Sport inclut Ligue 1, Canal+ Live et Coupe du monde ; Jeunesse n'attrape plus Disney+.
private val LIVE_FACETS = listOf(
    LiveFacet("tnt", R.string.live_facet_tnt, Regex("(^|\\s)(france\\s*(fhd|hd|4k|sd)|tnt)(\\s|$)")),
    LiveFacet("sport", R.string.grp_sport, Regex("sport|foot|bein|dazn|ligue|world\\s*cup|canal\\+?\\s*live|eurosport|equipe|tennis|rugby|f1|ufc|nba|dstv|starz")),
    LiveFacet("cinema", R.string.live_facet_cinema, Regex("cinema|film|serie|box\\s*office|netflix|amazon|prime|disney|ocs|paramount|tcm|vip")),
    LiveFacet("info", R.string.live_facet_news, Regex("info|news|actualit|bfm|cnews|lci")),
    LiveFacet("docu", R.string.facet_docu, Regex("docu|decouverte|nature|histoire|science|planete|geo")),
    LiveFacet("kids", R.string.grp_kids, Regex("enfant|kids|jeunesse|junior|cartoon|nickel|gulli|tiji|manga|toon")),
    LiveFacet("music", R.string.grp_music, Regex("musi(que|c)|concert|radio|mtv|nrj|trace|mcm|mezzo|melody|stingray|rfm")),
    LiveFacet("intl", R.string.live_facet_intl, Regex("belgique|swiss|suisse|canada|quebec|caraibe|outre|africa|afrique|dstv|international")),
    LiveFacet("arab", R.string.live_facet_arab, Regex("arab|mbc|algerie|tunisie|maroc|shahid|osn|gotv|kurdistan"))
)

private const val TNT_ID = "__tnt__"
private val tntQualityRx = Regex("(^|\\s)france\\s*(fhd|hd|4k|sd)(\\s|$)")
private val spacesRe = Regex("\\s+")

/** Noms propres des catégories de ce fournisseur ; les autres gardent leur nom nettoyé. */
private val LIVE_CATEGORY_TITLES = mapOf(
    "sports fr" to "Sport", "sports live fr" to "Sport · événements", "ligue 1 fr" to "Ligue 1",
    "canal+live" to "Canal+ · événements", "dazn disney+" to "DAZN & LaLiga", "world cup 2026" to "Coupe du monde 2026",
    "france news" to "Info", "france vip" to "Bouquets & thématiques", "france kids" to "Jeunesse", "music tv" to "Musique",
    "box office fr" to "Box-office", "netflix fr" to "Netflix (chaînes)", "amazon fr" to "Prime Video (chaînes)", "disney fr" to "Disney+ (chaînes)",
    "belgique" to "Belgique", "swiss" to "Suisse", "canada" to "Canada / Québec", "canal+ caraibe" to "Outre-mer & Caraïbes",
    "canal+ afrique" to "Canal+ Afrique", "africa" to "Afrique", "no category" to "Divers", "tnt" to "TNT"
)

fun liveCategoryTitle(raw: String): String = LIVE_CATEGORY_TITLES[normalize(raw).trim()] ?: cleanLiveCategoryName(raw)

/** Ordre d'affichage : TNT, info, bouquets, plateformes, sport, jeunesse, musique, puis les autres chaînes françaises, enfin l'étranger. */
private val LIVE_RANK = listOf(
    "tnt", "france news", "france vip", "box office fr", "netflix fr", "amazon fr", "disney fr",
    "sports fr", "sports live fr", "ligue 1 fr", "canal+live", "dazn disney+", "world cup 2026", "france kids", "music tv"
)

private fun liveCategoryRank(c: Category, frIds: Set<String>): Int {
    val n = normalize(c.name).trim()
    val i = LIVE_RANK.indexOf(n)
    return when {
        CategoryRules.isAdult(c.name) -> 1000
        i >= 0 -> i
        c.id in frIds -> 50
        else -> 100
    }
}

/**
 * Une carte par chaîne : les versions SD / HD / FHD / 4K d'une même chaîne sont regroupées, la meilleure qualité est lancée
 * en premier et les autres servent de secours si elle ne démarre pas.
 */
fun groupLiveQualities(items: List<Item>): List<Item> {
    val groups = LinkedHashMap<String, MutableList<Item>>()
    for (it in items) {
        val key = spacesRe.replace(normalize(cleanChannelName(it.name)), "").ifEmpty { it.id }
        groups.getOrPut(key) { ArrayList() }.add(it)
    }
    return groups.values.map { g ->
        if (g.size == 1) g[0] else {
            val sorted = g.sortedByDescending { streamQualityScore(it.name, false) }
            sorted[0].copy(alts = sorted.drop(1).map { it.id })
        }
    }
}

/** Lance une chaîne en gardant la rangée comme liste de zapping (CH+/CH−, numéro, dernière chaîne). */
fun openLiveZap(ctx: android.content.Context, account: Account, item: Item, list: List<Item>) {
    val idx = list.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
    val from = (idx - 150).coerceAtLeast(0)
    val to = (idx + 150).coerceAtMost(list.size)
    val zap = list.subList(from, to).map {
        ZapItem(cleanChannelName(it.name), streamUrl(account, "live", it.id, "m3u8"), it.alts.map { a -> streamUrl(account, "live", a, "m3u8") })
    }
    ctx.startActivity(
        playIntent(
            ctx, streamUrl(account, "live", item.id, "m3u8"), cleanChannelName(item.name), true, item.image,
            alts = item.alts.map { streamUrl(account, "live", it, "m3u8") }, zap = zap, zapIdx = idx - from
        )
    )
}
data class FeaturedFrenchChannel(val number: Int, val canonicalName: String, val item: Item)

@Composable
fun LiveTvScreen(account: Account, onDetail: OpenDetail) {
    val ctx = LocalContext.current
    var managing by remember { mutableStateOf(false) }
    if (managing) {
        BackHandler { managing = false }
        CategoriesScreen(Kind.LIVE, account, R.string.live) { managing = false }
        return
    }

    val open: (Item) -> Unit = { openItem(ctx, account, Kind.LIVE, it, onDetail) }
    val cv = Catalog.version
    val hv = HiddenVersion.v
    val fv = FavVersion.v
    var cats by remember(account, cv) { mutableStateOf(Catalog.categoriesOf(Kind.LIVE)) }
    var failed by remember(account) { mutableStateOf(false) }
    val hidden = remember(managing, hv) { HiddenStore.load(ctx, Kind.LIVE) }

    LaunchedEffect(account) {
        if (cats == null) {
            val r = Catalog.ensureCategories(account, ctx.cacheDir, Kind.LIVE)
            if (r == null) failed = true else cats = r
        }
    }

    var facet by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var focusedLive by remember { mutableStateOf<Item?>(null) }
    // (Le programme en cours s'affiche maintenant sur la vignette de la chaîne survolée : plus de barre en bas.)

    // Chaînes favorites visibles, triées par nom propre et épinglées dès l'arrivée sur TV en direct
    val favLive = remember(fv, hv, hidden) {
        Favorites.load(ctx)
            .filter { it.kind == Kind.LIVE.key && it.cat !in hidden }
            .sortedBy { normalize(cleanChannelName(it.name)) }
            .map { it.toItem() }
    }

    // Catégories visibles triées : les catégories françaises en premier, les adultes en fin
    val frIds = remember(cats, cv) { cats?.let { CategoryRules.frenchIds(Kind.LIVE, it) } ?: emptySet() }
    val rawVisible = remember(cats, hidden) { cats?.filter { it.id !in hidden } }
    // TNT : les bouquets FRANCE SD / HD / FHD / 4K ne font plus qu'UNE rangée, sans chaînes en quadruple.
    val tntIds = remember(rawVisible) { rawVisible?.filter { tntQualityRx.containsMatchIn(normalize(it.name)) }?.mapTo(HashSet()) { it.id } ?: emptySet() }
    val tntItems by produceState(emptyList<Item>(), cv, tntIds) {
        value = withContext(Dispatchers.Default) {
            if (tntIds.isEmpty()) emptyList() else groupLiveQualities(Catalog.list(Kind.LIVE).filter { it.cat in tntIds }.map { it.toItem() })
        }
    }
    val visibleCats = remember(rawVisible, frIds, tntIds) {
        rawVisible?.let { list ->
            val base = list.filter { it.id !in tntIds }
            (if (tntIds.isEmpty()) base else base + Category(TNT_ID, "TNT")).sortedBy { liveCategoryRank(it, frIds) }
        }
    }
    val zapOpen: (Item, List<Item>) -> Unit = { it, l -> openLiveZap(ctx, account, it, l) }

    // Extraction automatique en RAM des grandes chaînes françaises (TNT & Canal+)
    val featuredFrench by produceState(emptyList<FeaturedFrenchChannel>(), cv, hv, rawVisible, frIds) {
        value = withContext(Dispatchers.Default) {
            val catList = rawVisible ?: return@withContext emptyList()
            val frCatIds = catList.filter { it.id in frIds && !CategoryRules.isAdult(it.name) }.mapTo(HashSet()) { it.id }
            val nonAdultCatIds = catList.filter { !CategoryRules.isAdult(it.name) }.mapTo(HashSet()) { it.id }

            val allStreams = Catalog.list(Kind.LIVE).filter { it.cat in nonAdultCatIds }
            if (allStreams.isEmpty()) return@withContext emptyList()

            val candidates = allStreams.filter { e ->
                e.cat in frCatIds || CategoryRules.isFrench(e.name) || !nonFrTagRx.containsMatchIn(normalize(e.name))
            }

            val out = ArrayList<FeaturedFrenchChannel>(FAMOUS_FRENCH_CHANNELS.size)
            for (spec in FAMOUS_FRENCH_CHANNELS) {
                var bestEntry: Entry? = null
                var bestScore = Int.MIN_VALUE
                for (e in candidates) {
                    val cleaned = normalize(cleanChannelName(e.name))
                    if (!spec.matchRx.containsMatchIn(cleaned)) continue
                    if (spec.excludeRx != null && spec.excludeRx.containsMatchIn(cleaned)) continue
                    val s = streamQualityScore(e.name, e.cat in frCatIds) + (if (e.image != null) 15 else 0)
                    if (s > bestScore) {
                        bestScore = s
                        bestEntry = e
                    }
                }
                if (bestEntry != null) {
                    out.add(FeaturedFrenchChannel(spec.number, spec.canonicalName, bestEntry.toItem()))
                }
            }
            out
        }
    }

    val normCats = remember(visibleCats) { visibleCats?.associate { it.id to normalize(it.name) } ?: emptyMap() }
    val availableFacets = remember(visibleCats, normCats) {
        if (visibleCats == null) emptyList()
        else LIVE_FACETS.filter { f -> visibleCats.any { f.rx.containsMatchIn(normCats[it.id] ?: "") } }
    }

    val theme = navThemeOf(NavIconType.LIVE)

    Box(
        Modifier
            .fillMaxSize()
            .cachedDraw {
                drawRect(
                    Brush.radialGradient(
                        listOf(theme.c1.copy(alpha = 0.25f), Color.Transparent),
                        center = Offset(size.width * 0.78f, size.height * 0.14f),
                        radius = size.width * 0.48f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(theme.c2.copy(alpha = 0.18f), Color.Transparent),
                        center = Offset(size.width * 0.24f, size.height * 0.20f),
                        radius = size.width * 0.38f
                    )
                )
            }
    ) {
        Column(Modifier.fillMaxSize().padding(top = 22.dp)) {
            PageHeader(
                NavIconType.LIVE, stringResource(R.string.live), stringResource(R.string.live_subtitle),
                modifier = Modifier.padding(horizontal = 28.dp),
                // Réglage de la liste, à part des filtres.
                trailing = { ActionChip(stringResource(R.string.cat_manage), icon = Icons.Outlined.Tune) { managing = true } }
            )
            Spacer(Modifier.height(10.dp))

            // Barre de filtres thématiques + favoris + gestion des catégories
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 4.dp)
            ) {
                item(key = "all") {
                    SelectChip(stringResource(R.string.facet_all), facet == null) { facet = null }
                }
                if (favLive.isNotEmpty()) {
                    item(key = "favs") {
                        SelectChip(stringResource(R.string.favorites) + " (${favLive.size})", facet == "favs", icon = androidx.compose.material.icons.Icons.Filled.Favorite) { facet = "favs" }
                    }
                }
                if (featuredFrench.isNotEmpty()) {
                    item(key = "fr_top") {
                        SelectChip(stringResource(R.string.live_fr_top_chip), facet == "fr_top") { facet = "fr_top" }
                    }
                }
                items(availableFacets, key = { it.key }) { f ->
                    SelectChip(stringResource(f.labelRes), facet == f.key) { facet = f.key }
                }
                val ch = focusedLive
                if (ch != null) {
                    val isChFav = Favorites.contains(ctx, Kind.LIVE, ch.id)
                    item(key = "toggle_fav") {
                        ActionChip(
                            cleanChannelName(ch.name),
                            icon = if (isChFav) androidx.compose.material.icons.Icons.Filled.Favorite else androidx.compose.material.icons.Icons.Outlined.FavoriteBorder
                        ) {
                            Favorites.toggle(ctx, Kind.LIVE, ch)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            when {
                visibleCats == null -> Text(
                    stringResource(if (failed) R.string.cat_error else R.string.cat_loading),
                    color = SX.Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 28.dp)
                )
                visibleCats.isEmpty() -> Text(
                    stringResource(R.string.cat_none_visible),
                    color = SX.Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 28.dp)
                )
                else -> {
                    val activeFacet = availableFacets.firstOrNull { it.key == facet }
                    val shownCats = remember(visibleCats, facet, activeFacet, normCats) {
                        when {
                            facet == "favs" -> emptyList()
                            facet == "fr_top" -> visibleCats.filter { (it.id in frIds || it.id == TNT_ID) && !CategoryRules.isAdult(it.name) }
                            activeFacet != null -> visibleCats.filter { activeFacet.rx.containsMatchIn(normCats[it.id] ?: "") }
                            else -> visibleCats
                        }
                    }

                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = 28.dp, top = 6.dp, bottom = 20.dp)
                    ) {
                        // 1. Mes chaînes favorites — Épinglées dès l'arrivée sur TV en direct
                        if (favLive.isNotEmpty() && (facet == null || facet == "favs")) {
                            item(key = "fav_live") {
                                Row(
                                    Modifier.padding(bottom = 3.dp, end = 28.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(Brush.horizontalGradient(listOf(Color(0xFF2DD4BF), Color(0xFF7B52FF))))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Icon(androidx.compose.material.icons.Icons.Filled.Favorite, null, tint = Color.White, modifier = Modifier.size(11.dp))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.favs_live),
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                LazyRow(
                                    Modifier.height(118.dp).bleedStart(),
                                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp)
                                ) {
                                    items(favLive, key = { "fav_" + it.id }) { ch ->
                                        LiveChannelCard(
                                            item = ch,
                                            onFocus = { focusedLive = ch },
                                            onClick = { open(ch) }
                                        )
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                            }
                        }

                        // 2. Rangée vedette : Les grandes chaînes françaises (TNT & Canal+)
                        if (featuredFrench.isNotEmpty() && (facet == null || facet == "fr_top" || facet == "tnt")) {
                            item(key = "featured_french") {
                                Row(
                                    Modifier.padding(bottom = 3.dp, end = 28.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.live_fr_top_title),
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                LazyRow(
                                    Modifier.height(118.dp).bleedStart(),
                                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp)
                                ) {
                                    itemsIndexed(featuredFrench, key = { _, ch -> "fr_" + ch.number + "_" + ch.item.id }) { _, ch ->
                                        LiveChannelCard(
                                            item = ch.item,
                                            displayName = ch.canonicalName,
                                            channelNum = ch.number,
                                            onFocus = { focusedLive = ch.item },
                                            onClick = { zapOpen(ch.item, featuredFrench.map { it.item }) }
                                        )
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                            }
                        }

                        // 3. Toutes les catégories TV en direct (catégories françaises en tête)
                        items(shownCats, key = { it.id }) { c ->
                            LiveCategoryShelf(
                                account, c, "", { focusedLive = it }, open,
                                itemsOverride = if (c.id == TNT_ID) tntItems else null,
                                french = c.id == TNT_ID || c.id in frIds,
                                onOpenZap = zapOpen
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LiveCategoryShelf(
    account: Account,
    cat: Category,
    hubTag: String = "",
    onFocusItem: (Item) -> Unit,
    onOpen: (Item) -> Unit,
    itemsOverride: List<Item>? = null,
    french: Boolean? = null,
    onOpenZap: ((Item, List<Item>) -> Unit)? = null
) {
    val cv = Catalog.version
    val inMemory = remember(cat.id, cv) { Catalog.itemsInCategory(Kind.LIVE, cat.id) }
    var netData by remember(cat.id) { mutableStateOf(ItemCache.get(Kind.LIVE, cat.id)) }
    var failed by remember(cat.id) { mutableStateOf(false) }
    val data = itemsOverride ?: inMemory ?: netData

    LaunchedEffect(cat.id, inMemory) {
        if (itemsOverride == null && inMemory == null && netData == null) {
            val r = XtreamApi.items(account, Kind.LIVE, cat.id)
            if (r == null) failed = true else { ItemCache.put(Kind.LIVE, cat.id, r); netData = r }
        }
    }

    val isFr = french ?: remember(cat.name) { CategoryRules.isFrench(cat.name) }
    val cleanCatTitle = remember(cat.name) { liveCategoryTitle(cat.name) }
    // Une carte par chaîne (SD / HD / FHD / 4K regroupés).
    val grouped = remember(data) { data?.let { groupLiveQualities(it) } }

    // Dans Musique / Enfants, une rangée qui porte le nom de la page répète le titre : on la laisse sans intitulé.
    val redundant = hubTag.isNotEmpty() && normalize(cleanCatTitle).trim() in setOf("musique", "music", "enfants", "kids", "enfant")
    Row(
        Modifier.padding(bottom = if (redundant) 0.dp else 2.dp, end = 28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!redundant) Text(
            text = cleanCatTitle,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    Box(Modifier.height(118.dp)) {
        val l = if (hubTag == "kids") grouped?.filter { !HubExclusions.isExcluded(it.name) } else grouped
        if (l != null) {
            if (l.isEmpty()) {
                Text(stringResource(R.string.items_empty), color = SX.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            } else {
                LazyRow(
                    Modifier.bleedStart(),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp)
                ) {
                    items(l, key = { it.id }) { ch ->
                        LiveChannelCard(
                            item = ch,
                            hubTag = hubTag,
                            onFocus = { onFocusItem(ch) },
                            onClick = { if (onOpenZap != null) onOpenZap(ch, l) else onOpen(ch) }
                        )
                    }
                }
            }
        } else {
            Text(
                stringResource(if (failed) R.string.items_error else R.string.cat_loading),
                color = SX.Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
    Spacer(Modifier.height(10.dp))
}

/**
 * Carte de chaîne TV en direct inspirée de myCANAL / Apple TV :
 * - Clic OK = lance le direct
 * - Appui long OK (ou touche Menu) = ouvre la modale d'options (Ajouter / Retirer des favoris, Regarder le direct)
 */
@Composable
fun LiveChannelCard(
    item: Item,
    displayName: String? = null,
    channelNum: Int? = null,
    hubTag: String = "",
    onFocus: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val ctx = LocalContext.current
    val fv = FavVersion.v
    val isFav = remember(item.id, fv) { Favorites.contains(ctx, Kind.LIVE, item.id) }
    val shape = RoundedCornerShape(12.dp)
    val w = 166.dp
    val h = 98.dp
    val cleanName = remember(item.name, displayName) { displayName ?: cleanChannelName(item.name) }
    val quality = remember(item.name) { extractQualityBadge(item.name) }
    var showMenu by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    // Programme en cours de la chaîne (guide de l'abonnement), demandé seulement quand la vignette est survolée.
    val acc = remember { AccountStore.load(ctx) }
    val prog by produceState<Epg?>(null, item.id, focused) {
        value = null
        if (focused && acc != null && !acc.isM3u) {
            delay(350)
            value = EpgNow.current(acc, item.id)
        }
    }

    if (showMenu) {
        val sub = buildList {
            add(stringResource(R.string.badge_live))
            if (channelNum != null) add("N° $channelNum")
            if (quality != null) add(quality)
        }.joinToString("  ·  ")

        QuickActionModal(
            title = cleanName,
            subtitle = sub,
            image = item.image,
            landscapeImage = true,
            accentColor = LocalNavTheme.current.ring,
            actions = listOf(
                QuickAction(
                    label = stringResource(if (isFav) R.string.action_remove_fav else R.string.action_add_fav),
                    icon = if (isFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    accent = Color(0xFFEC4899),
                    onSelect = { Favorites.toggle(ctx, Kind.LIVE, item, hubTag) }
                ),
                QuickAction(
                    label = stringResource(R.string.action_watch_live),
                    icon = Icons.Filled.PlayArrow,
                    accent = Color(0xFF38BDF8),
                    onSelect = onClick
                )
            ),
            onDismiss = { showMenu = false }
        )
    }

    Box(
        Modifier
            .width(w)
            .height(h)
            .onFocusChanged { focused = it.hasFocus; if (it.hasFocus) onFocus?.invoke() }
            .tvFocus(
                shape = shape,
                cornerRadius = 12.dp,
                scale = 1.06f,
                onLongClick = { showMenu = true },
                onClick = onClick
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1A1E36), Color(0xFF101324))
                )
            )
            .border(1.dp, Color(0x1FFFFFFF), shape)
    ) {
        // Halo central subtil pour détacher les logos sombres comme les logos blancs
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (focused && prog != null) 52.dp else 70.dp) // le logo remonte pour laisser la place au programme
                .background(
                    Brush.radialGradient(
                        listOf(
                            Color(0x14FFFFFF),
                            Color.Transparent
                        ),
                        radius = 120f
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            val logoUrl = ChannelLogos.logo(item.name, item.image)
            if (!logoUrl.isNullOrBlank()) {
                NetImage(
                    url = logoUrl,
                    widthDp = 116.dp,
                    heightDp = 46.dp,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .fillMaxSize()
                )
            } else {
                Text(
                    text = cleanName.take(12),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }

        // En haut à gauche : N° de chaîne TNT et/ou badge Favori ♥
        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (channelNum != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xCC0B0D19))
                        .border(0.6.dp, Color(0x40FFFFFF), RoundedCornerShape(5.dp))
                        .padding(horizontal = 5.5.dp, vertical = 1.5.dp)
                ) {
                    Text(
                        text = channelNum.toString(),
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (isFav) FavBadge(size = 20.dp)
        }

        // Badge qualité (4K / FHD / HEVC / HD) en haut à droite
        if (quality != null) {
            val isUhd = quality == "4K"
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (isUhd) Color(0x33F6C244) else Color(0xAD0B0D19))
                    .border(
                        0.6.dp,
                        if (isUhd) Color(0x80F6C244) else Color(0x38FFFFFF),
                        RoundedCornerShape(5.dp)
                    )
                    .padding(horizontal = 5.dp, vertical = 1.5.dp)
            ) {
                Text(
                    text = quality,
                    color = if (isUhd) SX.Gold else Color(0xFFD6DCFA),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Bandeau inférieur vitré : nom de la chaîne et, sur la vignette survolée, le programme en cours avec sa jauge.
        val cur = if (focused) prog else null
        val bandH by androidx.compose.animation.core.animateDpAsState(if (cur != null) 48.dp else 27.dp, androidx.compose.animation.core.tween(160), label = "band")
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(bandH)
                .background(Color(0xE6090B16))
                .padding(horizontal = 9.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (isFav) Color(0xFFF472B6) else SX.Turquoise)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = cleanName,
                    color = if (cur != null) Color(0xFFB9BED6) else Color.White,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            if (cur != null) {
                val df = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = cur.title, color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(text = df.format(java.util.Date(cur.startMs)) + "–" + df.format(java.util.Date(cur.endMs)), color = SX.Muted, fontSize = 10.5.sp, maxLines = 1)
                }
                Spacer(Modifier.height(3.dp))
                val frac = ((System.currentTimeMillis() - cur.startMs).toFloat() / (cur.endMs - cur.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(2.5.dp).clip(CircleShape).background(Color(0x33FFFFFF))) {
                    Box(Modifier.fillMaxWidth(frac).height(2.5.dp).background(Color(0xE6FFFFFF)))
                }
            }
        }
    }
}
