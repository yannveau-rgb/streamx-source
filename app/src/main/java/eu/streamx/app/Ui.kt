package eu.streamx.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import kotlinx.coroutines.delay

/**
 * Mise en cache GPU des dégradés et halos : utilise une DisplayList matérielle (`graphicsLayer` + `drawWithCache`)
 * sans allouer de bitmap logiciel géant en RAM CPU.
 * En mode allégé (`LocalLowRamMode`), les halos décoratifs non essentiels (`essential = false`) sont ignorés pour zéro overdraw GPU.
 */
@Composable
fun Modifier.cachedDraw(essential: Boolean = false, block: DrawScope.() -> Unit): Modifier {
    val lowRam = LocalLowRamMode.current
    if (lowRam && !essential) return this
    // Les halos sont des dégradés doux plein écran : les redessiner à chaque image coûte ~50 ms de GPU sur la télé (mesuré).
    // On les « cuit » une fois dans une image 8 fois plus petite, qu'on étire ensuite : invisible à l'œil, presque gratuit.
    return this.drawWithCache {
        val scaleDown = 8
        val w = size.width.toInt()
        val h = size.height.toInt()
        if (w < 16 || h < 16) {
            onDrawBehind(block)
        } else {
            val bw = (w / scaleDown).coerceAtLeast(2)
            val bh = (h / scaleDown).coerceAtLeast(2)
            val bmp = androidx.compose.ui.graphics.ImageBitmap(bw, bh)
            val owner = this
            androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
                owner, layoutDirection, androidx.compose.ui.graphics.Canvas(bmp), size
            ) {
                withTransform({ scale(bw / size.width, bh / size.height, pivot = Offset.Zero) }) { block() }
            }
            onDrawBehind {
                drawImage(
                    bmp, dstSize = androidx.compose.ui.unit.IntSize(w, h),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Low
                )
            }
        }
    }
}

/**
 * Effet « verre » : reflet clair en haut, fond translucide, liseré lumineux qui s'estompe sur les bords.
 * `strength` règle l'intensité (1 = normal, moins = plus discret).
 */
fun Modifier.glass(shape: Shape, strength: Float = 1f): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(
                Color(1f, 1f, 1f, (0.20f * strength).coerceAtMost(0.16f)),
                Color(1f, 1f, 1f, (0.06f * strength).coerceAtMost(0.20f))
            )
        )
    )
    .border(
        0.8.dp,
        Brush.linearGradient(
            listOf(
                Color(1f, 1f, 1f, (0.50f * strength).coerceAtMost(0.72f)),
                Color(1f, 1f, 1f, 0.08f),
                Color(1f, 1f, 1f, 0.10f)
            )
        ),
        shape
    )

/**
 * Expression régulière pour détecter les hash d'affiches TMDB stockés par les serveurs Xtream
 * ex : "/images/gyUUNoz2XTN8eatTXPCPKGXFbXl_big.jpg" ou avec un domaine mort (voir DEAD_IMAGE_HOST)
 */
/** Serveur de l'abonnement actif, pour retrouver les images dont le domaine d'origine n'existe plus. */
object ImageHosts {
    @Volatile var provider: String? = null
}

private val deadImageHostRe: Regex? = BuildConfig.DEAD_IMAGE_HOST.takeIf { it.isNotBlank() }
    ?.let { Regex("https?://" + Regex.escape(it) + "(:\\d+)?", RegexOption.IGNORE_CASE) }

val TMDB_HASH_REGEX = Regex("""/images/([A-Za-z0-9]{20,45})(?:_big)?\.(?:jpe?g|png|webp)""", RegexOption.IGNORE_CASE)

/**
 * Image réseau optimisée pour la télé : décode l'image exactement à la taille d'affichage demandée
 * au lieu de charger des affiches 2000×3000 en pleine résolution en mémoire.
 */
