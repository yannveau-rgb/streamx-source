package eu.streamx.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class HubTheme { KIDS, MUSIC }

/**
 * Filtre d'exclusion strict pour préserver la cohérence des univers thématiques.
 * Élimine le sport, le football (ex: beIN, beIN Family, chaînes arabes de foot),
 * et tout contenu adulte ou non adapté.
 */
object HubExclusions {
    val sportRx = Regex(
        "(^|[^a-z0-9])(sport|sports|foot|football|soccer|nba|nfl|nhl|ufc|wwe|dazn|bein|formula|f1|tennis|rugby|motogp|boxing|fight|kass|champion|champions|ligue|league|match|stadium|arena|koora|kora|afcon|euroleague|bundesliga|laliga|serie\\s*a|eredivisie|prime\\s*ligue)([^a-z0-9]|$)",
        RegexOption.IGNORE_CASE
    )
    val adultRx = Regex(
        "(^|[^a-z0-9])(adult|adults|xxx|porn|porno|eroti|sex|sexe|\\+18|18\\+|charm|playboy|hustler|penthouse|brazzers|dorcel|redlight|passie)([^a-z0-9]|$)",
        RegexOption.IGNORE_CASE
    )

    fun isSport(name: String): Boolean = sportRx.containsMatchIn(normalize(name))
    fun isAdult(name: String): Boolean = adultRx.containsMatchIn(normalize(name))
    fun isExcluded(name: String): Boolean = isSport(name) || isAdult(name)
}

internal object HubMatcher {
    // Catégories « Enfants » dont le NOM suffit à garantir le contenu.
    private val kidsStrongRx = Regex(
        "enfant|kids|jeunesse|junior|cartoon|nickel|gulli|tiji|canal\\s*j|boing|boomerang|toon|dessin|baby|bebe|nick\\s*jr|piwi|teletoon|famill|family",
        RegexOption.IGNORE_CASE
    )
    private val kidsFilmsStrongRx = Regex("disney|pixar|dreamworks", RegexOption.IGNORE_CASE)
    // Animation / animés : mélange d'enfants et d'adultes, donc seuls les titres reconnus passent.
    private val kidsWeakRx = Regex("anime|animation|manga", RegexOption.IGNORE_CASE)
    private val kidsNegCatRx = Regex("arab|(^|[^a-z0-9])ar([^a-z0-9]|$)|turqu|turk", RegexOption.IGNORE_CASE)
    /** Titres à ne jamais montrer à des enfants (horreur, animation adulte, séries pour adultes). */
    val kidsTitleNegRx = Regex(
        "vengeance|cauchemar|nightmare|horreur|horror|massacre|blood|zombie|deadpool|mortal\\s*kombat|deathstroke|psycho[\\s-]*pass|berserk|chainsaw|hell'?s\\s*paradise|griffin|rick\\s*(et|and)\\s*morty|harley\\s*quinn|cyberpunk|parasite|baki|kengan|gannibal|shogun|alien|daredevil|dopesick|meurtre|killer|slasher|exorcis|conjuring",
        RegexOption.IGNORE_CASE
    )
    val musicCatRx = Regex(
        "musi(que|c|cal)|concert|clip|radio|mtv|nrj|trace|mcm|mezzo|stingray|melody|rfm|cherie|fun\\s*radio|skyrock|jazz|classique|opera|karaoke|clubbing|dance|hip[\\s-]*hop|rock|pop",
        RegexOption.IGNORE_CASE
    )
    val versusRx = Regex("(^|[^a-z0-9])(vs\\.?|versus)([^a-z0-9]|$)")
    val concertTitleRx = Regex("(^|[^a-z0-9])(live|concert|tour|festival|unplugged|symphon|opera|olympia|zenith|bercy|stade|stadium|arena|wembley)([^a-z0-9]|$)")

