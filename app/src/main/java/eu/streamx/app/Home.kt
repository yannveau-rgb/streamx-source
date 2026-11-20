package eu.streamx.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

val LocalResumeTick = androidx.compose.runtime.compositionLocalOf { 0 }

internal val heroPalette = listOf(
    Triple(Color(0xFFF4A860), Color(0xFFC86438), Color(0xFF171829)),
    Triple(Color(0xFF56D8FF), Color(0xFF2858C8), Color(0xFF0D152C)),
    Triple(Color(0xFFF6B44E), Color(0xFFB8452A), Color(0xFF1F1118)),
    Triple(Color(0xFF4AF2C8), Color(0xFF187A8C), Color(0xFF0A1D2A)),
    Triple(Color(0xFFC28BFF), Color(0xFF6B38E8), Color(0xFF150F2E)),
    Triple(Color(0xFFFF8FA3), Color(0xFFA2354F), Color(0xFF260F1A)),
)

/** Ouvre un titre : film ou série = sa fiche, chaîne = lecture directe. */
fun openItem(ctx: android.content.Context, account: Account, kind: Kind, item: Item, onDetail: OpenDetail) {
    when (kind) {
        Kind.SERIES, Kind.VOD -> onDetail(kind, item)
        Kind.LIVE -> ctx.startActivity(
            playIntent(ctx, streamUrl(account, "live", item.id, "m3u8"), item.name, true, alts = item.alts.map { streamUrl(account, "live", it, "m3u8") })
        )
    }
}

