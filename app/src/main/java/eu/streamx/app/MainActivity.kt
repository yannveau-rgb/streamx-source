package eu.streamx.app

import android.app.ActivityManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

val LocalLowRamMode = compositionLocalOf { false }

object SX {
    val BgTop = Color(0xFF0B0D19)
    val BgBottom = Color(0xFF070811)
    val SideTop = Color(0xFF14112B)
    val SideMid = Color(0xFF100F22)
    val SideBottom = Color(0xFF0B0C18)
    val Violet = Color(0xFF7B52FF)
    val VioletDeep = Color(0xFF5E38E8)
    val VioletLight = Color(0xFFB288FF)
    val PeachGlow = Color(0xFFE08258)
    val Text = Color(0xFFF5F6FA)
    val Muted = Color(0xFFA6ACC8)
    val Gold = Color(0xFFF6C244)
    val Lilac = Color(0xFFD6C2FF)
    val Turquoise = Color(0xFF3DE0C5)
    val Sky = Color(0xFF3DB6E0)
    val Text2 = Color(0xDCE2E8F5)
    val Overline = Color(0x99FFFFFF)
    val Placeholder = Color(0xFF1E2140)
    val Scrim = Color(0xB30B0D19)
    val Danger = Color(0xFFFF6B7A)
    val Gutter = 28.dp
    val RBadge = 6.dp
    val RCard = 10.dp
    val RTile = 14.dp
    val RPanel = 18.dp
    val Condensed = FontFamily(Typeface.create("sans-serif-condensed", Typeface.BOLD))
    val SerifBold = FontFamily(Typeface.create("serif", Typeface.BOLD))
}

/**
 * Mise en avant de l'élément sélectionné à la télécommande : léger agrandissement, halo et liseré qui apparaissent en fondu.
 * Supporte aussi l'appui long sur OK (`onLongClick`) ou la touche Menu de la télécommande (ex. ajout rapide en favoris).
 */
fun Modifier.tvFocus(
    shape: Shape,
    cornerRadius: Dp = 14.dp, // conservé pour compatibilité (n'a plus d'effet)
    scale: Float = 1.05f,
    onLongClick: (() -> Unit)? = null,
    decorate: Boolean = true,
    onClick: () -> Unit = {}
): Modifier = composed {
    val lowRam = LocalLowRamMode.current
    var focused by remember { mutableStateOf(false) }
    var armedForClick by remember { mutableStateOf(false) }
    var longPressConsumed by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val animProgress = if (lowRam) null else animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "focus"
    )
    this
        .zIndex(if (focused) 2f else 0f)
        .then(
            if (lowRam) Modifier else Modifier.graphicsLayer {
                val p = animProgress?.value ?: 0f
                val k = 1f + (scale - 1f) * p
                scaleX = k
                scaleY = k
                if (decorate) {
                    shadowElevation = 12.dp.toPx() * p
                    this.shape = shape
                    clip = false
                    ambientShadowColor = Color.Black
                    spotShadowColor = Color.Black
                }
            }
        )
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val ringW = 2.5.dp.toPx()
            onDrawWithContent {
                drawContent()
                val a = if (!decorate) 0f else if (lowRam) (if (focused) 1f else 0f) else (animProgress?.value ?: 0f)
                if (a > 0.01f) drawOutline(outline, Color.White, alpha = a * 0.95f, style = Stroke(ringW))
            }
        }
        .onFocusChanged {
            focused = it.isFocused
            if (!it.isFocused) {
                armedForClick = false
                longPressConsumed = false
            }
        }
        .onPreviewKeyEvent { ev ->
            val nk = ev.nativeKeyEvent
            val code = nk.keyCode
            val isCenter = code == KeyEvent.KEYCODE_DPAD_CENTER ||
                code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER
            val isMenu = code == KeyEvent.KEYCODE_MENU ||
                code == KeyEvent.KEYCODE_BOOKMARK ||
                code == KeyEvent.KEYCODE_STAR
            when {
                onLongClick != null && isMenu && nk.action == KeyEvent.ACTION_DOWN && nk.repeatCount == 0 -> {
                    armedForClick = false
                    longPressConsumed = true
                    onLongClick()
                    true
                }
                isCenter && nk.action == KeyEvent.ACTION_DOWN -> {
                    if (nk.repeatCount == 0) {
                        armedForClick = true
                        longPressConsumed = false
                        false
                    } else if (onLongClick != null && armedForClick && !longPressConsumed) {
                        longPressConsumed = true
                        armedForClick = false
                        onLongClick()
                        true
                    } else {
                        // Consomme toutes les répétitions (empêche qu'une modale fraîchement ouverte prenne la répétition pour un clic)
                        true
                    }
                }
                isCenter && nk.action == KeyEvent.ACTION_UP -> {
                    if (!armedForClick || longPressConsumed) {
                        armedForClick = false
                        longPressConsumed = false
                        true
                    } else {
                        armedForClick = false
                        false
                    }
                }
                else -> false
            }
        }
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
}

