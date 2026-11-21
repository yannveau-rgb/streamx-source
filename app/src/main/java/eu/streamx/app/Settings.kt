package eu.streamx.app

import android.app.ActivityManager
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okhttp3.OkHttpClient
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Garde la trace du dernier plantage sur la télé : l'écran Réglages permet de la copier pour le dépannage. */
class StreamXApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val stamp = DateFormat.getDateTimeInstance().format(Date())
                val secrets = AccountStore.all(this).flatMap { listOf(it.pass, it.user) }
                File(filesDir, "last_crash.txt").writeText(
                    "$stamp · v${BuildConfig.VERSION_NAME}\n" + Redact.text(android.util.Log.getStackTraceString(e).take(4000), secrets)
                )
            } catch (_: Exception) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        // Vérification TLS normale (certificats et noms d'hôte contrôlés par Android) : plus de « tout accepter ».
        val okHttpClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()
                chain.proceed(req)
            }
            .build()

        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            }
            .crossfade(true)
            .build()
    }
}

private fun crashFile(c: Context) = File(c.filesDir, "last_crash.txt")

/**
 * Réglage de performance propre à chaque appareil TV (enregistré localement sur la télé, indépendant du compte) :
 * - "auto" : détecte automatiquement les clés TV modestes (<= 2 Go de RAM ou Android < 10)
 * - "high" : Qualité maximale (tous les effets de verre, halos lumineux, fondus et animations)
 * - "lite" : Mode allégé (coupe les halos de fond, calques Offscreen et animations lourdes pour les clés TV moins puissantes)
 */
object PerfPrefs {
    private const val PREFS = "streamx_perf"
    private const val KEY_MODE = "mode"
    var version by mutableIntStateOf(0)

    fun mode(c: Context): String =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, "auto") ?: "auto"

    fun setMode(c: Context, m: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, m).apply()
        version++
    }

    fun isHardwareModest(c: Context): Boolean {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am?.isLowRamDevice == true || Build.VERSION.SDK_INT < 29) return true
        return try {
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            // Clés TV d'entrée de gamme (1 Go à 2 Go de RAM)
            mi.totalMem in 1L..(2_150L * 1024L * 1024L)
        } catch (_: Exception) {
            false
        }
    }

    fun isLite(c: Context): Boolean = when (mode(c)) {
        "lite" -> true
        "high" -> false
        else -> isHardwareModest(c)
    }
}

private fun deviceLabel(): String {
    val mfg = Build.MANUFACTURER.trim().replaceFirstChar { it.uppercase() }
    val model = Build.MODEL.trim()
    return if (mfg.isEmpty() || model.startsWith(mfg, ignoreCase = true)) model else "$mfg $model"
}