@Composable
fun HomeScreen(
    account: Account,
    bellDot: Boolean,
    onSearch: (String) -> Unit,
    onBell: () -> Unit,
    onDetail: OpenDetail,
    onNavigate: (Int) -> Unit
) {
    val ctx = LocalContext.current
    val tick = LocalResumeTick.current
    val histVer = HistoryVersion.v
    val hv = HiddenVersion.v
    val cv = Catalog.version
    val hiddenVod = remember(hv) { HiddenStore.load(ctx, Kind.VOD) }
    val hiddenSeries = remember(hv) { HiddenStore.load(ctx, Kind.SERIES) }
    val hiddenLive = remember(hv) { HiddenStore.load(ctx, Kind.LIVE) }

    // Un titre d'une catégorie masquée disparaît aussi de « Reprendre la lecture ».
    val history = remember(tick, histVer, hv, hiddenVod, hiddenSeries, hiddenLive) {
        // Une seule carte par série (la plus récente) : pas un épisode par ligne.
        val seenSeries = HashSet<String>()
        History.load(ctx).filter { w ->
            if (w.key.startsWith("ep:") && !seenSeries.add(cleanTitle(w.title.substringBefore(" · ").trim()).lowercase())) return@filter false
            when (w.kind) {
                Kind.VOD.key -> w.cat !in hiddenVod
                Kind.SERIES.key -> w.cat !in hiddenSeries
                Kind.LIVE.key -> w.cat !in hiddenLive
                else -> true
            }
        }
    }

    // Les tris portent sur des dizaines de milliers de titres : on les fait hors du fil principal.
    val newMovies by produceState(emptyList<Entry>(), cv, hv) { value = withContext(Dispatchers.Default) { Catalog.newest(Kind.VOD, hiddenVod, 24) } }
    val newSeries by produceState(emptyList<Entry>(), cv, hv) { value = withContext(Dispatchers.Default) { Catalog.newest(Kind.SERIES, hiddenSeries, 24) } }
    // Vraies tendances de la semaine, retrouvées dans le catalogue (nécessite la clé TMDB ; sinon rangées absentes).
    val trendMovies by produceState(emptyList<Entry>(), cv, hv) {
        val t = Tmdb.trending(Kind.VOD)
        value = withContext(Dispatchers.Default) { Catalog.matchTrends(Kind.VOD, t, hiddenVod) }
    }
    val trendSeries by produceState(emptyList<Entry>(), cv, hv) {
        val t = Tmdb.trending(Kind.SERIES)
        value = withContext(Dispatchers.Default) { Catalog.matchTrends(Kind.SERIES, t, hiddenSeries) }
    }
    // Affiches du moment mémorisées pour l'écran de veille.
    LaunchedEffect(trendMovies, trendSeries, newMovies, newSeries) {
        val all = (trendMovies + trendSeries + newMovies + newSeries).distinctBy { it.id }
        DreamPosters.save(ctx, all.mapNotNull { e -> sanitizeImageUrl(e.image)?.let { it to cleanTitle(e.name) } }.take(40))
    }
    val heroes = remember(newMovies, newSeries, trendMovies, trendSeries) {
        val out = ArrayList<Entry>()
        val s = trendSeries.ifEmpty { newSeries }
        val m = trendMovies.ifEmpty { newMovies }
        for (i in 0 until 2) { s.getOrNull(i)?.let { out.add(it) }; m.getOrNull(i)?.let { out.add(it) } }
        out
    }

    val rowPadding = PaddingValues(horizontal = 28.dp, vertical = 7.dp)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Barre de recherche et cloche font partie de la bannière : elles défilent avec elle (zIndex : les suggestions recouvrent la suite).
        Box(Modifier.zIndex(1f)) {
            Hero(account, heroes, onDetail)
            Row(
                Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HomeSearchBar(account, onSearch, onDetail)
                RoundGlassButton(Icons.Outlined.Notifications, bellDot, onBell, description = stringResource(R.string.notifications))
            }
        }
        Spacer(Modifier.height(4.dp))
        // Recommandation locale, sans serveur : des titres de la même catégorie que le dernier film ou la dernière série regardé.
        val because = remember(history, cv) {
            history.firstOrNull { (it.kind == Kind.VOD.key || it.kind == Kind.SERIES.key) && it.cat.isNotEmpty() }?.let { w ->
                val kind = if (w.kind == Kind.SERIES.key) Kind.SERIES else Kind.VOD
                val self = w.key.removePrefix("vod:")
                val items = Catalog.itemsInCategory(kind, w.cat)?.filter { it.id != self && it.image != null }?.take(24) ?: emptyList()
                if (items.isEmpty()) null else Triple(w, kind, items)
            }
        }
        if (history.isNotEmpty()) {
            SectionTitle(R.string.continue_watching, topPadding = 10.dp)
            // Peu de titres en cours : on complète la rangée avec des suggestions de même format (sinon un grand vide à droite).
            val fill = if (history.size < 4) because?.third?.takeLast(4 - history.size).orEmpty() else emptyList()
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = rowPadding) {
                items(history, key = { it.key }) { w -> ResumeCard(w, account, onDetail) }
                items(fill, key = { "fill_" + it.id }) { e -> SuggestCard(e) { openItem(ctx, account, because!!.second, e, onDetail) } }
            }
        }
        because?.let { (w, kind, items) ->
            Text(
                stringResource(R.string.because_watched, cleanTitle(w.title).replace(Regex("\\s*[·\\-–]\\s*S\\d+\\s*E\\d+.*$"), "")), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 10.dp, bottom = 8.dp)
            )
            RowKey("home_because") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = rowPadding) {
                    items(items, key = { "because_" + it.id }) { it -> ItemCard(it, false) { openItem(ctx, account, kind, it, onDetail) } }
                }
            }
        }
        if (trendMovies.isNotEmpty()) {
            SectionTitle(R.string.trend_movies, topPadding = 10.dp)
            RowKey("home_trend_movies") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = rowPadding) {
                    items(trendMovies, key = { it.id }) { e -> ItemCard(e.toItem(), false) { openItem(ctx, account, Kind.VOD, e.toItem(), onDetail) } }
                }
            }
        }
        if (trendSeries.isNotEmpty()) {
            SectionTitle(R.string.trend_series, topPadding = 10.dp)
            RowKey("home_trend_series") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = rowPadding) {
                    items(trendSeries, key = { it.id }) { e -> ItemCard(e.toItem(), false) { openItem(ctx, account, Kind.SERIES, e.toItem(), onDetail) } }
                }
            }
        }
        if (newMovies.isNotEmpty()) {
            SectionTitle(R.string.new_movies, topPadding = 10.dp)
            RowKey("home_new_movies") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = rowPadding) {
                    items(newMovies, key = { it.id }) { e -> ItemCard(e.toItem(), false) { openItem(ctx, account, Kind.VOD, e.toItem(), onDetail) } }
                }
            }
        }
        if (newSeries.isNotEmpty()) {
            SectionTitle(R.string.new_series, topPadding = 10.dp)
            RowKey("home_new_series") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = rowPadding) {
                    items(newSeries, key = { it.id }) { e -> ItemCard(e.toItem(), false) { openItem(ctx, account, Kind.SERIES, e.toItem(), onDetail) } }
                }
            }
        }
        if (newMovies.isEmpty() && newSeries.isEmpty()) {
            Text(
                stringResource(if (Catalog.loading) R.string.catalog_loading else if (Catalog.failed) R.string.catalog_error else R.string.nothing_visible),
                color = SX.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 28.dp, top = 16.dp)
            )
        }
        Spacer(Modifier.height(26.dp))
    }
}

