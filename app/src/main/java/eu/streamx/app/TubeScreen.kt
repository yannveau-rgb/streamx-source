package eu.streamx.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface TubeLoad {
    class Done(val shelves: List<TShelf>) : TubeLoad
    class Videos(val list: List<TVideo>, val failed: Boolean = false) : TubeLoad
}

/** Contenu d'une page, avec son identifiant : on garde l'ancien contenu affiché tant que la même page se recharge. */
private class TubeState(val page: String, val data: TubeLoad, val q: String = "")

/**
 * Onglet « Tube » : un accueil à la YouTube (étagères par thème, tendances), la recherche, les cœurs et l'historique.
 * Aucun compte : les cœurs et les thèmes cochés restent sur la télé et orientent les suggestions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TubeScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme = navThemeOf(NavIconType.TUBE)
    val resumeTick = LocalResumeTick.current
    var likeTick by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    var refocus by remember { mutableIntStateOf(0) }
    var onboarded by remember { mutableStateOf(TubeStore.onboarded(ctx)) }
    var picking by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 accueil, 1 tendances, 2 cœurs, 3 historique
    var query by remember { mutableStateOf("") }
    // La recherche s'affiche en direct dès 2 lettres ; vider la barre revient à la page en cours.
    val searching = query.trim().length >= 2
    // La recherche prime sur le sélecteur de thèmes (même au tout premier lancement).
    val showPicker = (!onboarded || picking) && !searching
    val pageId = when { showPicker -> "pick"; searching -> "search"; else -> "tab$tab" }

    // Les puces du haut : quand un bouton disparaît sous le doigt (Réessayer, Continuer, Retour…), le focus leur revient.
    val chipReq = remember { List(5) { FocusRequester() } }
    LaunchedEffect(refocus) {
        if (refocus > 0) { delay(60); runCatching { chipReq[if (picking || !onboarded) 4 else tab.coerceIn(0, 3)].requestFocus() } }
    }

    val likedIds by produceState(emptySet<String>(), likeTick, resumeTick) {
        value = withContext(Dispatchers.IO) { TubeStore.likes(ctx).map { it.id }.toSet() }
    }

    // Retour : seulement quand une recherche ou le sélecteur de thèmes est ouvert (sinon MainShell ramène à la barre latérale).
    var inTube by remember { mutableStateOf(false) }
    BackHandler(enabled = inTube && (query.isNotEmpty() || picking)) {
        if (picking) picking = false else query = ""
        refocus++
    }

    fun play(v: TVideo, queue: List<TVideo> = emptyList()) = ctx.startActivity(tubePlayIntent(ctx, v, queue))
    fun like(v: TVideo) {
        scope.launch {
            withContext(Dispatchers.IO) { TubeStore.toggleLike(ctx, v); Tube.invalidate() }
            likeTick++
        }
    }
    fun goTab(t: Int) {
        if (!onboarded) { TubeStore.setInterests(ctx, TubeStore.interests(ctx)); onboarded = true }
        tab = t; query = ""; picking = false
    }
    var focusResults by remember { mutableStateOf(false) }
    fun retry() { Tube.invalidate(); reload++; if (searching) focusResults = true else refocus++ }

    // Chargement des pages : réseau pour l'accueil, les tendances et la recherche ; lecture locale pour cœurs et historique.
    // La page « Accueil » n'est PAS rechargée au retour du lecteur : elle garde son défilement et son focus.
    val localKey = if (!showPicker && !searching && tab >= 2) resumeTick else 0
    val state by produceState<TubeState?>(null, pageId, if (searching) query.trim() else "", onboarded, reload, localKey) {
        when {
            showPicker -> value = TubeState(pageId, TubeLoad.Videos(emptyList()))
            searching -> {
                delay(450)
                val q = query.trim()
                // Échec réseau et « aucun résultat » ne se disent pas pareil.
                val r: List<TVideo>? = tubeSafe<List<TVideo>?>(null) { Tube.search(ctx, q) }
                value = TubeState(pageId, TubeLoad.Videos(r ?: emptyList(), failed = r == null), q)
            }
            tab == 0 -> value = TubeState(pageId, TubeLoad.Done(tubeSafe(emptyList()) { Tube.homeShelves(ctx) }))
            tab == 1 -> value = TubeState(pageId, TubeLoad.Videos(tubeSafe(emptyList()) { Tube.trending(ctx) }))
            tab == 2 -> value = TubeState(pageId, TubeLoad.Videos(withContext(Dispatchers.IO) { TubeStore.likes(ctx) }))
            else -> value = TubeState(pageId, TubeLoad.Videos(withContext(Dispatchers.IO) { TubeStore.history(ctx) }))
        }
    }
    val shown = state?.takeIf { it.page == pageId }?.data
    val firstResult = remember { FocusRequester() }
    // La flèche bas depuis la barre ne descend dans la grille que si elle correspond à la requête en cours (pas à la précédente).
    val hasResults = searching && state?.q == query.trim() && (shown as? TubeLoad.Videos)?.list?.isNotEmpty() == true

    // Après « Réessayer » en recherche : le bouton disparaît quand les résultats arrivent, le focus passe alors à la première carte.
    LaunchedEffect(focusResults, hasResults) {
        if (focusResults && hasResults) { delay(80); runCatching { firstResult.requestFocus() }; focusResults = false }
    }

    Box(
        Modifier.fillMaxSize().onFocusChanged { inTube = it.hasFocus }.cachedDraw {
            drawRect(Brush.radialGradient(listOf(theme.c1.copy(alpha = 0.22f), Color.Transparent), center = Offset(size.width * 0.80f, size.height * 0.12f), radius = size.width * 0.48f))
            drawRect(Brush.radialGradient(listOf(theme.c2.copy(alpha = 0.20f), Color.Transparent), center = Offset(size.width * 0.22f, size.height * 0.20f), radius = size.width * 0.40f))
        }
    ) {
        Column(Modifier.fillMaxSize().padding(top = 22.dp)) {
            PageHeader(
                NavIconType.TUBE, stringResource(R.string.nav_tube), stringResource(R.string.tube_subtitle),
                modifier = Modifier.padding(horizontal = 28.dp)
            )
            Spacer(Modifier.height(12.dp))
            val plain = !searching && !showPicker
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 4.dp)) {
                item("home") { SelectChip(stringResource(R.string.home), tab == 0 && plain, Modifier.focusRequester(chipReq[0])) { goTab(0) } }
                item("trend") { SelectChip(stringResource(R.string.tube_trending), tab == 1 && plain, Modifier.focusRequester(chipReq[1])) { goTab(1) } }
                item("hearts") { SelectChip(stringResource(R.string.tube_hearts), tab == 2 && plain, Modifier.focusRequester(chipReq[2]), Icons.Filled.ThumbUp) { goTab(2) } }
                item("hist") { SelectChip(stringResource(R.string.tube_history), tab == 3 && plain, Modifier.focusRequester(chipReq[3])) { goTab(3) } }
                item("topics") { SelectChip(stringResource(R.string.tube_topics), showPicker, Modifier.focusRequester(chipReq[4])) { picking = true; query = "" } }
            }
            Spacer(Modifier.height(6.dp))

            when {
                showPicker -> InterestPicker { chosen ->
                    TubeStore.setInterests(ctx, chosen)
                    Tube.invalidate()
                    onboarded = true; picking = false; tab = 0; reload++; refocus++
                }
                searching -> VideoGrid(
                    shown, likedIds, emptyText = stringResource(if ((shown as? TubeLoad.Videos)?.failed == true) R.string.tube_offline else R.string.tube_none), firstReq = firstResult,
                    onPlay = { v, q -> TubeStore.addRecent(ctx, state?.q ?: query); play(v, q) }, onLike = { like(it) }, onRetry = { retry() }
                )
                tab == 0 -> when (val c = shown) {
                    is TubeLoad.Done -> if (c.shelves.isEmpty()) Offline { retry() } else Shelves(c.shelves, likedIds, { play(it) }, { like(it) })
                    else -> Loading()
                }
                tab == 1 -> VideoGrid(shown, likedIds, emptyText = stringResource(R.string.tube_offline), onPlay = { v, q -> play(v, q) }, onLike = { like(it) }, onRetry = { retry() })
                tab == 2 -> VideoGrid(shown, likedIds, emptyText = stringResource(R.string.tube_empty_hearts), onPlay = { v, q -> play(v, q) }, onLike = { like(it) }, onRetry = null, queueAfter = true)
                else -> {
                    if ((shown as? TubeLoad.Videos)?.list?.isNotEmpty() == true) {
                        Row(Modifier.padding(horizontal = 28.dp, vertical = 4.dp)) {
                            SelectChip(stringResource(R.string.tube_clear_history), false) {
                                scope.launch { withContext(Dispatchers.IO) { TubeStore.clearHistory(ctx); Tube.invalidate() }; reload++; refocus++ }
                            }
                        }
                    }
                    VideoGrid(shown, likedIds, emptyText = stringResource(R.string.tube_empty_history), onPlay = { v, q -> play(v, q) }, onLike = { like(it) }, onRetry = null)
                }
            }
        }
        // Barre de recherche : par-dessus la page, en haut à droite, pour que son panneau de suggestions la recouvre.
        TubeSearchBar(
            query, { query = it; if (it.isNotEmpty()) picking = false },
            onSubmit = { TubeStore.addRecent(ctx, it) },
            downTarget = if (hasResults) firstResult else null,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 28.dp).zIndex(2f)
        )
    }
}

@Composable
private fun Loading() {
    Text(stringResource(R.string.tube_loading), color = SX.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp))
}

@Composable
private fun Offline(onRetry: () -> Unit) {
    Column(Modifier.padding(horizontal = 28.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.tube_offline), color = SX.Muted, fontSize = 13.sp)
        Row { SelectChip(stringResource(R.string.retry), false) { onRetry() } }
    }
}

@Composable
private fun Shelves(shelves: List<TShelf>, liked: Set<String>, onPlay: (TVideo) -> Unit, onLike: (TVideo) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp)) {
        items(shelves, key = { it.key }) { s ->
            Text(s.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 6.dp))
            LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = SX.Gutter, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(s.videos, key = { it.id }) { v ->
                    TubeCard(v, v.id in liked, Modifier.width(236.dp), onClick = { onPlay(v) }, onLike = { onLike(v) })
                }
            }
        }
    }
}

@Composable
private fun VideoGrid(
    state: TubeLoad?, liked: Set<String>, emptyText: String,
    onPlay: (TVideo, List<TVideo>) -> Unit, onLike: (TVideo) -> Unit, onRetry: (() -> Unit)?, queueAfter: Boolean = false,
    firstReq: FocusRequester? = null
) {
    val list = (state as? TubeLoad.Videos)?.list
    when {
        list == null -> Loading()
        list.isEmpty() -> if (emptyText.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 28.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(emptyText, color = SX.Muted, fontSize = 13.sp)
                if (onRetry != null) Row { SelectChip(stringResource(R.string.retry), false) { onRetry() } }
            }
        }
        else -> LazyVerticalGrid(
            GridCells.Adaptive(236.dp), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(list, key = { it.id }) { v ->
                TubeCard(
                    v, v.id in liked, Modifier, onClick = { onPlay(v, if (queueAfter) list.filter { it.id != v.id }.shuffled() else emptyList()) }, onLike = { onLike(v) },
                    focusMod = if (firstReq != null && v.id == list.first().id) Modifier.focusRequester(firstReq) else Modifier
                )
            }
        }
    }
}

/** Carte vidéo : vignette 16/9, durée, cœur ; titre sur deux lignes puis chaîne, vues et date. */
@Composable
private fun TubeCard(v: TVideo, liked: Boolean, modifier: Modifier, onClick: () -> Unit, onLike: () -> Unit, focusMod: Modifier = Modifier) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(SX.RCard)
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .then(focusMod)
                .tvFocus(shape, cornerRadius = SX.RCard, onLongClick = onLike, onClick = onClick)
                .clip(shape).background(SX.Placeholder)
        ) {
            NetImage(v.thumb, 236.dp, 133.dp, modifier = Modifier.fillMaxSize())
            if (v.live) Badge(stringResource(R.string.player_live).uppercase(), Modifier.align(Alignment.BottomEnd), navThemeOf(NavIconType.TUBE).let { th -> Brush.horizontalGradient(listOf(androidx.compose.ui.graphics.lerp(th.c1, Color.Black, 0.45f), androidx.compose.ui.graphics.lerp(th.c2, Color.Black, 0.45f))) })
            else if (v.durationSec > 0) Badge(Tube.formatDuration(v.durationSec), Modifier.align(Alignment.BottomEnd), null)
            if (liked) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(Color(0xCC14112B)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.ThumbUp, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(v.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        val meta = listOf(v.channel, Tube.formatViews(ctx, v.views), Tube.formatAgo(ctx, v.at)).filter { it.isNotBlank() }.joinToString("  ·  ")
        if (meta.isNotEmpty()) Text(meta, color = SX.Muted, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier, brush: Brush?) {
    Text(
        text, color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.padding(6.dp).clip(RoundedCornerShape(5.dp))
            .then(if (brush != null) Modifier.background(brush) else Modifier.background(Color(0xCC0B0D19)))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** Choix des thèmes (premier lancement ou « Mes thèmes ») : les étagères de l'accueil en découlent. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InterestPicker(onDone: (Set<String>) -> Unit) {
    val ctx = LocalContext.current
    var chosen by remember { mutableStateOf(TubeStore.interests(ctx)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.tube_pick_title), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.tube_pick_sub), color = SX.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (it in TubeInterests.all) {
                SelectChip(it.label(ctx), it.key in chosen) { chosen = if (it.key in chosen) chosen - it.key else chosen + it.key }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row { PillButton(stringResource(R.string.tube_continue), true) { onDone(chosen) } }
    }
}
