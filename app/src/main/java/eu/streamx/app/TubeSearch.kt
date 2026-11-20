package eu.streamx.app

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Barre de recherche de l'onglet Tube : toujours visible en haut à droite.
 * OK pour écrire (ou le micro pour dicter) ; les résultats défilent en direct sous la barre dès 2 lettres,
 * et un panneau propose les suggestions de YouTube (ou les dernières recherches quand la barre est vide).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun TubeSearchBar(query: String, onQuery: (String) -> Unit, onSubmit: (String) -> Unit, modifier: Modifier = Modifier, downTarget: FocusRequester? = null) {
    val ctx = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // On garde le TextFieldValue entier (texte, curseur ET zone de composition) : le recréer à chaque frappe casse le clavier Google.
    var tf by remember { mutableStateOf(TextFieldValue(query)) }
    LaunchedEffect(query) { if (query != tf.text) tf = TextFieldValue(query) }
    fun setText(s: String) { tf = TextFieldValue(s, androidx.compose.ui.text.TextRange(s.length)); onQuery(s) }
    var active by remember { mutableStateOf(false) }
    var inPanel by remember { mutableStateOf(false) }
    var barFocused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val fieldReq = remember { FocusRequester() }

    // Le clavier se referme (touche Retour) : on quitte le mode saisie pour que les flèches renavigent.
    val imeVisible = androidx.compose.foundation.layout.WindowInsets.isImeVisible
    var imeSeen by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, editing) {
        if (!editing) imeSeen = false
        else if (imeVisible) imeSeen = true
        else if (imeSeen) { editing = false; imeSeen = false }
    }

    val text = tf.text
    val suggestions by produceState(emptyList<Pair<String, Boolean>>(), text, active) {
        if (!active) { value = emptyList(); return@produceState }
        val q = text.trim()
        if (q.isEmpty()) {
            value = TubeStore.recents(ctx).take(6).map { it to true }
        } else {
            delay(220)
            value = tubeSafe(emptyList()) { Tube.suggestions(ctx, q) }.filter { !it.equals(q, true) }.take(7).map { it to false }
        }
    }

    val startVoice = rememberVoiceSearch { setText(it); onSubmit(it) }
    val canSpeak = true

    fun submit(s: String) {
        val q = s.trim()
        if (q.isEmpty()) return
        setText(q)
        onSubmit(q)
        editing = false
        keyboard?.hide()
        // Le focus reste dans la barre (jamais « nulle part ») ; la flèche du bas descend ensuite dans les résultats.
        runCatching { fieldReq.requestFocus() }
    }

    // Flèche bas : droit vers le premier résultat (sinon Compose choisit la puce « Mes thèmes », voisine dans le couloir),
    // sauf quand le panneau de suggestions est ouvert : la flèche y descend d'abord.
    val panelOpen = suggestions.isNotEmpty() && (editing || inPanel)
    val downMod = if (downTarget != null && !panelOpen) Modifier.focusProperties { down = downTarget } else Modifier
    Column(modifier.width(400.dp).onFocusChanged { active = it.hasFocus }) {
        val pill = RoundedCornerShape(50)
        Row(
            Modifier
                .fillMaxWidth()
                .height(42.dp)
                .glass(pill, if (barFocused) 1.5f else 1.1f)
                .border(
                    if (barFocused) 1.6.dp else 0.6.dp,
                    if (barFocused) Brush.horizontalGradient(listOf(Color(0xFF2DD4BF), Color(0xFF7B52FF))) else SolidColor(Color(0x40FFFFFF)),
                    pill
                )
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Search, null, tint = if (barFocused) Color.White else Color(0xCCFFFFFF), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(9.dp))
            Box(Modifier.weight(1f)) {
                if (text.isEmpty()) {
                    Text(stringResource(R.string.tube_search_hint), color = Color(0xB3FFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = tf,
                    onValueChange = { tf = it; if (it.text != query) onQuery(it.text) },
                    singleLine = true, readOnly = !editing,
                    textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                    cursorBrush = SolidColor(Color(0xFF2DD4BF)),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit(tf.text) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(downMod)
                        .focusRequester(fieldReq)
                        .onFocusChanged { barFocused = it.isFocused; if (!it.isFocused) editing = false }
                        .onPreviewKeyEvent { ev ->
                            if (editing) {
                                if (ev.type == KeyEventType.KeyDown) {
                                    val out = when {
                                        ev.key == Key.DirectionRight && tf.selection.end >= text.length -> FocusDirection.Right
                                        ev.key == Key.DirectionLeft && tf.selection.start == 0 -> FocusDirection.Left
                                        ev.key == Key.DirectionDown -> FocusDirection.Down
                                        else -> null
                                    }
                                    if (out != null) { editing = false; keyboard?.hide(); focusManager.moveFocus(out); return@onPreviewKeyEvent true }
                                }
                                return@onPreviewKeyEvent false
                            }
                            val dir = when (ev.key) {
                                Key.DirectionRight -> FocusDirection.Right
                                Key.DirectionLeft -> FocusDirection.Left
                                Key.DirectionUp -> FocusDirection.Up
                                Key.DirectionDown -> FocusDirection.Down
                                else -> null
                            }
                            when {
                                dir != null -> { if (ev.type == KeyEventType.KeyDown) focusManager.moveFocus(dir); true }
                                ev.key == Key.DirectionCenter || ev.key == Key.Enter -> {
                                    if (ev.type == KeyEventType.KeyUp) { editing = true; keyboard?.show() }
                                    true
                                }
                                else -> false
                            }
                        }
                )
            }
            if (text.isNotEmpty()) {
                // Effacer d'un clic : revient à l'accueil de Tube.
                Text(
                    "✕", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .then(downMod)
                        .tvFocus(CircleShape, cornerRadius = 13.dp, scale = 1.12f) { runCatching { fieldReq.requestFocus() }; setText(""); editing = false; keyboard?.hide() }
                        .clip(CircleShape)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            if (canSpeak) {
                Icon(
                    Icons.Outlined.Mic, stringResource(R.string.search_voice), tint = Color.White,
                    modifier = Modifier
                        .size(28.dp)
                        .then(downMod)
                        .tvFocus(CircleShape, cornerRadius = 14.dp, scale = 1.12f) { startVoice() }
                        .clip(CircleShape)
                        .padding(4.dp)
                )
            }
        }
        if (panelOpen) {
            val panel = RoundedCornerShape(16.dp)
            Column(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .clip(panel)
                    .onFocusChanged { inPanel = it.hasFocus }
                    .background(Brush.verticalGradient(listOf(Color(0xFF232852), Color(0xFF10122A))))
                    .border(0.8.dp, Brush.linearGradient(listOf(Color(0x80FFFFFF), Color(0x1AFFFFFF), Color(0x402DD4BF))), panel)
                    .padding(6.dp)
            ) {
                suggestions.forEach { (s, recent) ->
                    val row = RoundedCornerShape(10.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .tvFocus(row, cornerRadius = 10.dp, scale = 1.02f) { submit(s) }
                            .clip(row)
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (recent) Icons.Outlined.History else Icons.Outlined.Search, null, tint = SX.Muted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(s, color = Color.White, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