fun sanitizeImageUrl(raw: String?): String? {
    if (raw.isNullOrBlank() || raw == "null") return null
    var s = raw.trim()
    if (s.startsWith("//")) s = "https:$s"
    if (s.startsWith("http://image.tmdb.org", ignoreCase = true)) {
        s = "https://image.tmdb.org" + s.substring("http://image.tmdb.org".length)
    } else if (s.startsWith("http://api.themoviedb.org", ignoreCase = true)) {
        s = "https://api.themoviedb.org" + s.substring("http://api.themoviedb.org".length)
    } else if (s.startsWith("http://m.media-amazon.com", ignoreCase = true)) {
        s = "https://m.media-amazon.com" + s.substring("http://m.media-amazon.com".length)
    } else if (s.startsWith("http://ia.media-imdb.com", ignoreCase = true)) {
        s = "https://ia.media-imdb.com" + s.substring("http://ia.media-imdb.com".length)
    }

    // Récupération directe sur le CDN officiel TMDB pour toute affiche encodée par hash
    // Résout instantanément les domaines IPTV morts (nom lu dans BuildConfig.DEAD_IMAGE_HOST)
    val tmdbMatch = TMDB_HASH_REGEX.find(s)
    if (tmdbMatch != null) {
        val hash = tmdbMatch.groupValues[1].removeSuffix("_big")
        return "https://image.tmdb.org/t/p/w500/$hash.jpg"
    }

    // Ancien domaine d'images mort : on le remplace par le serveur de l'abonnement actif (jamais par une adresse codée en dur).
    val provider = ImageHosts.provider
    if (provider != null && deadImageHostRe != null && s.contains(BuildConfig.DEAD_IMAGE_HOST, ignoreCase = true)) {
        s = deadImageHostRe.replace(s, provider)
    }

    if (s.contains(' ')) {
        s = s.replace(" ", "%20")
    }
    return s
}

@Composable
fun NetImage(
    url: String?,
    widthDp: Dp,
    heightDp: Dp,
    contentScale: ContentScale = ContentScale.Crop,
    alpha: Float = 1f,
    modifier: Modifier = Modifier
) {
    val cleanUrl = remember(url) { sanitizeImageUrl(url) }
    if (cleanUrl.isNullOrBlank()) return
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val lowRam = LocalLowRamMode.current
    val req = remember(cleanUrl, widthDp, heightDp, lowRam) {
        val factor = if (lowRam) 0.72f else 1f
        val wPx = with(density) { (widthDp * factor).roundToPx().coerceAtLeast(48) }
        val hPx = with(density) { (heightDp * factor).roundToPx().coerceAtLeast(48) }
        ImageRequest.Builder(ctx)
            .data(cleanUrl)
            .size(wPx, hPx)
            .precision(Precision.INEXACT)
            .listener(
                onError = { _, res ->
                    android.util.Log.e("StreamXImg", "ERR ${runCatching { java.net.URI(cleanUrl).host }.getOrNull()}: ${res.throwable.javaClass.simpleName}")
                }
            )
            .build()
    }
    AsyncImage(
        model = req,
        contentDescription = null,
        contentScale = contentScale,
        alpha = alpha,
        modifier = modifier
    )
}

/**
 * Champ de saisie unifié en verre dépoli avec liseré dégradé violet-turquoise au focus.
 */
@Composable
fun GlassInputBox(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String = "",
    shape: Shape = RoundedCornerShape(50),
    password: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    fontSizeSp: Float = 13f,
    horizontalPadding: Dp = 16.dp,
    verticalPadding: Dp = 10.dp,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .glass(shape, 0.9f)
            .background(if (focused) Color(0x29FFFFFF) else Color.Transparent, shape)
            .border(
                width = if (focused) 2.dp else 0.6.dp,
                color = if (focused) Color(0xF2FFFFFF) else Color(0x38FFFFFF),
                shape = shape
            )
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
    ) {
        if (value.isEmpty() && hint.isNotEmpty()) {
            Text(hint, color = SX.Muted, fontSize = fontSizeSp.sp, maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = fontSizeSp.sp),
            cursorBrush = SolidColor(LocalNavTheme.current.ring),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = keyboardOptions,
            modifier = fieldModifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
        )
    }
}

/**
 * Une rangée dont la zone (et donc son découpage) déborde de `amount` vers la gauche : le premier élément, agrandi au focus,
 * n'est plus rogné. À utiliser avec contentPadding(horizontal = amount) sur la LazyRow.
 */
