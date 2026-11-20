package eu.streamx.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Thème chromatique signature de chaque rubrique (teinte de la goutte, halo et liseré).
 */
data class NavTheme(val c1: Color, val c2: Color, val glow: Color, val ring: Color)

fun navThemeOf(type: NavIconType): NavTheme = when (type) {
    NavIconType.HOME -> NavTheme(Color(0xFF7B52FF), Color(0xFF3DE0C5), Color(0x788B5CF6), Color(0xFFB99AFF))
    NavIconType.LIVE -> NavTheme(Color(0xFF2563EB), Color(0xFF06B6D4), Color(0x7806B6D4), Color(0xFF67E8F9))
    NavIconType.MOVIES -> NavTheme(Color(0xFFF59E0B), Color(0xFFE11D48), Color(0x78F43F5E), Color(0xFFFDA4AF))
    NavIconType.SERIES -> NavTheme(Color(0xFF6366F1), Color(0xFFD946EF), Color(0x78C084FC), Color(0xFFE879F9))
    NavIconType.KIDS -> NavTheme(Color(0xFFFF8A3D), Color(0xFFFF4D94), Color(0x78FF784E), Color(0xFFFFB088))
    NavIconType.MUSIC -> NavTheme(Color(0xFFEC4899), Color(0xFF8B5CF6), Color(0x78EC4899), Color(0xFFF472B6))
    // Tube : turquoise vers violet, dans la palette de l'appli (pas de rouge YouTube).
    NavIconType.TUBE -> NavTheme(Color(0xFF2DD4BF), Color(0xFF7B52FF), Color(0x782DD4BF), Color(0xFF99F6E4))
    NavIconType.FAVORITES -> NavTheme(Color(0xFFFF2E63), Color(0xFFF59E0B), Color(0x78FF4D79), Color(0xFFFF8FA3))
    NavIconType.M3U -> NavTheme(Color(0xFF0EA5E9), Color(0xFF6366F1), Color(0x7838BDF8), Color(0xFF7DD3FC))
    NavIconType.SETTINGS -> NavTheme(Color(0xFF10B981), Color(0xFF3B82F6), Color(0x7810B981), Color(0xFF6EE7B7))
    NavIconType.SEARCH -> NavTheme(Color(0xFF38BDF8), Color(0xFF7B52FF), Color(0x7838BDF8), Color(0xFF7DD3FC))
}

/** Clé de la ligne « Recherche » dans la table des positions (les rubriques utilisent leur index). */
private const val SEARCH_KEY = 100

// Constantes du dôme, en dp (reprises du composant « Flow Tab Bar » : base d'environ 56, hauteur d'environ 15).
private const val BUMP_H = 12f
private const val BUMP_HALF_W = 34f // dôme plus large et plus doux : un ménisque, pas une bosse
private const val GUTTER_DP = 20f
private const val PAD_TOP_DP = 14f

/**
 * Physique de la goutte, portée de l'exemple « Flow Tab Bar » : un ressort pour la position, et deux ressorts sous-amortis pour la
 * hauteur et la largeur du dôme. Quand la goutte voyage vite, la tension de surface l'aplatit (« melt »), puis elle ressort
 * en arrivant avec un petit rebond. Rien ne s'étire : la goutte fait partie du contour du panneau.
 */
private class Droplet {
    var y by mutableFloatStateOf(-1f)       // centre du dôme (dp, dans la colonne)
    var h by mutableFloatStateOf(BUMP_H)    // hauteur du dôme (dp)
    var w by mutableFloatStateOf(BUMP_HALF_W) // demi-largeur de la base (dp)
    var skew by mutableFloatStateOf(0f)     // inclinaison du sommet dans le sens du mouvement (dp)
    var dash by mutableFloatStateOf(-1f)    // tiret et pastille discrète : leur propre ressort, un peu plus ferme
    var dashStretch by mutableFloatStateOf(1f)
    var ripple by mutableFloatStateOf(1f)   // ondulation : 0 → 1
    var rippleY = 0f
    private var vy = 0f
    private var vh = 0f
    private var vw = 0f
    private var vd = 0f