    val kidsLiveTitleRx = Regex(
        "(^|[^a-z0-9])(gulli|tiji|piwi\\+?|canal\\s*j|teletoon\\+?|disney\\s*(channel|junior|xd|cinema)|nickelodeon|nick\\s*jr|cartoon\\s*network|boomerang|boing|toonami|mangas|j[\\s-]*one|baby\\s*tv|duck\\s*tv|kidz|okoo|ludo|dreamworks\\s*tv)([^a-z0-9]|$)",
        RegexOption.IGNORE_CASE
    )
    val kidsVodTitleRx = Regex(
        "(^|[^a-z0-9])(disney|pixar|dreamworks|studio\\s*ghibli|pat[\\s']*patrouille|paw\\s*patrol|peppa\\s*pig|bluey|miraculous|asterix|tintin|lucky\\s*luke|schtroumpf|smurf|pokemon|toy\\s*story|encanto|vaiana|moana|reine\\s*des\\s*neiges|frozen|roi\\s*lion|lion\\s*king|shrek|kung\\s*fu\\s*panda|madagascar|minions|moi\\s*moche|hotel\\s*transylvani|vice[\\s-]*versa|inside\\s*out|zootopi|raiponce|aladdin|mulan|hercule|tarzan|bambi|dumbo|peter\\s*pan|cendrillon|monstres\\s*et\\s*cie|indestructibles|incredibles|ratatouille|super\\s*mario\\s*bros)([^a-z0-9]|$)",
        RegexOption.IGNORE_CASE
    )

    val musicLiveTitleRx = Regex(
        "(^|[^a-z0-9])(mtv|nrj\\s*hits|trace\\s*(urban|latina|toca|caribbean|africa|gospel|muzika|vanilla)|mcm|mezzo|stingray|melody|rfm\\s*tv|cstar\\s*hits|m6\\s*music|clubbing\\s*tv|vevo|deluxe\\s*music|kiss\\s*tv|vh1|jukebox|fg\\s*tv|qwest\\s*tv|iconcerts|fun\\s*radio|skyrock|cherie\\s*fm|nostalgie|virgin\\s*radio|europe\\s*2|fip|france\\s*musique|mouv)([^a-z0-9]|$)|musique|music\\s*(tv|channel|hd|hits|live|box)",
        RegexOption.IGNORE_CASE
    )
    val musicVodTitleRx = Regex(
        "(^|[^a-z0-9])(concert|live\\s*(at|in|from)|unplugged|symphon|opera|philharmon|mtv\\s*unplugged|bohemian\\s*rhapsody|rocketman|whiplash|la\\s*la\\s*land|8\\s*mile|bob\\s*marley|taylor\\s*swift|beyonce|coldplay|daft\\s*punk|stromae|mylene\\s*farmer|indochine|johnny\\s*hallyday|hans\\s*zimmer|en\\s*concert)([^a-z0-9]|$)",
        RegexOption.IGNORE_CASE
    )

    /** 2 = le nom de la catégorie suffit ; 1 = seuls les titres reconnus passent ; 0 = pas pour cet univers. */
    fun categoryMode(theme: HubTheme, kind: Kind, name: String): Int {
        if (CategoryRules.isAdult(name) || HubExclusions.isExcluded(name)) return 0
        val n = normalize(name)
        return when (theme) {
            HubTheme.KIDS -> when {
                kidsNegCatRx.containsMatchIn(n) -> 0
                kind == Kind.LIVE && n.contains("disney") -> 0   // chaînes Disney+ : films et séries pour tous âges
                kidsStrongRx.containsMatchIn(n) -> 2
                kind == Kind.VOD && kidsFilmsStrongRx.containsMatchIn(n) -> 2
                kind != Kind.LIVE && (kidsWeakRx.containsMatchIn(n) || (kind == Kind.SERIES && n.contains("disney"))) -> 1
                else -> 0
            }
            HubTheme.MUSIC -> if (musicCatRx.containsMatchIn(n)) 2 else 0
        }
    }

