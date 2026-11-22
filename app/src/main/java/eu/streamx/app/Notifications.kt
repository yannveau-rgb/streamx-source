package eu.streamx.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.lerp
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Mise à jour de l'application détectée au lancement (null = aucune). */
object UpdateState {
    var info by mutableStateOf<Updater.Info?>(null)
}

fun expiresSoon(info: AccountInfo?): Boolean {
    val exp = info?.expMillis ?: return false
    return exp - System.currentTimeMillis() < 7L * 24 * 3600 * 1000
}

/** Une notification = un identifiant. Une fois lue (panneau ouvert), elle ne rallume plus la pastille de la cloche. */
object NotifState {
    var tick by mutableIntStateOf(0)
    private const val PREFS = "streamx_notifs"

    fun ids(c: Context, info: AccountInfo?): List<String> = buildList {
        UpdateState.info?.let { add("update:" + it.versionCode) }
        SeriesWatch.notes(c).forEach { add(it.id) }
        if (expiresSoon(info)) add("expiry:" + (info?.expMillis ?: 0L))
    }

    private fun seen(c: Context): Set<String> = c.getSharedPreferences(Profile.prefs(PREFS), Context.MODE_PRIVATE).getStringSet("seen", emptySet()) ?: emptySet()

    fun hasUnread(c: Context, info: AccountInfo?): Boolean = ids(c, info).any { it !in seen(c) }

    private fun chimed(c: Context): Set<String> = c.getSharedPreferences(Profile.prefs(PREFS), Context.MODE_PRIVATE).getStringSet("chimed", emptySet()) ?: emptySet()

    /** Faut-il sonner ? Une seule fois par notification, et seulement si elle n'a pas déjà été lue. */
    fun shouldChime(c: Context, info: AccountInfo?): Boolean = ids(c, info).any { it !in seen(c) && it !in chimed(c) }

    fun markChimed(c: Context, info: AccountInfo?) {
        c.getSharedPreferences(Profile.prefs(PREFS), Context.MODE_PRIVATE).edit().putStringSet("chimed", HashSet(chimed(c) + ids(c, info))).apply()
    }

    fun markSeen(c: Context, info: AccountInfo?) {
        c.getSharedPreferences(Profile.prefs(PREFS), Context.MODE_PRIVATE).edit().putStringSet("seen", HashSet(seen(c) + ids(c, info))).apply()
        tick++
    }
}

/** Petit « bip » discret, joué quand une nouvelle notification arrive. */
object Chime {
    fun play(c: Context) {
        try {
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 55)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 220)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tone.release() }, 600)
        } catch (_: Exception) {
        }
    }
}

@Composable
fun rememberBellDot(info: AccountInfo?): Boolean {
    val c = LocalContext.current
    val t = NotifState.tick
    val update = UpdateState.info
    val sv = SeriesWatch.version
    return remember(t, info, update, sv) { NotifState.hasUnread(c, info) }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun NotificationsPanel(modifier: Modifier, info: AccountInfo?, onOpenSeries: (Item) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val shape = RoundedCornerShape(SX.RPanel)
    val first = remember { FocusRequester() }
    val update = UpdateState.info
    val soon = expiresSoon(info)
    val expired = (info?.expMillis ?: Long.MAX_VALUE) < System.currentTimeMillis()
    val notesCtx = LocalContext.current
    val notes = remember { SeriesWatch.notes(notesCtx).take(8) }
    Column(
        modifier
            .padding(top = 64.dp, end = 28.dp)
            .width(305.dp)
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xF022264E), Color(0xF2101228))))
            .glass(shape, 1.1f)
            .padding(16.dp)
    ) {
        Text(stringResource(R.string.notifications), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        if (update != null) UpdateRow(update, first)
        if (notes.isNotEmpty()) {
            if (update != null) Spacer(Modifier.height(12.dp))
            notes.forEachIndexed { i, n ->
                val row = RoundedCornerShape(10.dp)
                val what = if (n.season > 0) stringResource(R.string.notif_new_season, n.season)
                else if (n.newEpisodes > 1) stringResource(R.string.notif_new_episodes, n.newEpisodes)
                else stringResource(R.string.notif_new_episode)
                var rowFocused by remember { mutableStateOf(false) }
                val rp = rememberFocusProgress(rowFocused).value
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(if (update == null && i == 0) Modifier.focusRequester(first) else Modifier)
                        .onFocusChanged { rowFocused = it.isFocused }
                        .tvFocus(row, scale = 1.0f, decorate = false) { onOpenSeries(n.toItem()) }
                        .clip(row)
                        .background(lerp(Color.Transparent, SXFocus.Fill, rp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(cleanTitle(n.name), color = lerp(Color.White, SXFocus.On, rp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(what, color = lerp(LocalNavTheme.current.ring, SXFocus.On, rp), fontSize = 11.sp)
                }
            }
        }
        if (soon) {
            if (update != null || notes.isNotEmpty()) Spacer(Modifier.height(12.dp))
            Text(stringResource(if (expired) R.string.notif_expired_title else R.string.notif_expiry_title), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(expiryText(info), color = SX.Muted, fontSize = 11.sp)
        }
        if (update == null && notes.isEmpty()) {
            if (!soon) Text(stringResource(R.string.notif_none), color = SX.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            ActionChip(stringResource(R.string.cat_done), Modifier.focusRequester(first)) { onClose() }
        }
    }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        runCatching { first.requestFocus() }
        NotifState.markSeen(ctx, info)
    }
}

@Composable
fun UpdateRow(i: Updater.Info, focus: FocusRequester) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableIntStateOf(-1) }
    var failed by remember { mutableStateOf(false) }
    var needsPermission by remember { mutableStateOf(false) }
    val english = Locale.getDefault().language == "en"
    val notes = if (english) i.notesEn else i.notesFr
    Text(stringResource(R.string.update_available, i.versionName), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    if (notes.isNotBlank()) {
        Text(notes, color = SX.Muted, fontSize = 10.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
    Spacer(Modifier.height(8.dp))
    val status = when {
        failed -> stringResource(R.string.update_failed)
        needsPermission -> stringResource(R.string.update_permission)
        progress >= 0 -> stringResource(R.string.update_downloading, progress)
        else -> null
    }
    if (status != null) {
        Text(status, color = SX.VioletLight, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
    }
    ActionChip(stringResource(R.string.update_install), Modifier.focusRequester(focus)) {
        if (progress < 0) {
            failed = false
            needsPermission = false
            progress = 0
            scope.launch {
                val apk = Updater.download(context, i) { progress = it }
                progress = -1
                if (apk == null) failed = true else if (!Updater.install(context, apk)) needsPermission = true
            }
        }
    }
}

@Composable
fun expiryDate(info: AccountInfo?): String =
    info?.expMillis?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) } ?: ""