fun Modifier.bleedStart(amount: Dp = SX.Gutter): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val p = measurable.measure(constraints)
        layout(p.width, p.height) { p.place(0, 0) }
    } else {
        val g = amount.roundToPx()
        val p = measurable.measure(constraints.copy(minWidth = 0, maxWidth = constraints.maxWidth + g))
        layout((p.width - g).coerceAtLeast(0), p.height) { p.place(-g, 0) }
    }
}

@Composable
fun RoundGlassButton(icon: ImageVector, hasNotification: Boolean, onClick: () -> Unit = {}, description: String? = null) {
    Box(
        Modifier
            .size(40.dp)
            .tvFocus(shape = CircleShape, cornerRadius = 20.dp, scale = 1.1f, onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .glass(CircleShape, 1.2f),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        if (hasNotification) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 1.dp, end = 1.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF59E0B))
                    .border(1.dp, Color(0xFF181A28), CircleShape)
            )
        }
    }
}

class QuickAction(
    val label: String,
    val icon: ImageVector,
    val accent: Color,
    val onSelect: () -> Unit
)

/**
 * Retrouve le (Kind, Item) correspondant à une entrée « Reprendre la lecture » afin de pouvoir
 * l'ajouter ou la retirer des favoris directement depuis l'appui long sur sa vignette.
 */
fun resolveWatchFavItem(w: Watch): Pair<Kind, Item>? {
    if (w.key.startsWith("vod:") || w.kind == Kind.VOD.key) {
        val vodId = w.key.removePrefix("vod:").trim()
        if (vodId.isEmpty()) return null
        val entry = Catalog.list(Kind.VOD).firstOrNull { it.id == vodId }
        val item = entry?.toItem() ?: Item(
            id = vodId,
            name = w.title,
            image = w.image,
            ext = null,
            cat = w.cat,
            rating = 0f,
            kind = Kind.VOD
        )
        return Kind.VOD to item
    }
    if (w.key.startsWith("ep:") || w.kind == Kind.SERIES.key) {
        val seriesTitle = cleanTitle(w.title.substringBefore(" · ").trim())
        val entry = Catalog.list(Kind.SERIES).firstOrNull { e ->
            (w.image != null && e.image == w.image) ||
                cleanTitle(e.name).equals(seriesTitle, ignoreCase = true)
        } ?: return null
        return Kind.SERIES to entry.toItem()
    }
    return null
}

/**
 * Renvoie les clés d'historique (« Reprendre la lecture ») associées à un film ou une série.
 */
fun matchingWatchKeys(ctx: Context, kind: Kind, item: Item): List<String> {
    if (kind == Kind.LIVE) return emptyList()
    val hist = History.load(ctx)
    if (hist.isEmpty()) return emptyList()
    return when (kind) {
        Kind.VOD -> {
            val k = "vod:" + item.id
            hist.filter { it.key == k }.map { it.key }
        }
        Kind.SERIES -> {
            val seriesClean = cleanTitle(item.name)
            hist.filter { w ->
                (w.kind == Kind.SERIES.key || w.key.startsWith("ep:")) &&
                    ((item.image != null && w.image == item.image) ||
                        cleanTitle(w.title.substringBefore(" · ").trim()).equals(seriesClean, ignoreCase = true))
            }.map { it.key }
        }
        Kind.LIVE -> emptyList()
    }
}

/**
 * Menu contextuel en verre dépoli (appui long OK ou touche Menu sur une vignette) :
 * permet de retirer un titre de « Reprendre la lecture » ou d'ajouter / retirer un favori.
 */