    fun snap(t: Float) {
        y = t; dash = t; h = BUMP_H; w = BUMP_HALF_W; skew = 0f; dashStretch = 1f
        vy = 0f; vh = 0f; vw = 0f; vd = 0f; ripple = 1f
    }

    /** Impulsion de tension de surface quand la goutte change de rubrique. */
    fun kick(t: Float) {
        vh -= 26f; vw += 22f
        ripple = 0f; rippleY = t
    }

    /** Avance d'un pas ; renvoie false quand tout est au repos. */
    fun step(dt: Float, t: Float): Boolean {
        vy += ((t - y) * 200f - vy * 21f) * dt
        y += vy * dt
        vd += ((t - dash) * 250f - vd * 23f) * dt
        dash += vd * dt
        dashStretch = 1f + min(0.55f, abs(vd) / 900f)

        val melt = min(0.84f, (abs(vy) / 440f).pow(1.15f))
        val targetH = BUMP_H * (1f - melt)
        val targetW = BUMP_HALF_W * (1f + 0.24f * melt)
        vh += ((targetH - h) * 260f - vh * 14.5f) * dt
        h = max(0.5f, h + vh * dt)
        vw += ((targetW - w) * 240f - vw * 15f) * dt
        w = max(16f, w + vw * dt)
        skew = (vy * 0.014f).coerceIn(-5.5f, 5.5f)
        if (ripple < 1f) ripple = min(1f, ripple + dt * 2.1f)

        return abs(t - y) > 0.05f || abs(vy) > 2f || abs(h - BUMP_H) > 0.05f || abs(vh) > 2f ||
            abs(w - BUMP_HALF_W) > 0.05f || abs(vw) > 2f || abs(t - dash) > 0.05f || abs(vd) > 2f || ripple < 1f
    }
}

