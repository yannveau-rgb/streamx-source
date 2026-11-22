package eu.streamx.app

import android.content.Context
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import java.security.MessageDigest
import java.security.SecureRandom

/** Code parental (jamais enregistré en clair : sel + SHA-256) et état du mode enfant. */
object ParentalStore {
    private const val PREFS = "streamx_parental"
    var version by mutableIntStateOf(0)

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun hash(salt: ByteArray, pin: String): String =
        Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(salt + pin.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

    fun hasPin(c: Context): Boolean = sp(c).contains("hash")

    fun setPin(c: Context, pin: String) {
        val salt = ByteArray(12).also { SecureRandom().nextBytes(it) }
        sp(c).edit().putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP)).putString("hash", hash(salt, pin)).apply()
        version++
    }

    fun check(c: Context, pin: String): Boolean {
        val salt = sp(c).getString("salt", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        return MessageDigest.isEqual(hash(salt, pin).toByteArray(), (sp(c).getString("hash", "") ?: "").toByteArray())
    }

    fun clearPin(c: Context) {
        sp(c).edit().remove("salt").remove("hash").putBoolean("kids", false).apply()
        version++
    }

    fun kidsMode(c: Context): Boolean = sp(c).getBoolean("kids", false) && hasPin(c)

    fun setKids(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("kids", on).apply()
        version++
    }
}

/** Saisie d'un code à 4 chiffres dans une fenêtre à part (le focus reste dans la fenêtre). */
@Composable
fun PinPrompt(title: String, error: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    // Le champ repart vide à chaque étape (nouveau code, confirmation…) et après un code refusé.
    var pin by remember(title, error) { mutableStateOf("") }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xCC05060F)), contentAlignment = Alignment.Center) {
            Column(Modifier.width(360.dp).glass(RoundedCornerShape(SX.RPanel), 1.1f).background(Color(0xE60E1024)).padding(22.dp)) {
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                LoginField(stringResource(R.string.pin_label), pin, { pin = it.filter { c -> c.isDigit() }.take(8) }, password = true)
                if (error) Text(stringResource(R.string.pin_wrong), color = SX.Danger, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillButton(stringResource(R.string.pin_ok), true) { if (pin.length >= 4) onSubmit(pin) }
                    PillButton(stringResource(R.string.cancel), false) { onCancel() }
                }
            }
        }
    }
}

/**
 * Mode enfant : seul l'univers Enfants est accessible (pas de Réglages, ni de recherche, ni d'autres rubriques).
 * Le cadenas en haut à droite demande le code pour en sortir.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun KidsShell(account: Account) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    var detail by remember { mutableStateOf<Pair<Kind, Item>?>(null) }
    var asking by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    val open: OpenDetail = { k, i ->
        if (detail == null) FocusMemory.onOpenDetail()
        detail = k to i
    }
    val closeDetail: () -> Unit = {
        FocusMemory.onCloseDetail()
        detail = null
    }
    BackHandler(enabled = detail != null) { closeDetail() }
    val sectionType = when (detail?.first) {
        Kind.SERIES -> NavIconType.SERIES
        Kind.VOD -> NavIconType.MOVIES
        else -> NavIconType.KIDS
    }
    CompositionLocalProvider(LocalNavTheme provides navThemeOf(sectionType)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(SX.BgTop, SX.BgBottom)))) {
            val d = detail
            when {
                d != null && d.first == Kind.SERIES -> SeriesScreen(account, d.second) { closeDetail() }
                d != null -> MovieScreen(account, d.second, { closeDetail() }, open)
                else -> ThemedHubScreen(HubTheme.KIDS, account, open)
            }
            Box(Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 28.dp)) {
                RoundGlassButton(Icons.Outlined.Lock, false, { wrong = false; asking = true }, description = stringResource(R.string.kids_exit))
            }
            if (asking) {
                PinPrompt(stringResource(R.string.kids_exit_title), wrong, onSubmit = { p ->
                    if (ParentalStore.check(ctx, p)) { asking = false; ParentalStore.setKids(ctx, false) } else wrong = true
                }, onCancel = { asking = false })
            }
        }
    }
}