@Composable
fun QuickActionModal(
    title: String,
    subtitle: String,
    image: String?,
    landscapeImage: Boolean = false,
    accentColor: Color = LocalNavTheme.current.ring,
    actions: List<QuickAction>,
    onDismiss: () -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    val openedAt = remember { android.os.SystemClock.uptimeMillis() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { firstFocus.requestFocus() }
    }
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xC2050711)),
            contentAlignment = Alignment.Center
        ) {
            val panelShape = RoundedCornerShape(20.dp)
            Column(
                Modifier
                    .width(356.dp)
                    .clip(panelShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xF5151931), Color(0xFA0C0E1D))
                        )
                    )
                    .cachedDraw {
                        drawCircle(
                            Brush.radialGradient(
                                listOf(accentColor.copy(alpha = 0.28f), Color.Transparent),
                                center = Offset(size.width * 0.22f, size.height * 0.18f),
                                radius = size.width * 0.65f
                            ),
                            radius = size.width * 0.65f,
                            center = Offset(size.width * 0.22f, size.height * 0.18f)
                        )
                    }
                    .border(
                        1.1.dp,
                        Brush.linearGradient(
                            listOf(
                                Color(0x80FFFFFF),
                                accentColor.copy(alpha = 0.55f),
                                Color(0x28FFFFFF)
                            )
                        ),
                        panelShape
                    )
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val thumbW = if (landscapeImage) 64.dp else 44.dp
                    val thumbH = if (landscapeImage) 42.dp else 62.dp
                    val thumbShape = RoundedCornerShape(10.dp)
                    Box(
                        Modifier
                            .width(thumbW)
                            .height(thumbH)
                            .clip(thumbShape)
                            .background(Color(0xFF1F2342))
                            .border(0.7.dp, Color(0x40FFFFFF), thumbShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!image.isNullOrBlank()) {
                            NetImage(
                                url = image,
                                widthDp = thumbW,
                                heightDp = thumbH,
                                contentScale = if (landscapeImage) ContentScale.Fit else ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .then(if (landscapeImage) Modifier.padding(6.dp) else Modifier)
                            )
                        } else {
                            Text(
                                text = title.take(2).uppercase(),
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = subtitle,
                            color = SX.Text2,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(0.8.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, Color(0x33FFFFFF), Color.Transparent)
                            )
                        )
                )
                Spacer(Modifier.height(12.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEachIndexed { idx, act ->
                        var rowFocused by remember { mutableStateOf(false) }
                        val rowShape = RoundedCornerShape(SX.RTile)
                        val rp = rememberFocusProgress(rowFocused).value
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .then(if (idx == 0) Modifier.focusRequester(firstFocus) else Modifier)
                                .onFocusChanged { rowFocused = it.isFocused }
                                .tvFocus(
                                    shape = rowShape,
                                    scale = 1.0f,
                                    decorate = false,
                                    onClick = {
                                        if (android.os.SystemClock.uptimeMillis() - openedAt >= 320L) {
                                            onDismiss()
                                            act.onSelect()
                                        }
                                    }
                                )
                                .clip(rowShape)
                                .background(lerp(Color(0x14FFFFFF), SXFocus.Fill, rp))
                                .padding(horizontal = 13.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(lerp(act.accent.copy(alpha = 0.22f), act.accent.copy(alpha = 0.16f), rp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = act.icon,
                                    contentDescription = null,
                                    tint = act.accent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(Modifier.width(11.dp))
                            Text(
                                text = act.label,
                                color = lerp(Color.White, SXFocus.On, rp),
                                fontSize = 13.5.sp,
                                fontWeight = if (rowFocused) FontWeight.Bold else FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Bouton Annuler
                    var cancelFocused by remember { mutableStateOf(false) }
                    val cancelShape = RoundedCornerShape(SX.RTile)
                    val cp = rememberFocusProgress(cancelFocused).value
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .onFocusChanged { cancelFocused = it.isFocused }
                            .tvFocus(
                                shape = cancelShape,
                                scale = 1.0f,
                                decorate = false,
                                onClick = {
                                    if (android.os.SystemClock.uptimeMillis() - openedAt >= 320L) {
                                        onDismiss()
                                    }
                                }
                            )
                            .clip(cancelShape)
                            .background(lerp(Color(0x0FFFFFFF), SXFocus.Fill, cp))
                            .padding(horizontal = 13.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x22FFFFFF)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = null,
                                tint = lerp(SX.Text2, SXFocus.On, cp),
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Spacer(Modifier.width(11.dp))
                        Text(
                            text = stringResource(R.string.cancel),
                            color = lerp(SX.Text2, SXFocus.On, cp),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/** Pastille « Favori » posée sur une jaquette : cœur blanc sur rouge vif, cerclé de blanc, lisible sur n'importe quelle image. */
@Composable
fun FavBadge(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 22.dp) {
    androidx.compose.foundation.layout.Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFFEC4899))
            .border(1.5.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Filled.Favorite, null,
            tint = Color.White, modifier = Modifier.size(size * 0.58f)
        )
    }
}