/**
 * Barre latérale « verre liquide ». Le contour droit du panneau se déforme en goutte d'eau (dôme de ménisque) à la hauteur de la
 * rubrique survolée ; la goutte glisse avec un ressort, s'aplatit en voyageant puis ressort en rebondissant. Elle est faite du même
 * verre que le panneau : la couleur ne vient que de la lumière (liseré brillant au sommet, lueur interne, croissant, ondulation).
 * Quand le focus est dans le contenu, elle reste sur la rubrique affichée.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun Sidebar(items: List<NavItem>, selected: Int, requesters: List<FocusRequester>, enterContent: FocusRequester, onSelect: (Int) -> Unit, onSearch: () -> Unit) {
    val searchReq = remember { FocusRequester() }
    val searchItem = remember { NavItem(R.string.search_title, NavIconType.SEARCH) }
    val lowRam = LocalLowRamMode.current
    val density = LocalDensity.current.density
    var focusedKey by remember { mutableIntStateOf(-1) }
    val activeKey = selected.coerceIn(items.indices)
    val targetKey = if (focusedKey != -1) focusedKey else activeKey
    val activeIdx = if (!lowRam && focusedKey in items.indices) focusedKey else activeKey
    val ambientTheme = navThemeOf(items[activeIdx].type)
    val targetType = if (targetKey == SEARCH_KEY) NavIconType.SEARCH else items[targetKey.coerceIn(items.indices)].type
    // Focus dans le contenu : la goutte prend la couleur de l'affiche à l'écran (bandeau d'accueil, fiche).
    val accent = Accent.color
    val baseTint = navThemeOf(targetType).c1
    val tintState = animateColorAsState(if (focusedKey == -1 && accent != null && !lowRam) lerp(baseTint, accent, 0.6f) else baseTint, tween(400), label = "dropTint")

    // Position de chaque ligne (dans la colonne) et mouvement de la goutte.
    val rects = remember { mutableStateMapOf<Int, Rect>() }
    val dr = remember { Droplet() }
    val rowH = rects[targetKey]?.height ?: 0f
    val targetDp: Float? = rects[targetKey]?.let { it.center.y / density + PAD_TOP_DP }
    LaunchedEffect(targetDp, lowRam) {
        val t = targetDp ?: return@LaunchedEffect
        if (dr.y < 0f || lowRam) { dr.snap(t); return@LaunchedEffect }
        if (abs(t - dr.y) > 6f) dr.kick(t) // pas d'ondulation pour un simple ajustement de mise en page
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.033f)
            last = now
            if (!dr.step(dt, t)) break
        }
    }

    Column(
        // Largeur = celle du panneau visible : plus de marge à droite (elle laissait voir un bord de fond, le « second trait »).
        // Le dôme et l'ombre débordent sur le contenu ; zIndex garde la barre au-dessus du contenu pour qu'ils restent visibles.
        Modifier.width(146.dp).fillMaxHeight().zIndex(1f)
            .drawBehind {
                val edgeX = size.width
                val tint = tintState.value
                val peak = lerp(tint, Color.White, 0.72f)
                val ready = dr.y >= 0f && rowH > 0f

                // Géométrie du dôme (le contour droit du panneau), pivotée depuis l'exemple : pieds, poignées de pied et de couronne.
                val cy = dr.y * density
                val hPx = dr.h * density
                val halfPx = dr.w * density
                val footTop = max(2f, cy - halfPx)
                val footBot = min(size.height - 2f, cy + halfPx)
                val half = (footBot - footTop) / 2f
                val crestY = ((footTop + footBot) / 2f + dr.skew * density).coerceIn(footTop + 5.dp.toPx(), footBot - 5.dp.toPx())
                val footHandle = half * 0.54f
                val crownHandle = half * 0.44f
                fun Path.dome() {
                    lineTo(edgeX, footTop)
                    cubicTo(edgeX, footTop + footHandle, edgeX + hPx, crestY - crownHandle, edgeX + hPx, crestY)
                    cubicTo(edgeX + hPx, crestY + crownHandle, edgeX, footBot - footHandle, edgeX, footBot)
                }
                val panel = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(edgeX, 0f)
                    if (ready) dome()
                    lineTo(edgeX, size.height)
                    lineTo(0f, size.height)
                    close()
                }

                // Ombre portée sur le contenu : le même contour, décalé et très doux (remplace l'ancien bandeau, qui faisait un second trait).
                clipPath(panel, ClipOp.Difference) {
                    // Beaucoup de copies très transparentes : un dégradé doux, sans paliers visibles.
                    for (i in 1..4) {
                        translate(left = (i * 4.6f).dp.toPx()) { drawPath(panel, Color(0xFF04050B).copy(alpha = 0.065f)) }
                    }
                }
                // Lueur diffuse derrière le dôme (la lumière traverse le verre), discrète pour ne pas baver sur le contenu.
                if (!lowRam && ready) {
                    drawCircle(
                        Brush.radialGradient(listOf(tint.copy(alpha = 0.16f), Color.Transparent), center = Offset(edgeX + hPx * 0.5f, crestY), radius = 48.dp.toPx()),
                        radius = 48.dp.toPx(), center = Offset(edgeX + hPx * 0.5f, crestY)
                    )
                }

                // Le panneau : un seul verre, dôme compris. Une base opaque dessous : sans elle, le verre translucide se compose avec le fond
                // de l'écran à gauche du bord et avec le contenu (plus sombre) dans le dôme, ce qui dessinait une ligne verticale à travers la goutte.
                drawPath(panel, SX.BgTop)
                drawPath(panel, Brush.verticalGradient(0f to SX.SideTop.copy(alpha = 0.80f), 0.5f to SX.SideMid.copy(alpha = 0.76f), 1f to SX.SideBottom.copy(alpha = 0.84f)))
                clipPath(panel) {
                    if (!lowRam) {
                        // Halo d'ambiance dynamique teinté selon la rubrique survolée ou active
                        // Le rectangle déborde du bord (jusque sous le dôme) : sinon le halo s'arrêtait net à x = edgeX, une ligne dans la goutte.
                        drawRect(
                            Brush.radialGradient(
                                listOf(ambientTheme.c1.copy(alpha = 0.26f), ambientTheme.c2.copy(alpha = 0.09f), Color.Transparent),
                                center = Offset(edgeX * 0.45f, size.height * 0.30f), radius = 165.dp.toPx()
                            ),
                            size = Size(edgeX + BUMP_H.dp.toPx() * 2f, size.height)
                        )
                    }
                    if (ready) {
                        if (!lowRam) {
                            // Caustique : la lumière réfractée dans la goutte.
                            drawCircle(
                                Brush.radialGradient(
                                    listOf(tint.copy(alpha = 0.38f), tint.copy(alpha = 0.12f), Color.Transparent),
                                    center = Offset(edgeX - 8.dp.toPx(), crestY), radius = 62.dp.toPx()
                                ),
                                radius = 62.dp.toPx(), center = Offset(edgeX - 8.dp.toPx(), crestY)
                            )
                            // Ondulation à chaque changement de rubrique.
                            if (dr.ripple < 1f) {
                                val e = 1f - (1f - dr.ripple).pow(3)
                                drawCircle(
                                    peak.copy(alpha = (1f - dr.ripple) * 0.42f), radius = (3f + e * 28f).dp.toPx(),
                                    center = Offset(edgeX - 4.dp.toPx(), dr.rippleY * density), style = Stroke(1.2.dp.toPx())
                                )
                            }
                        }
                    }
                }

                // Liseré brillant du bord droit, au sommet de la goutte : le seul trait, et il suit le contour.
                val edge = Path().apply {
                    moveTo(edgeX, 0f)
                    if (ready) dome()
                    lineTo(edgeX, size.height)
                }
                drawPath(
                    edge,
                    Brush.radialGradient(
                        // La couleur ne se concentre qu'au sommet de la goutte ; ailleurs, le bord est neutre et à peine visible.
                        0f to peak.copy(alpha = 0.90f), 0.30f to tint.copy(alpha = 0.40f), 0.60f to Color.White.copy(alpha = 0.10f), 1f to Color.White.copy(alpha = 0.06f),
                        center = Offset(edgeX + hPx, crestY), radius = 60.dp.toPx()
                    ),
                    style = Stroke(1.25.dp.toPx())
                )
            }
            .padding(start = 12.dp, end = 12.dp, top = PAD_TOP_DP.dp, bottom = 14.dp)
            .focusProperties {
                exit = { d ->
                    when (d) {
                        FocusDirection.Right -> enterContent
                        FocusDirection.Left -> FocusRequester.Cancel
                        else -> FocusRequester.Default
                    }
                }
                // Au premier passage dans la barre (lancement, retour), le focus va à la rubrique active, pas à « Recherche ».
                enter = { requesters.getOrNull(selected) ?: FocusRequester.Default }
            }
            .focusGroup()
    ) {
        Row(Modifier.padding(start = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            StreamXLogoIcon(Modifier.size(26.dp))
            Spacer(Modifier.width(9.dp))
            androidx.compose.foundation.Image(painterResource(R.drawable.wordmark), contentDescription = "StreamX", modifier = Modifier.height(18.dp))
        }

        @Composable
        fun ColumnScope.row(i: Int) = NavRow(
            item = items[i],
            highlight = i == targetKey,
            requester = requesters[i],
            up = if (i == 0) searchReq else requesters.getOrNull(i - 1),
            down = requesters.getOrNull(i + 1),
            onPlaced = { rects[i] = it },
            onFocusState = { isFocused ->
                if (isFocused) focusedKey = i else if (focusedKey == i) focusedKey = -1
            },
            onClick = { onSelect(i) }
        )

        // 0. Recherche : accessible depuis toutes les rubriques
        NavRow(
            item = searchItem, highlight = targetKey == SEARCH_KEY, requester = searchReq, down = requesters.getOrNull(0),
            onPlaced = { rects[SEARCH_KEY] = it },
            onFocusState = { isFocused -> if (isFocused) focusedKey = SEARCH_KEY else if (focusedKey == SEARCH_KEY) focusedKey = -1 },
            onClick = onSearch
        )

        // 1. Accueil
        row(0)

        // 2. Catalogue principal
        Caption(R.string.sb_catalog)
        row(1)
        row(2)
        row(3)

        // 3. Univers thématiques (Enfants & Musique)
        Caption(R.string.sb_universes)
        row(4)
        row(5)
        row(6)

        Spacer(Modifier.weight(1f))

        // Filet lumineux séparant l'espace personnel
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 5.dp)
                .height(0.8.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Color(0x33FFFFFF), Color(0x338FE9DA), Color.Transparent)
                    )
                )
        )

        // 4. Espace personnel (Favoris & Réglages)
        row(7)
        row(8)
    }
}

@Composable
private fun Caption(label: Int) {
    Text(
        stringResource(label).uppercase(), color = SX.Overline, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp,
        modifier = Modifier.padding(start = 8.dp, top = 7.dp, bottom = 3.dp)
    )
}

@Composable
private fun NavRow(
    item: NavItem,
    highlight: Boolean,
    requester: FocusRequester,
    up: FocusRequester? = null,
    down: FocusRequester? = null,
    onPlaced: (Rect) -> Unit,
    onFocusState: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val hasUpdateBadge = item.type == NavIconType.SETTINGS && UpdateState.info != null
    val shape = RoundedCornerShape(13.dp)
    val tile = RoundedCornerShape(9.dp)
    val tint = remember(item.type) { navThemeOf(item.type).c1 }
    // L'icône active se soulève vers la goutte, grossit légèrement et rayonne de la teinte de la rubrique.
    val lift by animateFloatAsState(if (highlight) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = 300f), label = "iconLift")

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 1.5.dp)
            .onGloballyPositioned { onPlaced(it.boundsInParent()) }
            .focusRequester(requester)
            .focusProperties {
                if (up != null) this.up = up
                if (down != null) this.down = down
            }
            .onFocusChanged { onFocusState(it.isFocused) }
            // Pas de contour ni de halo propres à la ligne : c'est la goutte, dans le contour du panneau, qui marque la rubrique.
            .tvFocus(shape = shape, cornerRadius = 13.dp, scale = 1f, decorate = false, onClick = onClick)
            .clip(shape)
            .padding(horizontal = 6.dp, vertical = 4.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(27.dp)
                .graphicsLayer {
                    translationX = 3.dp.toPx() * lift
                    val s = 1f + 0.08f * lift
                    scaleX = s; scaleY = s
                }
                .drawBehind {
                    if (lift > 0.02f) {
                        drawCircle(
                            Brush.radialGradient(listOf(tint.copy(alpha = 0.50f * lift), Color.Transparent), radius = size.maxDimension * 0.95f),
                            radius = size.maxDimension * 0.95f
                        )
                    }
                }
        ) {
            Box(
                Modifier.matchParentSize().glass(tile, if (highlight) 1.0f else 0.65f),
                contentAlignment = Alignment.Center
            ) {
                SidebarNavIcon(item.type, highlight, if (highlight) Color.White else Color(0xFFC2C7DE), Modifier.size(15.dp))
            }
            if (hasUpdateBadge) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF3B5C))
                )
            }
        }
        Spacer(Modifier.width(9.dp))
        Text(
            stringResource(item.label),
            color = if (highlight) Color.White else Color(0xFFC9CEE4),
            fontSize = 11.5.sp,
            fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (hasUpdateBadge) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(Color(0xFFFF2E63), Color(0xFFF59E0B))))
                    .padding(horizontal = 6.dp, vertical = 1.5.dp)
            ) {
                Text(
                    text = stringResource(R.string.update_badge_new),
                    color = Color.White,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