object SXFocus {
    val Fill = Color(0xFFF4F5FA)
    val On = Color(0xFF0B0D19)
    val RestFill = Color(0x14FFFFFF)
    val RestStroke = Color(0x26FFFFFF)
    val RestText = Color(0xDEFFFFFF)
    val Easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

@Composable
fun rememberFocusProgress(focused: Boolean): androidx.compose.runtime.State<Float> {
    val lowRam = LocalLowRamMode.current
    return animateFloatAsState(
        if (focused) 1f else 0f,
        if (lowRam) androidx.compose.animation.core.snap() else tween(180, easing = SXFocus.Easing),
        label = "sxFocus"
    )
}

/** Ombre portée de focus (noire, suit la forme exacte). Remplace l'ancien halo radial qui formait une plaque. */
fun Modifier.focusShadow(p: () -> Float, shape: Shape, elevation: Dp = 10.dp, enabled: Boolean = true): Modifier =
    if (!enabled) this else this.graphicsLayer {
        shadowElevation = elevation.toPx() * p()
        this.shape = shape
        clip = false
        ambientShadowColor = Color.Black
        spotShadowColor = Color.Black
    }

enum class NavIconType { HOME, LIVE, MOVIES, SERIES, KIDS, MUSIC, TUBE, FAVORITES, M3U, SETTINGS }

data class NavItem(val label: Int, val type: NavIconType)

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(AppLang.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val pv = PerfPrefs.version
            val lowRam = remember(pv) { PerfPrefs.isLite(context) }
            CompositionLocalProvider(LocalLowRamMode provides lowRam) {
                StreamXApp()
            }
        }
    }
}