/** Carte de suggestion au même format que « Reprendre la lecture » (sans progression). */
@Composable
fun SuggestCard(item: Item, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val cardBase = Color(0xFF14172B)
    Box(
        Modifier
            .width(250.dp)
            .height(64.dp)
            .tvFocus(shape = shape, cornerRadius = 12.dp, scale = 1.05f, onClick = onClick)
            .clip(shape)
            .background(cardBase)
            .glass(shape, 0.85f)
    ) {
        Box(Modifier.width(110.dp).fillMaxHeight().background(Color(0xFF22254A))) {
            NetImage(url = item.image, widthDp = 110.dp, heightDp = 64.dp, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.width(110.dp).fillMaxHeight().background(Brush.horizontalGradient(0.38f to Color.Transparent, 1f to cardBase)))
        Column(Modifier.align(Alignment.CenterStart).padding(start = 104.dp, end = 12.dp)) {
            Text(cleanTitle(item.name), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.suggestion), color = SX.Muted, fontSize = 9.5.sp)
        }
    }
}

@Composable
fun ResumeCard(w: Watch, account: Account, onDetail: OpenDetail) {
    val ctx = LocalContext.current
    val fv = FavVersion.v
    val cv = Catalog.version
    val shape = RoundedCornerShape(12.dp)
    val notStarted = w.dur <= 0L
    val remainingMin = ((w.dur - w.pos) / 60000).toInt().coerceAtLeast(1)
    val leftText = if (notStarted) stringResource(R.string.next_episode) else stringResource(R.string.minutes_left, remainingMin)
    val cardBase = Color(0xFF14172B)
    var showMenu by remember { mutableStateOf(false) }

    val favTarget = remember(w.key, w.title, cv) { resolveWatchFavItem(w) }
    val isFav = remember(favTarget, fv) {
        favTarget != null && Favorites.contains(ctx, favTarget.first, favTarget.second.id)
    }

    val resumePlay: () -> Unit = {
        // Série : on ouvre sa fiche d'abord, pour qu'un retour arrière y ramène (liste des épisodes) et non à l'accueil.
        if (favTarget != null && favTarget.first == Kind.SERIES) onDetail(favTarget.first, favTarget.second)
        ctx.startActivity(playIntent(ctx, unmaskUrl(account, w.url), w.title, false, w.image, w.key, w.pos, w.kind, w.cat))
    }

    if (showMenu) {
        val actions = buildList {
            add(
                QuickAction(
                    label = stringResource(R.string.action_remove_resume),
                    icon = Icons.Outlined.DeleteOutline,
                    accent = Color(0xFFFF4D6D),
                    onSelect = { History.remove(ctx, w.key) }
                )
            )
            if (favTarget != null) {
                add(
                    QuickAction(
                        label = stringResource(if (isFav) R.string.action_remove_fav else R.string.action_add_fav),
                        icon = if (isFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        accent = Color(0xFFEC4899),
                        onSelect = { Favorites.toggle(ctx, favTarget.first, favTarget.second) }
                    )
                )
            }
            add(
                QuickAction(
                    label = stringResource(R.string.action_resume_play),
                    icon = Icons.Filled.PlayArrow,
                    accent = SX.VioletLight,
                    onSelect = resumePlay
                )
            )
        }
        QuickActionModal(
            title = cleanTitle(w.title),
            subtitle = stringResource(R.string.continue_watching) + "  ·  " + stringResource(R.string.minutes_left, remainingMin),
            image = w.image,
            landscapeImage = false,
            accentColor = Color(0xFFFF4D6D),
            actions = actions,
            onDismiss = { showMenu = false }
        )
    }

    Box(
        Modifier
            .width(250.dp)
            .height(64.dp)
            .tvFocus(
                shape = shape,
                cornerRadius = 12.dp,
                scale = 1.05f,
                onLongClick = { showMenu = true },
                onClick = resumePlay
            )
            .clip(shape)
            .background(cardBase)
            .glass(shape, 0.85f)
    ) {
        Box(Modifier.width(110.dp).fillMaxHeight().background(Color(0xFF22254A))) {
            NetImage(
                url = w.image,
                widthDp = 110.dp,
                heightDp = 64.dp,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            Modifier
                .width(110.dp)
                .fillMaxHeight()
                .background(Brush.horizontalGradient(0.38f to Color.Transparent, 1f to cardBase))
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 9.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color(0x66080A14))
                .border(1.2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(13.dp))
        }
        if (isFav) FavBadge(Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 6.dp), size = 17.dp)
        Column(Modifier.align(Alignment.CenterStart).padding(start = 104.dp, end = if (isFav) 24.dp else 12.dp)) {
            Text(cleanTitle(w.title), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(leftText, color = SX.Muted, fontSize = 9.5.sp)
            Spacer(Modifier.height(5.dp))
            if (!notStarted) Box(Modifier.fillMaxWidth().height(2.5.dp).clip(CircleShape).background(Color(0x33FFFFFF))) {
                Box(
                    Modifier
                        .fillMaxWidth((w.pos.toFloat() / w.dur.coerceAtLeast(1L)).coerceIn(0.02f, 1f))
                        .fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(SX.Violet, SX.VioletLight)))
                )
            }
        }
    }
}