    fun matchesCategory(theme: HubTheme, name: String): Boolean = categoryMode(theme, Kind.LIVE, name) == 2

    fun matchesEntryTitle(theme: HubTheme, kind: Kind, name: String): Boolean {
        val n = normalize(name)
        return when (theme) {
            HubTheme.KIDS ->
                !HubExclusions.isExcluded(name) && !kidsTitleNegRx.containsMatchIn(n) &&
                    (if (kind == Kind.LIVE) kidsLiveTitleRx else kidsVodTitleRx).containsMatchIn(n)
            HubTheme.MUSIC ->
                if (kind == Kind.LIVE) !HubExclusions.isExcluded(name) && !n.contains("lebanon") && musicLiveTitleRx.containsMatchIn(n)
                else !HubExclusions.isAdult(name) && !versusRx.containsMatchIn(n) && musicVodTitleRx.containsMatchIn(n)
        }
    }
}

private data class HubData(
    val liveCats: List<Category> = emptyList(),
    val vodCats: List<Category> = emptyList(),
    val seriesCats: List<Category> = emptyList(),
    val otherLive: List<Entry> = emptyList(),
    val recentVod: List<Entry> = emptyList(),
    val topVod: List<Entry> = emptyList(),
    val curatedVod: List<Entry> = emptyList(),
    val curatedSeries: List<Entry> = emptyList(),
    val universes: List<Pair<PlatformInfo, List<Entry>>> = emptyList()
)

/** Dernier résultat par univers : affiché aussitôt, le temps que le calcul à jour se termine. */
private object HubCache {
    private val map = HashMap<HubTheme, Pair<Pair<Int, Int>, HubData>>()
    @Synchronized fun get(t: HubTheme, cv: Int, hv: Int): HubData? = map[t]?.takeIf { it.first == (cv to hv) }?.second
    @Synchronized fun last(t: HubTheme): HubData? = map[t]?.second
    @Synchronized fun put(t: HubTheme, cv: Int, hv: Int, d: HubData) { map[t] = (cv to hv) to d }
}

private val riskyKidsCatRx = Regex("horror|horreur|thriller|western|spectacle|erotic")
private val riskyMusicCatRx = Regex("horror|horreur|thriller|western")