private fun supportText(c: Context, account: Account, info: AccountInfo?): String = buildString {
    append("StreamX ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
    append("Android ").append(Build.VERSION.RELEASE).append(" · ").append(deviceLabel()).append('\n')
    append("Serveur : ").append(account.server.substringAfter("://").substringBefore("/")).append('\n')
    append("Profil : ").append(account.label.take(2)).append("***\n")
    append("Statut : ").append(info?.status ?: "?").append(" · connexions max ").append(info?.maxConnections ?: 0).append('\n')
    append("Catalogue : films ").append(Catalog.list(Kind.VOD).size).append(", séries ").append(Catalog.list(Kind.SERIES).size)
        .append(", chaînes ").append(Catalog.list(Kind.LIVE).size).append('\n')
    val f = crashFile(c)
    if (f.exists()) append("--- dernier incident ---\n").append(Redact.text(f.readText().take(1500), listOf(account.pass, account.user)))
}

@Composable
private fun SettingSection(label: String) {
    Text(
        label.uppercase(), color = SX.Overline, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp,
        modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 5.dp)
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    badge: String? = null,
    badgeColor: Color = SX.Text2,
    badgeBg: Color = badgeColor.copy(alpha = 0.15f),
    content: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(SX.RTile)
    val th = LocalNavTheme.current
    var within by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { within = it.hasFocus }
            .background(if (within) Color(0x1FFFFFFF) else Color(0x0FFFFFFF), shape)
            .drawBehind {
                if (within) {
                    val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
                    clipPath(path) { drawRect(th.c1, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
                }
            }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(end = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(badgeBg)
                            .border(0.6.dp, badgeColor.copy(alpha = 0.4f), RoundedCornerShape(50))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badge,
                            color = badgeColor,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = SX.Muted,
                    fontSize = 10.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
private fun SettingUpdateRow() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val update = UpdateState.info
    var checking by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(-1) }
    var failed by remember { mutableStateOf(false) }
    var needsPermission by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(SX.RTile)
    val th = LocalNavTheme.current
    var within by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { within = it.hasFocus }
            .then(
                if (update != null) {
                    Modifier
                        .background(th.c1.copy(alpha = 0.14f), shape)
                        .border(1.dp, th.ring.copy(alpha = 0.45f), shape)
                } else {
                    Modifier.background(if (within) Color(0x1FFFFFFF) else Color(0x0FFFFFFF), shape)
                }
            )
            .drawBehind {
                if (within) {
                    val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
                    clipPath(path) { drawRect(th.c1, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
                }
            }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(end = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.set_update),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                if (update != null) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Brush.horizontalGradient(listOf(Color(0xFF2DD4BF), Color(0xFF7B52FF))))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "v" + update.versionName,
                            color = Color.White,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0x18FFFFFF))
                            .border(0.6.dp, Color(0x2EFFFFFF), RoundedCornerShape(50))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "v" + BuildConfig.VERSION_NAME,
                            color = SX.Text2,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            if (update != null) {
                val english = Locale.getDefault().language == "en"
                val notes = if (english) update.notesEn else update.notesFr
                Text(
                    text = if (notes.isNotBlank()) notes else stringResource(R.string.update_available, update.versionName),
                    color = SX.Text2,
                    fontSize = 10.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val status = when {
                    failed -> stringResource(R.string.update_failed)
                    needsPermission -> stringResource(R.string.update_permission)
                    progress >= 0 -> stringResource(R.string.update_downloading, progress)
                    else -> null
                }
                if (status != null) {
                    Text(
                        text = status,
                        color = if (failed) SX.Danger else SX.VioletLight,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (progress in 0..100) {
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.6f)
                            .height(3.5.dp)
                            .clip(CircleShape)
                            .background(Color(0x33FFFFFF))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth((progress / 100f).coerceIn(0.02f, 1f))
                                .fillMaxHeight()
                                .background(Brush.horizontalGradient(listOf(Color(0xFF2DD4BF), SX.VioletLight)))
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(
                        if (checking) R.string.update_checking else R.string.update_up_to_date,
                        BuildConfig.VERSION_NAME
                    ),
                    color = SX.Muted,
                    fontSize = 10.5.sp
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (update != null) {
                PillButton(stringResource(R.string.update_install), true) {
                    if (progress < 0) {
                        failed = false
                        needsPermission = false
                        progress = 0
                        scope.launch {
                            val apk = Updater.download(ctx, update) { progress = it }
                            progress = -1
                            if (apk == null) failed = true else if (!Updater.install(ctx, apk)) needsPermission = true
                        }
                    }
                }
            } else {
                ActionChip(stringResource(if (checking) R.string.update_checking else R.string.update_check_btn)) {
                    if (!checking) {
                        checking = true
                        scope.launch {
                            UpdateState.info = Updater.check()
                            checking = false
                        }
                    }
                }
            }
        }
    }
}

/** « 5467875780… » devient « 54••••27 » : l'identifiant ne s'affiche jamais en entier à l'écran. */
fun maskLabel(s: String): String = if (s.length <= 4) s else s.take(2) + "••••" + s.takeLast(2)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AccountScreen(
    account: Account, profiles: List<Account>, onSwitch: (Account) -> Unit, onAddProfile: () -> Unit,
    onOpenM3u: () -> Unit, onLogout: () -> Unit
) {
    val info = LocalAccountInfo.current
    val ctx = LocalContext.current
    val pendingUpdate = UpdateState.info
    var audio by remember { mutableStateOf(PlayerPrefs.audio(ctx)) }
    var subs by remember { mutableStateOf(PlayerPrefs.subs(ctx)) }
    var appLang by remember { mutableStateOf(AppLang.get(ctx)) }
    var perfMode by remember(PerfPrefs.version) { mutableStateOf(PerfPrefs.mode(ctx)) }
    val isLiteActive = LocalLowRamMode.current
    var crash by remember { mutableStateOf(crashFile(ctx).takeIf { it.exists() }?.readText()?.lineSequence()?.firstOrNull()) }
    val theme = navThemeOf(NavIconType.SETTINGS)
    // Saisie du code parental : "new1" (nouveau) -> "new2" (confirmation) ; "old" (ancien code) -> "new1" ; "remove" (code actuel).
    var pinStep by remember { mutableStateOf("") }
    var pinFirst by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { scrollState.scrollTo(0) }
    Box(
        Modifier
            .fillMaxSize()
            .cachedDraw {
                drawRect(
                    Brush.radialGradient(
                        listOf(theme.c1.copy(alpha = 0.24f), Color.Transparent),
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 26.dp, end = 26.dp, top = 16.dp, bottom = 24.dp)
                .verticalScroll(scrollState)
                .focusProperties { enter = { firstFocus } }
                .focusGroup()
        ) {
            // En-tête compact Réglages
            PageHeader(
                NavIconType.SETTINGS, stringResource(R.string.settings),
                trailing = if (pendingUpdate == null) null else ({
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFFF59E0B))
                            .padding(horizontal = 9.dp, vertical = 2.5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(5.5.dp).clip(CircleShape).background(SXFocus.On))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = stringResource(R.string.update_available, pendingUpdate.versionName),
                            color = SXFocus.On,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                })
            )
            Spacer(Modifier.height(10.dp))

            // Groupe : Compte
            SettingSection(stringResource(R.string.set_sec_account))
            // 1. Profil & Abonnement
            SettingRow(
                title = stringResource(R.string.profile_title, maskLabel(account.label)),
                subtitle = if (account.isM3u) "Playlist M3U" else account.server.substringAfter("://").substringBefore("/"),
                badge = expiryShort(info),
                badgeColor = if (account.isM3u) SX.Turquoise else SX.Gold
            ) {
                profiles.forEachIndexed { idx, p ->
                    SelectChip(
                        stringResource(R.string.profile_n, idx + 1),
                        p.server == account.server && p.user == account.user,
                        modifier = if (idx == 0) Modifier.focusRequester(firstFocus) else Modifier
                    ) {
                        if (!(p.server == account.server && p.user == account.user)) onSwitch(p)
                    }
                }
                ActionChip(
                    stringResource(R.string.add_profile),
                    modifier = if (profiles.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier
                ) { onAddProfile() }
                ActionChip(stringResource(R.string.remove_profile)) { confirmLogout = true }
            }

            Spacer(Modifier.height(7.dp))
            // 6. Listes M3U
            SettingRow(
                title = stringResource(R.string.m3u),
                subtitle = stringResource(R.string.m3u_desc)
            ) {
                ActionChip(stringResource(R.string.m3u_open)) { onOpenM3u() }
            }

            Spacer(Modifier.height(7.dp))
            // 6b. Contrôle parental : code + mode enfant
            val pv = ParentalStore.version
            val hasPin = remember(pv) { ParentalStore.hasPin(ctx) }
            SettingRow(
                title = stringResource(R.string.set_parental),
                subtitle = stringResource(R.string.parental_desc),
                badge = if (hasPin) stringResource(R.string.parental_on) else null,
                badgeColor = SX.Turquoise
            ) {
                if (!hasPin) {
                    ActionChip(stringResource(R.string.pin_set)) { pinStep = "new1"; pinError = false }
                } else {
                    ActionChip(stringResource(R.string.kids_enable)) { ParentalStore.setKids(ctx, true) }
                    ActionChip(stringResource(R.string.pin_change)) { pinStep = "old"; pinError = false }
                    ActionChip(stringResource(R.string.pin_remove)) { pinStep = "remove"; pinError = false }
                }
            }
            Spacer(Modifier.height(7.dp))
            SettingSection(stringResource(R.string.set_sec_playback))
            // 4. Langues : Voix
            SettingRow(
                title = stringResource(R.string.pref_audio),
                subtitle = stringResource(R.string.pref_audio_sub)
            ) {
                LangPicker(audio, listOf("orig" to stringResource(R.string.lang_orig)), { audio = it; PlayerPrefs.setAudio(ctx, it) })
            }

            Spacer(Modifier.height(7.dp))
            // 5. Langues : Sous-titres
            SettingRow(
                title = stringResource(R.string.pref_subs),
                subtitle = stringResource(R.string.pref_subs_sub)
            ) {
                LangPicker(
                    subs,
                    listOf("auto" to stringResource(R.string.subs_auto), "off" to stringResource(R.string.subs_off)),
                    { subs = it; PlayerPrefs.setSubs(ctx, it) }
                )
            }

            Spacer(Modifier.height(7.dp))
            SettingSection(stringResource(R.string.set_sec_app))
            SettingRow(
                title = stringResource(R.string.pref_app_lang),
                subtitle = stringResource(R.string.pref_app_lang_sub)
            ) {
                val act = ctx as? android.app.Activity
                val pick = { v: String -> if (v != appLang) { appLang = v; AppLang.set(ctx, v); act?.recreate() } }
                SelectChip(stringResource(R.string.lang_system), appLang == "auto") { pick("auto") }
                SelectChip("Français", appLang == "fr") { pick("fr") }
                SelectChip("English", appLang == "en") { pick("en") }
            }
            Spacer(Modifier.height(7.dp))
            // 3. Performance & Fluidité
            SettingRow(
                title = stringResource(R.string.set_perf),
                subtitle = stringResource(R.string.perf_desc),
                badge = stringResource(if (isLiteActive) R.string.perf_lite else R.string.perf_high),
                badgeColor = if (isLiteActive) SX.Turquoise else SX.VioletLight
            ) {
                SelectChip(stringResource(R.string.perf_auto), perfMode == "auto") {
                    perfMode = "auto"
                    PerfPrefs.setMode(ctx, "auto")
                }
                SelectChip(stringResource(R.string.perf_high), perfMode == "high") {
                    perfMode = "high"
                    PerfPrefs.setMode(ctx, "high")
                }
                SelectChip(stringResource(R.string.perf_lite), perfMode == "lite") {
                    perfMode = "lite"
                    PerfPrefs.setMode(ctx, "lite")
                }
            }

            Spacer(Modifier.height(7.dp))
            // 2. Mise à jour
            SettingUpdateRow()

            Spacer(Modifier.height(7.dp))
            SettingRow(title = stringResource(R.string.set_dream), subtitle = stringResource(R.string.set_dream_sub)) {
                ActionChip(stringResource(R.string.set_dream_open)) {
                    try { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_DREAM_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (e: Exception) { Toast.makeText(ctx, ctx.getString(R.string.set_dream_manual), Toast.LENGTH_LONG).show() }
                }
            }
            Spacer(Modifier.height(7.dp))
            SettingRow(title = stringResource(R.string.set_restart), subtitle = stringResource(R.string.set_restart_sub)) {
                ActionChip(stringResource(R.string.restart_app)) { AppRestart.restart(ctx) }
            }
            Spacer(Modifier.height(7.dp))
            // Mention obligatoire de la licence TMDB, avec le logo officiel (placée avant la dernière ligne focalisable pour rester visible).
            if (Tmdb.enabled) {
                SettingRow(
                    title = stringResource(R.string.tmdb_credit_title),
                    subtitle = stringResource(R.string.tmdb_credit)
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.tmdb_logo),
                        contentDescription = "TMDB",
                        modifier = Modifier.height(18.dp)
                    )
                }
                Spacer(Modifier.height(7.dp))
            }
            // 7. Assistance & Diagnostic
            val supportSub = deviceLabel() + if (crash != null) " · " + stringResource(R.string.crash_last, crash.orEmpty()) else ""
            SettingRow(
                title = stringResource(R.string.set_support),
                subtitle = supportSub,
                badge = "Android " + Build.VERSION.RELEASE
            ) {
                ActionChip(stringResource(R.string.support_copy)) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("StreamX", supportText(ctx, account, info)))
                    Toast.makeText(ctx, ctx.getString(R.string.support_copied), Toast.LENGTH_SHORT).show()
                }
                if (crash != null) {
                    ActionChip(stringResource(R.string.crash_clear)) {
                        crashFile(ctx).delete()
                        crash = null
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            // 6c. Mention obligatoire de la licence TMDB (tendances, affiches)
        }
        if (confirmLogout) {
            val cancelFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { delay(50); runCatching { cancelFocus.requestFocus() } }
            Dialog(onDismissRequest = { confirmLogout = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Box(Modifier.fillMaxSize().background(Color(0xCC05060F)), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier
                            .width(380.dp)
                            .glass(RoundedCornerShape(SX.RPanel), 1.1f)
                            .background(Color(0xE60E1024))
                            .padding(22.dp)
                    ) {
                        Text(stringResource(R.string.logout_confirm_title), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.logout_confirm_msg, maskLabel(account.label)), color = SX.Text2, fontSize = 12.5.sp)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                            PillButton(stringResource(R.string.cancel), true, Modifier.focusRequester(cancelFocus)) { confirmLogout = false }
                            PillButton(stringResource(R.string.remove_profile), false) { confirmLogout = false; onLogout() }
                        }
                    }
                }
            }
        }
        if (pinStep.isNotEmpty()) {
            val title = stringResource(
                when (pinStep) { "new1" -> R.string.pin_new; "new2" -> R.string.pin_confirm; "old" -> R.string.pin_enter_old; else -> R.string.pin_enter }
            )
            PinPrompt(title, pinError, onSubmit = { p ->
                when (pinStep) {
                    "new1" -> { pinFirst = p; pinStep = "new2"; pinError = false }
                    "new2" -> if (p == pinFirst) { ParentalStore.setPin(ctx, p); pinStep = ""; pinFirst = "" } else { pinError = true }
                    "old" -> if (ParentalStore.check(ctx, p)) { pinStep = "new1"; pinError = false } else pinError = true
                    "remove" -> if (ParentalStore.check(ctx, p)) { ParentalStore.clearPin(ctx); pinStep = "" } else pinError = true
                }
            }, onCancel = { pinStep = ""; pinFirst = ""; pinError = false })
        }
    }
}