@Composable
fun Hero(
    account: Account,
    entries: List<Entry>,
    onDetail: OpenDetail
) {
    val ctx = LocalContext.current
    val lowRam = LocalLowRamMode.current
    var index by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    LaunchedEffect(entries.size, lowRam) {
        index = 0
        if (lowRam) return@LaunchedEffect
        while (entries.size > 1) {
            delay(8000)
            if (!paused) index = (index + 1) % entries.size
        }
    }
    Box(Modifier.fillMaxWidth().height(250.dp)) {
        val e = entries.getOrNull(index)
        // En mode allégé, on évite la couche Offscreen + BlendMode.DstIn coûteuse sur les GPU modestes
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (lowRam) Modifier else Modifier
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                Brush.verticalGradient(0f to Color.Black, 0.52f to Color.Black, 0.82f to Color(0x66000000), 1f to Color.Transparent),
                                blendMode = BlendMode.DstIn
                            )
                        }
                )
        ) {
            Crossfade(targetState = e, animationSpec = tween(if (lowRam) 0 else 500), label = "heroBackdrop") { cur ->
                val p = rememberPosterPalette(cur?.image, cur?.id ?: "")
                if (cur != null && cur === e) PublishAccent(p.first)
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(p.third, p.second.copy(alpha = 0.75f), p.first.copy(alpha = 0.9f))))) {
                    if (cur?.image != null) {
                        if (!lowRam) {
                            // Affiche d'ambiance fondue sur son bord gauche (aucune cassure verticale)
                            Box(
                                Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .fillMaxWidth(0.64f)
                                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                                    .drawWithContent {
                                        drawContent()
                                        drawRect(
                                            Brush.horizontalGradient(0f to Color.Transparent, 0.35f to Color.Black, 1f to Color.Black),
                                            blendMode = BlendMode.DstIn
                                        )
                                    }
                            ) {
                                NetImage(
                                    url = cur.image,
                                    widthDp = 320.dp,
                                    heightDp = 250.dp,
                                    contentScale = ContentScale.Crop,
                                    alpha = 0.30f,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        val card = RoundedCornerShape(14.dp)
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 64.dp, bottom = 32.dp)
                                .width(100.dp)
                                .height(150.dp)
                                .then(
                                    if (lowRam) Modifier else Modifier.drawBehind {
                                        drawCircle(
                                            Brush.radialGradient(listOf(p.first.copy(alpha = 0.50f), Color.Transparent), center = center, radius = size.width * 1.15f),
                                            radius = size.width * 1.15f,
                                            center = center
                                        )
                                    }
                                )
                                .clip(card)
                                .border(0.8.dp, Color(0x45FFFFFF), card)
                        ) {
                            NetImage(
                                url = cur.image,
                                widthDp = 100.dp,
                                heightDp = 150.dp,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .cachedDraw(essential = true) {
                        drawRect(Brush.horizontalGradient(0f to Color(0xF50B0D19), 0.40f to Color(0xC20B0D19), 0.72f to Color(0x330B0D19), 0.92f to Color.Transparent))
                        if (lowRam) {
                            drawRect(Brush.verticalGradient(0.55f to Color.Transparent, 1f to SX.BgTop))
                        }
                    }
            )
        }

        if (e != null) {
            Column(Modifier.align(Alignment.BottomStart).padding(start = 28.dp, bottom = 24.dp).width(340.dp)) {
                Crossfade(targetState = e, animationSpec = tween(400), label = "heroText") { cur ->
                    Column {
                        val badge = RoundedCornerShape(SX.RBadge)
                        Text(
                            stringResource(if (cur.kind == Kind.SERIES) R.string.badge_series else R.string.badge_movie),
                            color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .glass(badge, 0.95f)
                                .padding(horizontal = 9.dp, vertical = 2.5.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            cleanTitle(cur.name).uppercase(), color = Color.White, fontSize = 30.sp, lineHeight = 29.sp,
                            fontFamily = SX.Condensed, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        val sub = listOfNotNull(yearOf(cur.name), cur.rating.takeIf { it > 0f }?.let { "★ " + "%.1f".format(it) }).joinToString("  ·  ")
                        if (sub.isNotEmpty()) Text(sub, color = SX.Text2, fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.onFocusChanged { paused = it.hasFocus }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton(stringResource(if (e.kind == Kind.SERIES) R.string.episodes else R.string.play), Icons.Filled.PlayArrow, true) {
                        if (e.kind == Kind.VOD) playMovie(ctx, account, e.toItem(), false) else openItem(ctx, account, e.kind, e.toItem(), onDetail)
                    }
                    ListButton(e.kind, e.toItem())
                }
            }
        } else {
            Text(
                stringResource(if (Catalog.loading) R.string.catalog_loading else if (Catalog.failed) R.string.catalog_error else R.string.nothing_visible),
                color = SX.Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomStart).padding(start = 28.dp, bottom = 28.dp)
            )
        }

        if (entries.size > 1) {
            // Indicateur façon « stories » : de fines barres, la courante se remplit pendant les 8 s avant le changement.
            val progress = remember { androidx.compose.animation.core.Animatable(0f) }
            var lastIdx by remember { mutableIntStateOf(-1) }
            LaunchedEffect(index, paused, lowRam) {
                if (lowRam) { progress.snapTo(1f); return@LaunchedEffect }
                if (lastIdx != index) { progress.snapTo(0f); lastIdx = index }
                if (!paused) progress.animateTo(1f, tween(((1f - progress.value) * 8000).toInt().coerceAtLeast(1), easing = androidx.compose.animation.core.LinearEasing))
            }
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 64.dp, bottom = 12.dp)
                    .width(100.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                entries.indices.forEach { i ->
                    val on = i == index
                    val w by androidx.compose.animation.core.animateDpAsState(if (on) 26.dp else 8.dp, tween(300), label = "heroSeg")
                    Box(
                        Modifier
                            .width(w)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(Color(0x40FFFFFF))
                    ) {
                        if (on) Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).background(Color.White))
                    }
                }
            }
        }
    }
}