private fun computeHub(theme: HubTheme, hiddenLive: Set<String>, hiddenVod: Set<String>, hiddenSeries: Set<String>): HubData {
    val lAll = Catalog.categoriesOf(Kind.LIVE)?.filter { it.id !in hiddenLive && !CategoryRules.isAdult(it.name) } ?: emptyList()
    val vAll = Catalog.categoriesOf(Kind.VOD)?.filter { it.id !in hiddenVod && !CategoryRules.isAdult(it.name) } ?: emptyList()
    val sAll = Catalog.categoriesOf(Kind.SERIES)?.filter { it.id !in hiddenSeries && !CategoryRules.isAdult(it.name) } ?: emptyList()

    val lStrong = lAll.filter { HubMatcher.categoryMode(theme, Kind.LIVE, it.name) == 2 }
    val vStrong = vAll.filter { HubMatcher.categoryMode(theme, Kind.VOD, it.name) == 2 }
    val sStrong = sAll.filter { HubMatcher.categoryMode(theme, Kind.SERIES, it.name) == 2 }
    val lIds = lStrong.mapTo(HashSet()) { it.id }
    val vIds = vStrong.mapTo(HashSet()) { it.id }
    val sIds = sStrong.mapTo(HashSet()) { it.id }
    val vNames = vAll.associate { it.id to normalize(it.name) }
    val sNames = sAll.associate { it.id to normalize(it.name) }

    // Un film ou une série est retenu si sa catégorie est « sûre », ou si son titre est reconnu (hors catégories à risque).
    fun vodOk(e: Entry): Boolean {
        val cn = vNames[e.cat] ?: return false
        val n = normalize(e.name)
        return when (theme) {
            HubTheme.KIDS -> {
                if (HubMatcher.kidsTitleNegRx.containsMatchIn(n) || HubExclusions.isExcluded(e.name)) false
                else e.cat in vIds || (!riskyKidsCatRx.containsMatchIn(cn) && HubMatcher.matchesEntryTitle(theme, Kind.VOD, e.name))
            }
            HubTheme.MUSIC -> {
                if (HubExclusions.isAdult(e.name) || HubMatcher.versusRx.containsMatchIn(n)) false
                else e.cat in vIds ||
                    (!riskyMusicCatRx.containsMatchIn(cn) && HubMatcher.matchesEntryTitle(theme, Kind.VOD, e.name)) ||
                    (cn.contains("spectacle") && HubMatcher.concertTitleRx.containsMatchIn(n))
            }
        }
    }
    fun seriesOk(e: Entry): Boolean {
        val cn = sNames[e.cat] ?: return false
        if (theme == HubTheme.MUSIC) return e.cat in sIds && !HubExclusions.isAdult(e.name)
        val n = normalize(e.name)
        if (HubMatcher.kidsTitleNegRx.containsMatchIn(n) || HubExclusions.isExcluded(e.name)) return false
        return e.cat in sIds || (!riskyKidsCatRx.containsMatchIn(cn) && HubMatcher.matchesEntryTitle(theme, Kind.SERIES, e.name))
    }

    val vod = Catalog.list(Kind.VOD).asSequence().filter { vodOk(it) }.toList()
    val recentVod = vod.asSequence().filter { it.image != null }.sortedByDescending { it.added }.distinctBy { it.id }.take(30).toList()
    val topVod = vod.asSequence().filter { it.image != null && it.rating > 0f && it.rating <= Catalog.MAX_TRUSTED_RATING && Catalog.recentEnough(it) }
        .sortedWith(compareByDescending<Entry> { it.rating }.thenByDescending { it.added })
        .distinctBy { cleanTitle(it.name) }.take(30).toList()
    val curatedVod = vod.asSequence().filter { it.cat !in vIds && it.image != null }.sortedByDescending { it.added }.distinctBy { it.id }.take(40).toList()
    val curatedSeries = Catalog.list(Kind.SERIES).asSequence().filter { seriesOk(it) && it.cat !in sIds && it.image != null }
        .sortedByDescending { it.added }.distinctBy { it.id }.take(40).toList()

    // Chaînes reconnues par leur nom mais rangées ailleurs (ex. « NO Category ») : seulement les françaises, sans les catégories déjà montrées.
    val visibleLive = lAll.mapTo(HashSet()) { it.id }
    val otherLive = Catalog.list(Kind.LIVE).asSequence()
        .filter { it.cat in visibleLive && it.cat !in lIds && HubMatcher.matchesEntryTitle(theme, Kind.LIVE, it.name) && !nonFrTagRx.containsMatchIn(normalize(it.name)) }
        .distinctBy { cleanChannelName(it.name).lowercase() }.take(40).toList()

    // Univers (héros, studios, artistes…) : films et séries de l'univers regroupés par thème.
    val uvEntries = vod + Catalog.list(Kind.SERIES).filter { seriesOk(it) }
    val uvNorm = uvEntries.map { normalize(cleanTitle(it.name)) }
    val universes = Franchises.rows(if (theme == HubTheme.KIDS) Franchises.KIDS else Franchises.MUSIC, uvEntries, uvNorm, min = 3, exclusive = true)

    return HubData(lStrong, vStrong, sStrong, otherLive, recentVod, topVod, curatedVod, curatedSeries, universes)
}

