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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Movie
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class Item(
    val id: String,
    val name: String,
    val image: String?,
    val ext: String? = null,
    val cat: String = "",
    val rating: Float = 0f,
    val kind: Kind = Kind.VOD,
    /** Autres qualités de la même chaîne (identifiants), de la meilleure à la moins bonne : essayées si la première ne démarre pas. */
    val alts: List<String> = emptyList()
)

/** Ouvre la fiche d'un titre (film ou série). */
typealias OpenDetail = (Kind, Item) -> Unit

/** Garde en mémoire les rangées déjà chargées en réseau (utilisé uniquement avant que le catalogue complet soit prêt). */
object ItemCache {
    private val map = object : LinkedHashMap<String, List<Item>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Item>>?) = size > 80
    }

    @Synchronized fun get(kind: Kind, cat: String): List<Item>? = map["${kind.key}:$cat"]
    @Synchronized fun put(kind: Kind, cat: String, v: List<Item>) { map["${kind.key}:$cat"] = v }
    @Synchronized fun clear() = map.clear()
}

@Composable
fun BrowseScreen(kind: Kind, account: Account, title: Int, onDetail: OpenDetail) {
    val ctx = LocalContext.current
    var managing by remember(kind) { mutableStateOf(false) }
    var wasManaging by remember(kind) { mutableStateOf(false) }
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
            delay(50)
            manageFocus.tryFocus()
        }
    }
    if (managing) {
        BackHandler { managing = false }
        CategoriesScreen(kind, account, title) { managing = false }
        return
    }
    val open: (Item) -> Unit = { it -> openItem(ctx, account, kind, it, onDetail) }
    val cv = Catalog.version
    val hv = HiddenVersion.v
    var cats by remember(kind, account, cv) { mutableStateOf(Catalog.categoriesOf(kind)) }
    var failed by remember(kind, account) { mutableStateOf(false) }
    val hidden = remember(kind, managing, hv) { HiddenStore.load(ctx, kind) }
    LaunchedEffect(kind, account) {
        if (cats == null) {
            val r = Catalog.ensureCategories(account, ctx.cacheDir, kind)
            if (r == null) failed = true else cats = r
        }
    }
    // Films et séries : présentation « à la Netflix » (rubriques en haut, puis genres). Chaînes : catégories seules.
    val netflix = kind != Kind.LIVE
    var facet by androidx.compose.runtime.saveable.rememberSaveable(kind) { mutableStateOf<String?>(null) }
    val tick = LocalResumeTick.current
    val histVer = HistoryVersion.v
    val recent by produceState(if (netflix) Catalog.newest(kind, hidden, 30) else emptyList<Entry>(), kind, cv, hidden) {
        value = withContext(Dispatchers.Default) { if (netflix) Catalog.newest(kind, hidden, 30) else emptyList() }
    }
    // « Les mieux notés » : parmi les titres connus (populaires sur les plateformes d'après TMDB) quand c'est possible, sinon la note brute.
    val top by produceState(if (netflix) Catalog.topRated(kind, hidden, 30) else emptyList<Entry>(), kind, cv, hidden, ProviderIndex.version) {
        value = withContext(Dispatchers.Default) {
            if (netflix) ProviderIndex.popularTop(kind, hidden, 30) ?: Catalog.topRated(kind, hidden, 30) else emptyList()
        }
    }
    val resume = remember(kind, tick, histVer, hidden) {
        val seenSeries = HashSet<String>()
        if (!netflix) emptyList() else History.load(ctx).filter {
            it.kind == kind.key && it.cat !in hidden &&
                (!it.key.startsWith("ep:") || seenSeries.add(cleanTitle(it.title.substringBefore(" · ").trim()).lowercase()))
        }
    }
    // TV en direct : programme en cours de la chaîne sélectionnée (légère attente avant la requête pendant le défilement).
    var focusedLive by remember { mutableStateOf<Item?>(null) }
    val navTheme = navThemeOf(
        when (kind) {
            Kind.VOD -> NavIconType.MOVIES
            Kind.SERIES -> NavIconType.SERIES
            Kind.LIVE -> NavIconType.LIVE
        }
    )
    Box(
        Modifier
            .fillMaxSize()
            .tvPageEntry(firstChipFocus)
            .cachedDraw {
                drawRect(
                    Brush.radialGradient(
                        listOf(navTheme.c1.copy(alpha = 0.24f), Color.Transparent),
                        center = Offset(size.width * 0.78f, size.height * 0.14f),
                        radius = size.width * 0.48f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(navTheme.c2.copy(alpha = 0.18f), Color.Transparent),
                        center = Offset(size.width * 0.24f, size.height * 0.20f),
                        radius = size.width * 0.38f
                    )
                )
            }
    ) {
    Column(Modifier.fillMaxSize().padding(top = 22.dp)) {
        PageHeader(
            when (kind) {
                Kind.VOD -> NavIconType.MOVIES
                Kind.SERIES -> NavIconType.SERIES
                Kind.LIVE -> NavIconType.LIVE
            },
            stringResource(title),
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
        val list = remember(cats, hidden) { cats?.filter { it.id !in hidden } }
        val norm = remember(list) { list?.associate { it.id to normalize(it.name) } ?: emptyMap() }
        val facets = remember(list) {
            if (netflix && list != null) FACETS.filter { f -> list.any { f.rx.containsMatchIn(norm[it.id] ?: "") } } else emptyList()
        }
        // Plateformes (Netflix, Paramount+, HBO Max, Canal+…) d'après TMDB : leurs titres présents dans l'abonnement, en pastilles et en rangées.
        // Celles qui ont déjà une vraie catégorie chez le fournisseur gardent cette catégorie.
        val coveredKeys = remember(facets) { facets.map { if (it.key == "hbo") "max" else it.key }.toSet() }
        val platformRows by produceState(emptyList<Pair<PlatformInfo, List<Entry>>>(), kind, cv, hidden, ProviderIndex.version, coveredKeys) {
            value = if (!netflix) emptyList() else withContext(Dispatchers.Default) {
                when {
                    Tmdb.enabled && ProviderIndex.ready -> ProviderIndex.rows(kind, hidden, coveredKeys)
                    // Sans TMDB : listes de titres connus (séries seulement).
                    kind == Kind.SERIES -> {
                        val all = Catalog.list(Kind.SERIES).filter { it.cat !in hidden && it.image != null }
                        SERIES_PLATFORMS.mapNotNull { pf ->
                            val hits = all.filter { pf.rx.containsMatchIn(normalize(cleanTitle(it.name))) }
                                .distinctBy { normalize(cleanTitle(it.name)) }.sortedByDescending { it.added }.take(40)
                            if (hits.size >= 3) PlatformInfo(pf.key, pf.label) to hits else null
                        }
                    }
                    else -> emptyList()
                }
            }
        }
        // Univers (Marvel, Star Wars, Disney, Harry Potter…) reconnus par le titre : pastilles et rangées.
        val initialUniv = remember(kind, cv, hidden) { if (netflix) Franchises.cached(kind, hidden) ?: emptyList() else emptyList() }
        val univRows by produceState(initialUniv, kind, cv, hidden) {
            if (!netflix) { value = emptyList(); return@produceState }
            Franchises.cached(kind, hidden)?.let { value = it; return@produceState }
            value = withContext(Dispatchers.Default) { Franchises.rowsFor(kind, hidden) }
        }
        val genreFacets = remember(facets) { facets.filter { it.res != 0 } }
        val platFacets = remember(facets) { facets.filter { it.res == 0 } }
        var group by remember(kind) { mutableStateOf<String?>(null) }
        // Barre de filtres toujours présente
        if (netflix) {
            LazyRow(
                Modifier.tvUpAction(manageFocus),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 4.dp)
            ) {
                // Trois groupes au lieu d'une longue file de pastilles : on ouvre un groupe, ses choix s'affichent sur la ligne du dessous.
                item(key = "all") { SelectChip(stringResource(R.string.facet_all), facet == null && group == null, Modifier.safeFocusTarget(firstChipFocus)) { facet = null; group = null } }
                if (genreFacets.isNotEmpty()) item(key = "g_genres") {
                    SelectChip(stringResource(R.string.group_genres), group == "genres") { group = if (group == "genres") null else "genres" }
                }
                if (platFacets.isNotEmpty() || platformRows.isNotEmpty()) item(key = "g_plats") {
                    SelectChip(stringResource(R.string.group_platforms), group == "plats") { group = if (group == "plats") null else "plats" }
                }
                if (univRows.isNotEmpty()) item(key = "g_univ") {
                    SelectChip(stringResource(R.string.hub_universes), group == "univ") { group = if (group == "univ") null else "univ" }
                }
            }
            if (group != null) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 4.dp)
                ) {
                    when (group) {
                        "genres" -> items(genreFacets, key = { it.key }) { f ->
                            SelectChip(stringResource(f.res), facet == f.key) { facet = if (facet == f.key) null else f.key }
                        }
                        "plats" -> {
                            items(platFacets, key = { it.key }) { f ->
                                SelectChip(f.text, facet == f.key) { facet = if (facet == f.key) null else f.key }
                            }
                            items(platformRows, key = { "plat_" + it.first.key }) { (pf, _) ->
                                SelectChip(pf.label, facet == "plat_" + pf.key) { facet = if (facet == "plat_" + pf.key) null else "plat_" + pf.key }
                            }
                        }
                        else -> items(univRows, key = { "univ_" + it.first.key }) { (uv, _) ->
                            SelectChip(uv.label, facet == "univ_" + uv.key) { facet = if (facet == "univ_" + uv.key) null else "univ_" + uv.key }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        when {
            list == null -> Text(
                stringResource(if (failed) R.string.cat_error else R.string.cat_loading),
                color = SX.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 28.dp)
            )
            list.isEmpty() -> Text(stringResource(R.string.cat_none_visible), color = SX.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 28.dp))
            else -> {
                val current = facets.firstOrNull { it.key == facet }
                val platformFacet = platformRows.firstOrNull { "plat_" + it.first.key == facet }
                    ?: univRows.firstOrNull { "univ_" + it.first.key == facet }
                val shown = remember(list, current, norm, platformFacet) {
                    when {
                        platformFacet != null -> emptyList()
                        current == null -> list
                        else -> list.filter { current.rx.containsMatchIn(norm[it.id] ?: "") }
                    }
                }
                // Ces rangées arrivent parfois une seconde après l'ouverture : tant qu'on n'a pas touché à la liste, on reste tout en haut
                // (sinon la liste garde la catégorie qu'elle affichait et « Ajoutés récemment » se retrouve au-dessus, hors de vue).
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                var userMoved by androidx.compose.runtime.saveable.rememberSaveable(kind, facet) { mutableStateOf(false) }
                LaunchedEffect(recent.size, top.size, resume.size, kind, facet) {
                    if (!userMoved) listState.scrollToItem(0)
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().onFocusChanged { if (it.hasFocus) userMoved = true },
                    state = listState,
                    contentPadding = PaddingValues(start = 28.dp, bottom = 20.dp)
                ) {
                    if (platformFacet != null) {
                        item(key = "platform_only") { EntryShelf(platformFacet.first.label, platformFacet.second, onOpen = open) }
                    }
                    if (netflix && current == null && platformFacet == null) {
                        if (resume.isNotEmpty()) item(key = "resume") {
                            Text(
                                stringResource(R.string.continue_watching),
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                            val fill = remember(resume, recent) {
                                if (resume.size >= 4) emptyList() else {
                                    val have = resume.mapTo(HashSet()) { cleanTitle(it.title.substringBefore(" · ").trim()).lowercase() }
                                    recent.filter { it.image != null && cleanTitle(it.name).lowercase() !in have }.take(4 - resume.size)
                                }
                            }
                            LazyRow(
                                Modifier.bleedStart(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 7.dp)
                            ) {
                                items(resume, key = { it.key }) { w -> ResumeCard(w, account, onDetail) }
                                items(fill, key = { "fill_" + it.id }) { e -> SuggestCard(e.toItem()) { open(e.toItem()) } }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        if (recent.isNotEmpty()) item(key = "recent") { EntryShelf(stringResource(R.string.row_recent), recent, onOpen = open) }
                        if (top.isNotEmpty()) item(key = "top") { EntryShelf(stringResource(R.string.row_top), top, onOpen = open) }
                        platformRows.take(3).forEach { (pf, hits) -> item(key = "plat_row_" + pf.key) { EntryShelf(pf.label, hits, onOpen = open) } }
                        univRows.take(4).forEach { (uv, hits) -> item(key = "univ_row_" + uv.key) { EntryShelf(uv.label, hits, onOpen = open) } }
                    }
                    items(shown, key = { it.id }) { c ->
                        Shelf(kind, account, c, if (kind == Kind.LIVE) ({ focusedLive = it }) else null, open)
                    }
                }            }
        }
    }
    }
}

private class Facet(val key: String, val rx: Regex, val res: Int = 0, val text: String = "")

/** Genres et plateformes reconnus dans les noms de catégories du fournisseur (comparés sans accents ni majuscules). */
private val FACETS = listOf(
    Facet("action", Regex("action"), R.string.facet_action),
    Facet("comedy", Regex("comed|humour"), R.string.facet_comedy),
    Facet("drama", Regex("dram"), R.string.facet_drama),
    Facet("thriller", Regex("thrill|suspens"), R.string.facet_thriller),
    Facet("horror", Regex("horreur|horror|epouv"), R.string.facet_horror),
    Facet("scifi", Regex("sci-?fi|science"), R.string.facet_scifi),
    Facet("anim", Regex("anim|cartoon|dessin"), R.string.facet_anim),
    Facet("docu", Regex("docu"), R.string.facet_docu),
    Facet("family", Regex("famil|enfant|kids|jeunesse"), R.string.facet_family),
    Facet("romance", Regex("romanc|romanti"), R.string.facet_romance),
    Facet("crime", Regex("polic|crime"), R.string.facet_crime),
    Facet("adventure", Regex("avent|advent"), R.string.facet_adventure),
    Facet("fantasy", Regex("fantas"), R.string.facet_fantasy),
    Facet("war", Regex("guerre|(^|[^a-z])war([^a-z]|$)"), R.string.facet_war),
    Facet("netflix", Regex("netflix"), text = "Netflix"),
    Facet("disney", Regex("disney"), text = "Disney+"),
    Facet("prime", Regex("amazon|prime"), text = "Prime Video"),
    Facet("apple", Regex("apple"), text = "Apple TV+"),
    Facet("canal", Regex("canal"), text = "Canal+"),
    Facet("hbo", Regex("hbo|(^|[^a-z])max([^a-z]|$)"), text = "Max"),
    Facet("paramount", Regex("paramount"), text = "Paramount+"),
)

/**
 * Plateformes sans catégorie propre chez ce fournisseur (aucune catégorie « Paramount » ou « HBO Max » dans le catalogue) :
 * leurs séries sont reconnues par le titre et regroupées dans des rangées et des pastilles dédiées.
 */
private class Platform(val key: String, val label: String, val rx: Regex)

private val SERIES_PLATFORMS = listOf(
    Platform(
        "paramount", "Paramount+",
        Regex("^(yellowstone|1883|1923|tulsa king|landman|mayor of kingstown|mobland|dexter(\\b|:)|star trek|evil\\b|the good fight|criminal minds|special ops: lioness|lioness|joe pickett|frasier\\b|school spirits|the agency|knuckles|sexy beast|couples therapy|the offer|war of the worlds|1992|blindspotting|rabbit hole|the last drive|tires)")
    ),
    Platform(
        "hbo", "HBO Max",
        Regex("^(game of thrones|house of the dragon|the last of us|euphoria|the white lotus|succession|the pitt|peacemaker|the penguin|dune: prophecy|true detective|sex and the city|and just like that|the sopranos|westworld|chernobyl|watchmen|big little lies|the gilded age|the righteous gemstones|hacks\\b|industry\\b|the idol|tokyo vice|a knight of the seven kingdoms|my brilliant friend|lovecraft country|the leftovers|veep|curb your enthusiasm|entourage|barry\\b|girls\\b|band of brothers|the pacific|rome\\b|deadwood|six feet under|the wire|boardwalk empire|true blood|furia|task\\b|lanterns|the chair company|mare of easttown|the undoing|the north water)")
    )
)

@Composable
fun EntryShelf(
    title: String,
    entries: List<Entry>,
    landscape: Boolean = false,
    onFocusItem: ((Item) -> Unit)? = null,
    onOpen: (Item) -> Unit
) {
    Text(
        title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 2.dp, end = 28.dp)
    )
    RowKey("e:$title") {
        LazyRow(
            Modifier.height(if (landscape) 118.dp else 198.dp).bleedStart(),
            horizontalArrangement = Arrangement.spacedBy(if (landscape) 11.dp else 10.dp),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp)
        ) {
            items(entries, key = { it.id }) { e ->
                val item = e.toItem()
                ItemCard(item, landscape, if (onFocusItem != null) ({ onFocusItem(item) }) else null) { onOpen(item) }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
fun Shelf(kind: Kind, account: Account, cat: Category, onFocusItem: ((Item) -> Unit)? = null, onOpen: (Item) -> Unit) {
    if (kind == Kind.LIVE) {
        LiveCategoryShelf(account, cat, "", { onFocusItem?.invoke(it) }, onOpen)
        return
    }
    val cv = Catalog.version
    val inMemory = remember(kind, cat.id, cv) { Catalog.itemsInCategory(kind, cat.id) }
    var netData by remember(kind, cat.id) { mutableStateOf(ItemCache.get(kind, cat.id)) }
    var failed by remember(kind, cat.id) { mutableStateOf(false) }
    val data = inMemory ?: netData

    LaunchedEffect(kind, cat.id, inMemory) {
        if (inMemory == null && netData == null) {
            val r = XtreamApi.items(account, kind, cat.id)
            if (r == null) failed = true else { ItemCache.put(kind, cat.id, r); netData = r }
        }
    }
    val landscape = false
    Text(
        cat.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 2.dp, end = 28.dp)
    )
    Box(Modifier.height(198.dp)) {
        val l = data
        if (l != null) {
            if (l.isEmpty()) {
                Text(stringResource(R.string.items_empty), color = SX.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            } else {
                RowKey("s:${kind.key}:${cat.id}") {
                    LazyRow(
                        Modifier.bleedStart(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp)
                    ) {
                        items(l, key = { it.id }) { ItemCard(it, landscape, if (onFocusItem != null) ({ onFocusItem(it) }) else null) { onOpen(it) } }
                    }
                }
            }
        } else {
            Text(stringResource(if (failed) R.string.items_error else R.string.cat_loading), color = SX.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
fun ItemCard(item: Item, landscape: Boolean, onFocus: (() -> Unit)? = null, showFavBadge: Boolean = true, extraFocus: SafeFocus? = null, onClick: () -> Unit) {
    if (landscape) {
        LiveChannelCard(item = item, modifier = if (extraFocus != null) Modifier.safeFocusTarget(extraFocus) else Modifier, onFocus = onFocus, onClick = onClick)
        return
    }
    val ctx = LocalContext.current
    val fv = FavVersion.v
    val histVer = HistoryVersion.v
    val isFav = remember(item.id, item.kind, fv) { Favorites.contains(ctx, item.kind, item.id) }
    var showMenu by remember { mutableStateOf(false) }
    val cardFocus = rememberCardFocus(item.kind.key + ":" + item.id)

    if (showMenu) {
        val watchKeys = remember(item.id, item.kind, histVer) { matchingWatchKeys(ctx, item.kind, item) }
        val actions = buildList {
            add(
                QuickAction(
                    label = stringResource(if (isFav) R.string.action_remove_fav else R.string.action_add_fav),
                    icon = if (isFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    accent = Color(0xFFEC4899),
                    onSelect = { Favorites.toggle(ctx, item.kind, item) }
                )
            )
            if (watchKeys.isNotEmpty()) {
                add(
                    QuickAction(
                        label = stringResource(R.string.action_remove_resume),
                        icon = Icons.Outlined.DeleteOutline,
                        accent = SX.Danger,
                        onSelect = { watchKeys.forEach { k -> History.remove(ctx, k) } }
                    )
                )
            }
            add(
                QuickAction(
                    label = stringResource(R.string.action_open_detail),
                    icon = Icons.Filled.PlayArrow,
                    accent = SX.VioletLight,
                    onSelect = onClick
                )
            )
        }
        val sub = buildList {
            add(stringResource(if (item.kind == Kind.SERIES) R.string.badge_series else R.string.badge_movie))
            yearOf(item.name)?.let { add(it) }
            if (item.rating >= 1f) add("★ " + "%.1f".format(item.rating))
        }.joinToString("  ·  ")

        QuickActionModal(
            title = cleanTitle(item.name),
            subtitle = sub,
            image = item.image,
            landscapeImage = false,
            accentColor = LocalNavTheme.current.ring,
            actions = actions,
            onDismiss = { showMenu = false }
        )
    }

    val shape = RoundedCornerShape(SX.RCard)
    val w = 119.dp
    val h = 178.dp
    // Le titre est déjà imprimé sur l'affiche : on ne l'écrit par-dessus que sur la jaquette sélectionnée (ou s'il n'y a pas d'affiche).
    var focused by remember { mutableStateOf(false) }
    val showTitle = focused || item.image == null
    Box(
        Modifier
            .width(w)
            .height(h)
            .then(if (extraFocus != null) Modifier.safeFocusTarget(extraFocus) else Modifier)
            .focusRequester(cardFocus.requester)
            .onFocusChanged { focused = it.hasFocus; cardFocus.note(it.hasFocus); if (it.hasFocus) onFocus?.invoke() }
            .tvFocus(
                shape = shape,
                cornerRadius = SX.RCard,
                scale = 1.06f,
                onLongClick = { showMenu = true },
                onClick = onClick
            )
            .clip(shape)
            .background(SX.Placeholder)
            .border(1.dp, Color(0x1FFFFFFF), shape)
    ) {
        if (item.image != null) {
            NetImage(
                url = item.image,
                widthDp = w,
                heightDp = h,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (showTitle) Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xF2060710)))
        )
        // Badge Favori ♥ en haut à gauche
        if (isFav && showFavBadge) FavBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
        // Note en haut à droite des affiches (films et séries).
        // (affichée seulement sur la carte focalisée)
        if (item.rating >= 1f && focused) {
            val badgeShape = RoundedCornerShape(SX.RBadge)
            Text(
                "★ " + "%.1f".format(item.rating),
                color = SX.Gold,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(badgeShape)
                    .background(Color(0x99000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        if (showTitle) Text(
            cleanTitle(item.name),
            color = Color.White,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 7.dp)
        )
    }
}
