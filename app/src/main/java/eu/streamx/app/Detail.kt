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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Bouton en pilule : discret au repos (principal un peu plus marqué), plein clair au focus (comme Google TV).
 * `active` : bascule activée (ex. « Dans ma liste »), l'icône prend la couleur de la rubrique.
 */
@Composable
fun PillButton(
    label: String, icon: ImageVector?, primary: Boolean, modifier: Modifier = Modifier,
    active: Boolean = false, onClick: () -> Unit
) {
    val th = LocalNavTheme.current
    val lowRam = LocalLowRamMode.current
    val pill = RoundedCornerShape(50)
    var focused by remember { mutableStateOf(false) }
    val pState = rememberFocusProgress(focused)
    val p = pState.value
    val fill = lerp(if (primary) Color(0x38FFFFFF) else Color(0x1AFFFFFF), SXFocus.Fill, p)
    val content = lerp(if (primary) Color.White else Color(0xE6FFFFFF), SXFocus.On, p)
    val iconTint = if (active) lerp(th.ring, lerp(th.c1, Color.Black, 0.35f), p) else content
    Row(
        modifier.height(40.dp)
            .onFocusChanged { focused = it.isFocused }
            .tvFocus(pill, scale = 1.06f, decorate = false, onClick = onClick)
            .focusShadow({ pState.value }, pill, enabled = !lowRam)
            .clip(pill).background(fill)
            .then(if (primary) Modifier else Modifier.border(1.dp, Color(0x29FFFFFF).copy(alpha = 0.16f * (1f - p)), pill))
            .padding(horizontal = 20.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = content, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Bouton en pilule sans icône. */
@Composable
fun PillButton(label: String, primary: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) =
    PillButton(label, null, primary, modifier, false, onClick)

/** Bouton « Bande-annonce » : n'apparaît que si une bande-annonce est trouvée (TMDB). */
@Composable
fun TrailerButton(kind: Kind, item: Item) {
    val ctx = LocalContext.current
    val key by produceState<String?>(null, kind, item.id) { value = Tmdb.trailerKey(kind, item.name) }
    val k = key ?: return
    PillButton(stringResource(R.string.trailer), Icons.Outlined.Movie, false) { openTrailer(ctx, k) }
}

/** Bouton « Ma liste » : bascule l'ajout / le retrait du titre. */
@Composable
fun ListButton(kind: Kind, item: Item) {
    val ctx = LocalContext.current
    val fv = FavVersion.v
    val inList = remember(fv, item.id) { Favorites.contains(ctx, kind, item.id) }
    PillButton(
        stringResource(if (inList) R.string.in_my_list else R.string.my_list),
        if (inList) Icons.Outlined.Check else Icons.Outlined.Add, false, active = inList
    ) { Favorites.toggle(ctx, kind, item) }
}

@Composable
fun PosterFrame(image: String?, widthDp: Dp = 130.dp, heightDp: Dp = 195.dp, glowColor: Color = Color(0x457B52FF), modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(SX.RTile)
    Box(
        modifier
            .width(widthDp)
            .height(heightDp)
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(listOf(glowColor, Color.Transparent), center = center, radius = size.width * 1.1f),
                    radius = size.width * 1.1f,
                    center = center
                )
            }
            .clip(shape)
            .background(SX.Placeholder)
            .border(
                0.9.dp,
                Brush.verticalGradient(listOf(Color(0x55FFFFFF), Color(0x18FFFFFF))),
                shape
            )
    ) {
        NetImage(
            url = image,
            widthDp = widthDp,
            heightDp = heightDp,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** Fond atmosphérique en haut des fiches Film et Série : reprend la palette et l'affiche en fondu doux. */
@Composable
fun DetailAmbientBackdrop(item: Item) {
    if (LocalLowRamMode.current) return
    val p = rememberPosterPalette(item.image, item.id)
    PublishAccent(p.first)
    DetailAmbientBackdropBase(item, p)
}

@Composable
private fun DetailAmbientBackdropBase(item: Item, p: Triple<Color, Color, Color>) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(260.dp)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Black, 0.55f to Color(0x99000000), 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn
                )
            }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .cachedDraw {
                    drawRect(
                        Brush.radialGradient(
                            listOf(p.first.copy(alpha = 0.28f), p.second.copy(alpha = 0.14f), Color.Transparent),
                            center = Offset(size.width * 0.72f, size.height * 0.35f),
                            radius = size.width * 0.55f
                        )
                    )
                }
        )
        if (item.image != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .width(340.dp)
                    .height(240.dp)
                    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.horizontalGradient(0f to Color.Transparent, 0.45f to Color.Black, 1f to Color.Black),
                            blendMode = BlendMode.DstIn
                        )
                    }
            ) {
                NetImage(
                    url = item.image,
                    widthDp = 340.dp,
                    heightDp = 240.dp,
                    contentScale = ContentScale.Crop,
                    alpha = 0.20f,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

fun playMovie(ctx: android.content.Context, account: Account, item: Item, fromStart: Boolean) {
    val key = "vod:" + item.id
    val pos = if (fromStart) 0L else History.posFor(ctx, key)
    ctx.startActivity(playIntent(ctx, streamUrl(account, "movie", item.id, item.ext ?: "mp4"), item.name, false, item.image, key, pos, "vod", item.cat))
}

/** Fiche d'un film : affiche, infos, lecture / reprise, ma liste et titres de la même catégorie. */
@Composable
fun MovieScreen(account: Account, item: Item, onBack: () -> Unit, onDetail: OpenDetail) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val tick = LocalResumeTick.current
    val histVer = HistoryVersion.v
    val resumeMs = remember(item.id, tick, histVer) { History.posFor(ctx, "vod:" + item.id) }
    val cv = Catalog.version
    val similar by produceState(emptyList<Entry>(), item.id, cv) {
        value = withContext(Dispatchers.Default) {
            Catalog.list(Kind.VOD).asSequence().filter { it.cat == item.cat && it.id != item.id && it.image != null }
                .sortedByDescending { it.added }.distinctBy { it.id }.take(20).toList()
        }
    }
    val p = rememberPosterPalette(item.image, item.id)
    Box(Modifier.fillMaxSize()) {
        DetailAmbientBackdrop(item)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 28.dp, top = 24.dp)) {
            Row(Modifier.padding(end = 28.dp)) {
                PosterFrame(item.image, widthDp = 130.dp, heightDp = 195.dp, glowColor = p.first.copy(alpha = 0.42f))
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    val badge = RoundedCornerShape(SX.RBadge)
                    Text(
                        stringResource(R.string.badge_movie),
                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.glass(badge, 0.95f).padding(horizontal = 9.dp, vertical = 2.5.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        cleanTitle(item.name), color = Color.White, fontSize = 28.sp, lineHeight = 28.sp, fontFamily = SX.Condensed,
                        fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    val meta = listOfNotNull(yearOf(item.name), item.rating.takeIf { it > 0f }?.let { "★ " + "%.1f".format(it) }).joinToString("  ·  ")
                    if (meta.isNotEmpty()) Text(meta, color = SX.Text2, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    val info by produceState<Info?>(InfoCache.get("vod:" + item.id), item.id) { value = XtreamApi.vodInfo(account, item.id) }
                    InfoBlock(info, Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(14.dp))
                    val playFocus = remember(item.id) { FocusRequester() }
                    LaunchedEffect(item.id) {
                        delay(50)
                        runCatching { playFocus.requestFocus() }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (resumeMs > 20_000) {
                            PillButton(stringResource(R.string.resume_at, (resumeMs / 60000).toInt()), Icons.Filled.PlayArrow, true, Modifier.focusRequester(playFocus)) { playMovie(ctx, account, item, false) }
                            PillButton(stringResource(R.string.restart), Icons.Outlined.Replay, false) { playMovie(ctx, account, item, true) }
                        } else {
                            PillButton(stringResource(R.string.play), Icons.Filled.PlayArrow, true, Modifier.focusRequester(playFocus)) { playMovie(ctx, account, item, true) }
                        }
                        ListButton(Kind.VOD, item)
                        TrailerButton(Kind.VOD, item)
                    }
                }
            }
            if (similar.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.similar), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 2.dp))
                LazyRow(
                    Modifier.bleedStart(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 7.dp)
                ) {
                    items(similar, key = { it.id }) { e -> ItemCard(e.toItem(), false) { onDetail(Kind.VOD, e.toItem()) } }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