/** Défilement de la télécommande : on ne bouge que si la vignette sort de l'écran, en douceur, avec une petite marge. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private val TvBringIntoView = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(durationMillis = 220, easing = FastOutSlowInEasing)
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        if (offset >= 0f && trailing <= containerSize) return 0f
        val margin = containerSize * 0.08f
        return if (offset < 0f) offset - margin else trailing - containerSize + margin
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private val TvBringIntoViewLite = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(durationMillis = 100, easing = FastOutSlowInEasing)
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        if (offset >= 0f && trailing <= containerSize) return 0f
        val margin = containerSize * 0.06f
        return if (offset < 0f) offset - margin else trailing - containerSize + margin
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MainShell(account: Account, profiles: List<Account>, onSwitch: (Account) -> Unit, onAddProfile: () -> Unit, onLogout: () -> Unit) {
    val items = listOf(
        NavItem(R.string.home, NavIconType.HOME),
        NavItem(R.string.live, NavIconType.LIVE),
        NavItem(R.string.movies, NavIconType.MOVIES),
        NavItem(R.string.series, NavIconType.SERIES),
        NavItem(R.string.nav_kids, NavIconType.KIDS),
        NavItem(R.string.nav_music, NavIconType.MUSIC),
        NavItem(R.string.nav_tube, NavIconType.TUBE),
        NavItem(R.string.favorites, NavIconType.FAVORITES),
        NavItem(R.string.settings, NavIconType.SETTINGS),
    )
    var selected by remember { mutableIntStateOf(if (account.isM3u) 1 else 0) }
    var searchQuery by remember { mutableStateOf("") }
    var showM3u by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Pair<Kind, Item>?>(null) }
    val openDetail: OpenDetail = { k, i -> FocusMemory.onOpenDetail(); detail = k to i }
    // Retour d'une fiche : la page d'origine est recréée (défilement conservé ci-dessous) et la vignette qui avait le focus le retrouve.
    val closeDetail: () -> Unit = { FocusMemory.onCloseDetail(); detail = null }
    val pages = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    LaunchedEffect(detail) {
        if (detail == null) { delay(2000); FocusMemory.restore = null }
    }
    var searching by remember { mutableStateOf(false) }
    var showNotifs by remember { mutableStateOf(false) }
    val accountInfo = LocalAccountInfo.current
    val resumeTick = LocalResumeTick.current
    // Mise à jour : au lancement et à chaque retour dans l'app ; un 2e essai si le réseau n'était pas prêt ; puis toutes les 2 heures.
    LaunchedEffect(resumeTick) {
        var r = Updater.check()
        if (r == null) { delay(20_000); r = Updater.check() }
        UpdateState.info = r
        while (true) {
            delay(2L * 3600 * 1000)
            UpdateState.info = Updater.check()
        }
    }
    val bellDot = rememberBellDot(accountInfo)
    val chimeCtx = LocalContext.current
    // Nouveaux épisodes / saisons des séries en favoris : au lancement, au retour dans l'app, puis toutes les 3 heures.
    // Univers Enfants et Musique calculés en tâche de fond : leur ouverture est alors immédiate.
    LaunchedEffect(Catalog.version, HiddenVersion.v) {
        delay(1500)
        runCatching { prewarmHubs(chimeCtx) }
    }
    LaunchedEffect(account, resumeTick) {
        delay(6000)
        while (true) {
            runCatching { SeriesWatch.check(chimeCtx, account) }
            delay(3L * 3600 * 1000)
        }
    }
    LaunchedEffect(bellDot, accountInfo, UpdateState.info) {
        if (bellDot && NotifState.shouldChime(chimeCtx, accountInfo)) {
            Chime.play(chimeCtx)
            NotifState.markChimed(chimeCtx, accountInfo)
        }
    }
    val goTo: (Int) -> Unit = { selected = it; detail = null; searching = false; showNotifs = false; showM3u = false }

    // --- Télécommande : la barre latérale et le contenu sont deux zones de focus distinctes.
    val navReqs = remember { List(items.size) { FocusRequester() } }
    val contentReq = remember { FocusRequester() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var contentFocused by remember { mutableStateOf(false) }
    var enterTick by remember { mutableIntStateOf(0) }
    var firstRun by remember { mutableStateOf(true) }
    val onSidebarSelect: (Int) -> Unit = { goTo(it); enterTick++ }

    // Transfert rapide du focus vers le contenu dès l'ouverture d'une rubrique ou d'une fiche
    LaunchedEffect(enterTick, detail, searching, showM3u) {
        if (firstRun) { firstRun = false; return@LaunchedEffect }
        delay(20)
        repeat(20) {
            if (!contentFocused) {
                runCatching { focusManager.moveFocus(FocusDirection.Right) }
            }
            if (contentFocused) return@LaunchedEffect
            delay(45)
        }
    }

    // Retour : saut direct vers la rubrique active de la barre latérale (en 1 opération), puis vers l'accueil.
    val overlay = detail != null || searching || showNotifs || showM3u
    BackHandler(enabled = !overlay && (contentFocused || selected != 0)) {
        if (contentFocused) {
            runCatching { navReqs[selected].requestFocus() }
        } else {
            goTo(0)
            runCatching { navReqs[0].requestFocus() }
        }
    }

    val lowRam = LocalLowRamMode.current
    Box(
        Modifier
            .fillMaxSize()
            .then(
                if (lowRam) {
                    Modifier.background(SX.BgTop)
                } else {
                    Modifier.cachedDraw(essential = true) {
                        drawRect(Brush.verticalGradient(listOf(SX.BgTop, SX.BgBottom)))
                        drawRect(
                            Brush.radialGradient(
                                listOf(Color(0x263DE0C5), Color.Transparent),
                                center = Offset(0f, size.height * 0.28f),
                                radius = size.width * 0.34f
                            )
                        )
                        drawRect(
                            Brush.radialGradient(
                                listOf(Color(0x265B42D6), Color.Transparent),
                                center = Offset(size.width * 0.22f, size.height * 0.92f),
                                radius = size.width * 0.45f
                            )
                        )
                        drawRect(
                            Brush.radialGradient(
                                listOf(Color(0x262568B8), Color.Transparent),
                                center = Offset(size.width * 0.85f, size.height * 0.65f),
                                radius = size.width * 0.42f
                            )
                        )
                    }
                }
            )
    ) {
        Row(Modifier.fillMaxSize()) {
            Sidebar(items, selected, navReqs, contentReq, onSidebarSelect)
            val sectionType = when (detail?.first) {
                Kind.SERIES -> NavIconType.SERIES
                Kind.VOD -> NavIconType.MOVIES
                else -> items[selected].type
            }
            CompositionLocalProvider(
                LocalBringIntoViewSpec provides (if (lowRam) TvBringIntoViewLite else TvBringIntoView),
                LocalNavTheme provides navThemeOf(sectionType)
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .onFocusChanged { contentFocused = it.hasFocus }
                        .focusRequester(contentReq)
                        .focusProperties {
                            exit = { d ->
                                when (d) {
                                    FocusDirection.Left -> navReqs[selected]
                                    FocusDirection.Up, FocusDirection.Down, FocusDirection.Right -> FocusRequester.Cancel
                                    else -> FocusRequester.Default
                                }
                            }
                        }
                        .focusGroup()
                ) {
                    val d = detail
                    when {
                        d != null && d.first == Kind.SERIES -> SeriesScreen(account, d.second) { closeDetail() }
                        d != null -> MovieScreen(account, d.second, { closeDetail() }, openDetail)
                        showM3u -> M3uScreen { showM3u = false }
                        searching -> SearchScreen(account, searchQuery, { searching = false }) { k, i -> detail = k to i; searching = false }
                        else -> pages.SaveableStateProvider("page$selected") {
                            when (selected) {
                                0 -> HomeScreen(
                                    account, bellDot,
                                    onSearch = { q -> searchQuery = q; searching = true },
                                    onBell = { showNotifs = !showNotifs },
                                    onDetail = openDetail,
                                    onNavigate = { goTo(it) }
                                )
                                1 -> LiveTvScreen(account, openDetail)
                                2 -> BrowseScreen(Kind.VOD, account, R.string.movies, openDetail)
                                3 -> BrowseScreen(Kind.SERIES, account, R.string.series, openDetail)
                                4 -> ThemedHubScreen(HubTheme.KIDS, account, openDetail)
                                5 -> ThemedHubScreen(HubTheme.MUSIC, account, openDetail)
                                6 -> TubeScreen()
                                7 -> FavoritesScreen(account, openDetail)
                                else -> AccountScreen(account, profiles, onSwitch, onAddProfile, { showM3u = true }, onLogout)
                            }
                        }
                    }
                    if (showNotifs) NotificationsPanel(Modifier.align(Alignment.TopEnd), accountInfo, onOpenSeries = { showNotifs = false; openDetail(Kind.SERIES, it) }) { showNotifs = false }
                    // (L'ombre portée sur le contenu est désormais dessinée par la barre latérale elle-même, le long de son contour :
                    // l'ancien bandeau d'ombre ajoutait un second trait vertical à droite.)
                }
            }
        }
    }
}

@Composable
fun SectionTitle(label: Int, topPadding: Dp = 12.dp) {
    Text(
        text = stringResource(label),
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
        modifier = Modifier.padding(start = 28.dp, top = topPadding, bottom = 2.dp)
    )
}

@Composable
fun StreamXLogoIcon(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.logo), contentDescription = null, modifier = modifier)
}

@Composable
fun SidebarNavIcon(type: NavIconType, active: Boolean, tint: Color, modifier: Modifier = Modifier) {
    // Icônes dessinées pour StreamX (voir NavIcons.kt) ; les icônes Material ci-dessous ne servent plus que de secours.
    NavIcons.get(type, active)?.let { Icon(it, contentDescription = null, tint = tint, modifier = modifier); return }
    when (type) {
        NavIconType.HOME -> Icon(
            if (active) Icons.Filled.Home else Icons.Outlined.Home,
            contentDescription = null,
            tint = tint,
            modifier = modifier
        )
        NavIconType.LIVE -> Icon(Icons.Outlined.Tv, contentDescription = null, tint = tint, modifier = modifier)
        NavIconType.MOVIES -> Icon(Icons.Outlined.Movie, contentDescription = null, tint = tint, modifier = modifier)
        NavIconType.SERIES -> Icon(Icons.Outlined.Layers, contentDescription = null, tint = tint, modifier = modifier)
        NavIconType.KIDS -> Icon(Icons.Outlined.ChildCare, contentDescription = null, tint = tint, modifier = modifier)
        NavIconType.MUSIC -> Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = tint, modifier = modifier)
        NavIconType.TUBE -> Icon(Icons.Outlined.SmartDisplay, contentDescription = null, tint = tint, modifier = modifier)
        NavIconType.FAVORITES -> Icon(Icons.Outlined.FavoriteBorder, contentDescription = null, tint = tint, modifier = modifier)
        NavIconType.M3U -> Icon(Icons.Outlined.Link, contentDescription = null, tint = tint, modifier = modifier)
        NavIconType.SETTINGS -> Icon(Icons.Outlined.Settings, contentDescription = null, tint = tint, modifier = modifier)
    }
}

@Composable
fun CrownIcon(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.12f, h * 0.78f)
            lineTo(w * 0.08f, h * 0.28f)
            lineTo(w * 0.34f, h * 0.52f)
            lineTo(w * 0.50f, h * 0.20f)
            lineTo(w * 0.66f, h * 0.52f)
            lineTo(w * 0.92f, h * 0.28f)
            lineTo(w * 0.88f, h * 0.78f)
            close()
        }
        drawPath(path = path, color = tint)
    }
}