/** Calcule les univers Enfants et Musique en tâche de fond dès que le catalogue est prêt : leur ouverture est alors immédiate. */
suspend fun prewarmHubs(ctx: android.content.Context) {
    for (theme in HubTheme.entries) {
        val cv = Catalog.version
        val hv = HiddenVersion.v
        if (HubCache.get(theme, cv, hv) != null) continue
        if (Catalog.categoriesOf(Kind.LIVE) == null || Catalog.categoriesOf(Kind.VOD) == null || Catalog.categoriesOf(Kind.SERIES) == null) return
        val d = withContext(Dispatchers.Default) {
            computeHub(theme, HiddenStore.load(ctx, Kind.LIVE), HiddenStore.load(ctx, Kind.VOD), HiddenStore.load(ctx, Kind.SERIES))
        }
        HubCache.put(theme, cv, hv, d)
    }
}
@Composable
fun ThemedHubScreen(theme: HubTheme, account: Account, onDetail: OpenDetail) {
    val ctx = LocalContext.current
    val cv = Catalog.version
    val hv = HiddenVersion.v
    val fv = FavVersion.v
    val hubTag = if (theme == HubTheme.KIDS) "kids" else "music"

    val hiddenLive = remember(hv) { HiddenStore.load(ctx, Kind.LIVE) }
    val hiddenVod = remember(hv) { HiddenStore.load(ctx, Kind.VOD) }
    val hiddenSeries = remember(hv) { HiddenStore.load(ctx, Kind.SERIES) }

    var filter by androidx.compose.runtime.saveable.rememberSaveable(theme) { mutableStateOf("all") }
    var managing by remember(theme) { mutableStateOf(false) }
    var wasManaging by remember(theme) { mutableStateOf(false) }
    val notifySub = LocalNotifySubScreen.current
    LaunchedEffect(managing) {
        notifySub(managing)
        if (managing) wasManaging = true
    }
    val manageFocus = rememberSafeFocus()
    val firstChipFocus = rememberSafeFocus()
    LaunchedEffect(managing) {
        if (!managing && wasManaging) {
            wasManaging = false
            kotlinx.coroutines.delay(50)
            manageFocus.tryFocus()
        }
    }
    if (managing) {
        BackHandler { managing = false }
        HubCategoriesScreen(theme, account, filter) { managing = false }
        return
    }

    var catsReady by remember(account, cv) {
        mutableStateOf(
            Catalog.categoriesOf(Kind.LIVE) != null &&
                Catalog.categoriesOf(Kind.VOD) != null &&
                Catalog.categoriesOf(Kind.SERIES) != null
        )
    }

    LaunchedEffect(account, cv) {
        if (!catsReady) {
            Catalog.ensureCategories(account, ctx.cacheDir, Kind.LIVE)
            Catalog.ensureCategories(account, ctx.cacheDir, Kind.VOD)
            Catalog.ensureCategories(account, ctx.cacheDir, Kind.SERIES)
            catsReady = true
        }
    }

    // Résultat précédent affiché tout de suite (puis remplacé) ; « Chargement… » tant qu'il n'y a rien : plus de faux « Aucun résultat ».
    val hubState by produceState<HubData?>(HubCache.last(theme), theme, cv, hv, catsReady) {
        HubCache.get(theme, cv, hv)?.let { value = it; return@produceState }
        val d = withContext(Dispatchers.Default) { computeHub(theme, hiddenLive, hiddenVod, hiddenSeries) }
        HubCache.put(theme, cv, hv, d)
        value = d
    }
    val hub = hubState ?: HubData()
    // Chaînes favorites appartenant à cet univers (Enfants ou Musique), épinglées dès l'arrivée sur le menu
    val favLive = remember(theme, fv, hv, hub.liveCats, hiddenLive) {
        val catIds = hub.liveCats.mapTo(HashSet()) { it.id }
        Favorites.load(ctx)
            .filter { f ->
                f.kind == Kind.LIVE.key &&
                    f.cat !in hiddenLive &&
                    (f.hub == hubTag || f.cat in catIds || HubMatcher.matchesEntryTitle(theme, Kind.LIVE, f.name))
            }
            .sortedBy { normalize(cleanChannelName(it.name)) }
            .map { it.toItem() }
    }

    // Films et séries favoris appartenant à cet univers (Enfants ou Musique)
    val favVod = remember(theme, fv, hv, hub.vodCats, hub.seriesCats, hiddenVod, hiddenSeries) {
        val catIds = (hub.vodCats.map { it.id } + hub.seriesCats.map { it.id }).toHashSet()
        Favorites.load(ctx)
            .filter { f ->
                f.kind != Kind.LIVE.key &&
                    ((f.kind == Kind.VOD.key && f.cat !in hiddenVod) || (f.kind == Kind.SERIES.key && f.cat !in hiddenSeries)) &&
                    (f.hub == hubTag || f.cat in catIds || HubMatcher.matchesEntryTitle(theme, if (f.kind == Kind.SERIES.key) Kind.SERIES else Kind.VOD, f.name))
            }
            .sortedBy { normalize(cleanTitle(it.name)) }
            .map { it.toItem() }
    }

    var focusedLive by remember(theme) { mutableStateOf<Item?>(null) }
    // Le programme en cours s'affiche sur la vignette de la chaîne survolée (plus de barre en bas).
    val clearLive: (Item) -> Unit = { focusedLive = null }
    val openLive: (Item) -> Unit = { openItem(ctx, account, Kind.LIVE, it, onDetail) }
    val openVod: (Item) -> Unit = { openItem(ctx, account, Kind.VOD, it, onDetail) }
    val openSeries: (Item) -> Unit = { openItem(ctx, account, Kind.SERIES, it, onDetail) }

    val navTheme = navThemeOf(if (theme == HubTheme.KIDS) NavIconType.KIDS else NavIconType.MUSIC)

    val hasLive = favLive.isNotEmpty() || hub.otherLive.isNotEmpty() || hub.liveCats.isNotEmpty()
    val hasVod = hub.recentVod.isNotEmpty() || hub.vodCats.isNotEmpty() || hub.curatedVod.isNotEmpty()
    val hasSeries = hub.seriesCats.isNotEmpty() || hub.curatedSeries.isNotEmpty()
    val hasUniv = hub.universes.isNotEmpty()
    val openAny: (Item) -> Unit = { openItem(ctx, account, it.kind, it, onDetail) }
    val isEmpty = !hasLive && !hasVod && !hasSeries && !hasUniv
    val stillLoading = hubState == null || Catalog.loading || !catsReady

    Box(
        Modifier
            .fillMaxSize()
            .tvPageEntry(firstChipFocus)
            .cachedDraw {
                drawRect(
                    Brush.radialGradient(
                        listOf(navTheme.c1.copy(alpha = if (theme == HubTheme.KIDS) 0.42f else 0.25f), Color.Transparent),
                        center = Offset(size.width * 0.78f, size.height * 0.14f),
                        radius = size.width * 0.48f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(navTheme.c2.copy(alpha = if (theme == HubTheme.KIDS) 0.34f else 0.18f), Color.Transparent),
                        center = Offset(size.width * 0.25f, size.height * 0.22f),
                        radius = size.width * 0.40f
                    )
                )
                if (theme == HubTheme.KIDS) {
                    // Touches de couleur façon dessin animé : jaune soleil en bas à gauche, turquoise en bas à droite.
                    drawRect(Brush.radialGradient(listOf(Color(0x40FFC933), Color.Transparent), center = Offset(size.width * 0.1f, size.height * 0.95f), radius = size.width * 0.35f))
                    drawRect(Brush.radialGradient(listOf(Color(0x3326D9C0), Color.Transparent), center = Offset(size.width * 0.92f, size.height * 0.85f), radius = size.width * 0.32f))
                }
            }
    ) {
        Column(Modifier.fillMaxSize().padding(top = 22.dp)) {
            PageHeader(
                if (theme == HubTheme.KIDS) NavIconType.KIDS else NavIconType.MUSIC,
                stringResource(if (theme == HubTheme.KIDS) R.string.nav_kids else R.string.nav_music),
                stringResource(if (theme == HubTheme.KIDS) R.string.hub_kids_sub else R.string.hub_music_sub),
                modifier = Modifier.padding(horizontal = 28.dp),
                trailing = {
                    ActionChip(
                        stringResource(R.string.cat_manage),
                        Modifier.safeFocusTarget(manageFocus).tvDownTarget(firstChipFocus),
                        icon = Icons.Outlined.Tune
                    ) { managing = true }
                }
            )
            Spacer(Modifier.height(10.dp))

            // Pastilles de filtrage + favoris rapides
            LazyRow(
                Modifier.tvUpAction(manageFocus),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 4.dp)
            ) {
                item(key = "all") {
                    SelectChip(stringResource(R.string.facet_all), filter == "all", Modifier.safeFocusTarget(firstChipFocus)) { filter = "all" }
                }
                val favTotal = favLive.size + favVod.size
                if (favTotal > 0) {
                    item(key = "favs") {
                        SelectChip(stringResource(R.string.favorites) + " ($favTotal)", filter == "favs", icon = androidx.compose.material.icons.Icons.Filled.Favorite) { filter = "favs" }
                    }
                }
                if (hasUniv) {
                    item(key = "univ") {
                        SelectChip(stringResource(R.string.hub_universes), filter == "univ") { filter = "univ" }
                    }
                }
                if (hasLive) {
                    item(key = "live") {
                        SelectChip(
                            stringResource(if (theme == HubTheme.KIDS) R.string.hub_kids_live else R.string.hub_music_live),
                            filter == "live"
                        ) { filter = "live" }
                    }
                }
                if (hasVod) {
                    item(key = "vod") {
                        SelectChip(
                            stringResource(if (theme == HubTheme.KIDS) R.string.movies else R.string.hub_music_vod),
                            filter == "vod"
                        ) { filter = "vod" }
                    }
                }
                if (hasSeries) {
                    item(key = "series") {
                        SelectChip(stringResource(R.string.series), filter == "series") { filter = "series" }
                    }
                }
                val ch = focusedLive
                if (ch != null) {
                    val isChFav = Favorites.contains(ctx, Kind.LIVE, ch.id)
                    item(key = "toggle_fav") {
                        ActionChip(
                            cleanChannelName(ch.name),
                            icon = if (isChFav) androidx.compose.material.icons.Icons.Filled.Favorite else androidx.compose.material.icons.Icons.Outlined.FavoriteBorder
                        ) {
                            Favorites.toggle(ctx, Kind.LIVE, ch, hubTag)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            if (isEmpty) {
                Text(
                    text = stringResource(
                        if (stillLoading) R.string.catalog_loading else R.string.search_empty
                    ),
                    color = SX.Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 28.dp, top = 8.dp)
                )
            } else {
                val showLive = filter == "all" || filter == "live" || filter == "favs"
                val showVod = filter == "all" || filter == "vod"
                val showSeries = filter == "all" || filter == "series"

                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 28.dp, top = 6.dp, bottom = 20.dp)
                ) {
                    // 1. Chaînes favorites de cet univers, épinglées dès l'arrivée sur le menu
                    if (favLive.isNotEmpty() && (filter == "all" || filter == "live" || filter == "favs")) {
                        item(key = "fav_hub_live") {
                            Row(
                                Modifier.padding(bottom = 3.dp, end = 28.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(Brush.horizontalGradient(listOf(navTheme.c1, navTheme.c2)))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Icon(androidx.compose.material.icons.Icons.Filled.Favorite, null, tint = Color.White, modifier = Modifier.size(11.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(if (theme == HubTheme.KIDS) R.string.favs_kids_live else R.string.favs_music_live),
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
                                        hubTag = hubTag,
                                        onFocus = { focusedLive = ch },
                                        onClick = { openLive(ch) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                    }

                    // 1b. Films et séries favoris de cet univers
                    if (favVod.isNotEmpty() && (filter == "all" || filter == "vod" || filter == "favs")) {
                        item(key = "fav_hub_vod") {
                            Row(
                                Modifier.padding(bottom = 3.dp, end = 28.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(Brush.horizontalGradient(listOf(navTheme.c1, navTheme.c2)))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Icon(androidx.compose.material.icons.Icons.Filled.Favorite, null, tint = Color.White, modifier = Modifier.size(11.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(if (theme == HubTheme.KIDS) R.string.favs_kids_vod else R.string.favs_music_vod),
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            LazyRow(
                                Modifier.height(198.dp).bleedStart(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp)
                            ) {
                                items(favVod, key = { "fav_vod_" + it.kind.key + ":" + it.id }) { item ->
                                    ItemCard(item, false, onFocus = { focusedLive = null }) {
                                        openItem(ctx, account, item.kind, item, onDetail)
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                    }

                    if (theme == HubTheme.KIDS && (filter == "all" || filter == "univ")) {
                        items(hub.universes, key = { "univ_" + it.first.key }) { (pf, hits) -> EntryShelf(pf.label, hits, onFocusItem = clearLive, onOpen = openAny) }
                    }
                    if (filter != "favs" && showLive) {
                        items(hub.liveCats, key = { "live_" + it.id }) { c ->
                            LiveCategoryShelf(account, c, hubTag, { focusedLive = it }, openLive, onOpenZap = { ch, l -> openLiveZap(ctx, account, ch, l) })
                        }
                        if (hub.otherLive.isNotEmpty()) {
                            item(key = "other_live") {
                                Text(
                                    stringResource(R.string.hub_other_live),
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(bottom = 2.dp, end = 28.dp)
                                )
                                LazyRow(
                                    Modifier.height(118.dp).bleedStart(),
                                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp)
                                ) {
                                    items(hub.otherLive, key = { it.id }) { e ->
                                        val item = e.toItem()
                                        LiveChannelCard(
                                            item = item,
                                            hubTag = hubTag,
                                            onFocus = { focusedLive = item },
                                            onClick = { openLive(item) }
                                        )
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                            }
                        }
                    }
                    if (theme == HubTheme.MUSIC && (filter == "all" || filter == "univ")) {
                        items(hub.universes, key = { "univ_" + it.first.key }) { (pf, hits) -> EntryShelf(pf.label, hits, onFocusItem = clearLive, onOpen = openAny) }
                    }
                    if (filter != "favs" && showVod) {
                        if (hub.recentVod.isNotEmpty()) {
                            item(key = "recent_vod") {
                                EntryShelf(stringResource(R.string.row_recent), hub.recentVod, onFocusItem = clearLive, onOpen = openVod)
                            }
                        }
                        if (hub.topVod.isNotEmpty()) {
                            item(key = "top_vod") {
                                EntryShelf(stringResource(R.string.row_top), hub.topVod, onFocusItem = clearLive, onOpen = openVod)
                            }
                        }
                        if (hub.curatedVod.isNotEmpty()) {
                            item(key = "curated_vod") {
                                EntryShelf(
                                    title = stringResource(if (theme == HubTheme.KIDS) R.string.movies else R.string.hub_music_vod),
                                    entries = hub.curatedVod,
                                    onFocusItem = clearLive, onOpen = openVod
                                )
                            }
                        }
                        items(hub.vodCats, key = { "vod_" + it.id }) { c ->
                            Shelf(Kind.VOD, account, c, clearLive, openVod)
                        }
                    }

                    if (filter != "favs" && showSeries) {
                        if (hub.curatedSeries.isNotEmpty()) {
                            item(key = "curated_series") {
                                EntryShelf(
                                    title = stringResource(R.string.series),
                                    entries = hub.curatedSeries,
                                    onFocusItem = clearLive, onOpen = openSeries
                                )
                            }
                        }
                        items(hub.seriesCats, key = { "series_" + it.id }) { c ->
                            Shelf(Kind.SERIES, account, c, clearLive, openSeries)
                        }
                    }
                }
            }
        }
    }
}
