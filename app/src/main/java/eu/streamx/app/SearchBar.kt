package eu.streamx.app

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.lerp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Barre de recherche de l'accueil : les suggestions (films, séries, chaînes) apparaissent pendant la frappe.
 * Valider la saisie ouvre la page de résultats complète.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HomeSearchBar(account: Account, onOpenAll: (String) -> Unit, onDetail: OpenDetail, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // On garde le TextFieldValue entier (texte, curseur ET zone de composition) : le recréer à chaque frappe casse le clavier Google.
    var tf by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    val query = tf.text
    fun setQuery(s: String) { tf = androidx.compose.ui.text.input.TextFieldValue(s) }
    var active by remember { mutableStateOf(false) }
    var barFocused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val fieldReq = remember { FocusRequester() }

    val imeVisible = androidx.compose.foundation.layout.WindowInsets.isImeVisible
    var imeSeen by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, editing) {
        if (!editing) imeSeen = false
        else if (imeVisible) imeSeen = true
        else if (imeSeen) { editing = false; imeSeen = false }
    }
    val cv = Catalog.version
    val hv = HiddenVersion.v
    val suggestions by produceState(emptyList<Pair<Kind, Entry>>(), query, cv, hv) {
        val q = normalize(query.trim())
        if (q.length < 2) {
            value = emptyList()
        } else {
            delay(150)
            value = withContext(Dispatchers.Default) {
                val act = { isActive }
                search(Kind.VOD, q, HiddenStore.load(ctx, Kind.VOD), act).take(3).map { Kind.VOD to it } +
                    search(Kind.SERIES, q, HiddenStore.load(ctx, Kind.SERIES), act).take(3).map { Kind.SERIES to it } +
                    search(Kind.LIVE, q, HiddenStore.load(ctx, Kind.LIVE), act).take(1).map { Kind.LIVE to it }
            }
        }
    }
    // Recherche vocale intégrée (voir VoiceSearch.kt) : l'appli écoute elle-même, sans l'ancien écran Google.
    val startVoice = rememberVoiceSearch { setQuery(it) }
    val canSpeak = true
    val showPanel = active && query.trim().length >= 2
    BackHandler(enabled = showPanel) { setQuery(""); focusManager.clearFocus() }

    Column(modifier.width(330.dp).onFocusChanged { active = it.hasFocus }) {
        val pill = RoundedCornerShape(50)
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .glass(pill, 1.1f)
                .background(if (barFocused) Color(0x29FFFFFF) else Color.Transparent, pill)
                .border(if (barFocused) 2.dp else 0.6.dp, if (barFocused) Color(0xF2FFFFFF) else Color(0x40FFFFFF), pill)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Search, null, tint = if (barFocused) Color.White else Color(0xCCFFFFFF), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(9.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(stringResource(R.string.search_bar_hint), color = Color(0xB3FFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = tf,
                    onValueChange = { tf = it }, singleLine = true, readOnly = !editing,
                    textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                    cursorBrush = SolidColor(LocalNavTheme.current.ring),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) { onOpenAll(query.trim()); setQuery(""); focusManager.clearFocus() } }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldReq)
                        .onFocusChanged { barFocused = it.isFocused; if (!it.isFocused) editing = false }
                        .onPreviewKeyEvent { ev ->
                            if (editing) {
                                if (ev.type == KeyEventType.KeyDown) {
                                    val out = when {
                                        ev.key == Key.DirectionRight && tf.selection.end >= query.length -> FocusDirection.Right
                                        ev.key == Key.DirectionLeft && tf.selection.start == 0 -> FocusDirection.Left
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
            if (query.isNotEmpty()) {
                Text(
                    "✕", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .tvFocus(CircleShape, cornerRadius = 13.dp, scale = 1.12f) {
                            runCatching { fieldReq.requestFocus() }
                            setQuery("")
                            editing = false
                            keyboard?.hide()
                        }
                        .clip(CircleShape)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            if (canSpeak) {
                val mic = CircleShape
                Icon(
                    Icons.Outlined.Mic, stringResource(R.string.search_voice), tint = Color.White,
                    modifier = Modifier
                        .size(26.dp)
                        .tvFocus(mic, cornerRadius = 13.dp, scale = 1.12f) { startVoice() }
                        .clip(mic)
                        .padding(4.dp)
                )
            }
        }
        if (showPanel) {
            val panel = RoundedCornerShape(16.dp)
            Column(
                Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .clip(panel)
                    .background(Brush.verticalGradient(listOf(Color(0xEB262A55), Color(0xEB11132A))))
                    .border(0.8.dp, Brush.linearGradient(listOf(Color(0x80FFFFFF), Color(0x1AFFFFFF), Color(0x40B9A0FF))), panel)
                    .padding(6.dp)
            ) {
                if (suggestions.isEmpty()) {
                    val loading = Catalog.loading && Catalog.list(Kind.VOD).isEmpty()
                    Text(
                        stringResource(if (loading) R.string.catalog_loading else R.string.search_none),
                        color = SX.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)
                    )
                }
                suggestions.forEach { (k, e) ->
                    SuggestionRow(e, k) {
                        setQuery("")
                        focusManager.clearFocus()
                        openItem(ctx, account, k, e.toItem(), onDetail)
                    }
                }
                val all = RoundedCornerShape(10.dp)
                var allFocused by remember { mutableStateOf(false) }
                val ap = rememberFocusProgress(allFocused).value
                Text(
                    stringResource(R.string.search_all, query.trim()),
                    color = lerp(SX.Lilac, SXFocus.On, ap),
                    fontSize = 11.5.sp,
                    fontWeight = if (allFocused) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { allFocused = it.isFocused }
                        .tvFocus(all, scale = 1.0f, decorate = false) {
                            onOpenAll(query.trim()); setQuery(""); focusManager.clearFocus()
                        }
                        .clip(all)
                        .background(lerp(Color.Transparent, SXFocus.Fill, ap))
                        .padding(horizontal = 10.dp, vertical = 9.dp)
                )
            }
        }
    }
}

@Composable
private fun SuggestionRow(e: Entry, kind: Kind, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val w = if (kind == Kind.LIVE) 44.dp else 28.dp
    var focused by remember { mutableStateOf(false) }
    val p = rememberFocusProgress(focused).value
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .tvFocus(shape, scale = 1.0f, decorate = false, onClick = onClick)
            .clip(shape)
            .background(lerp(Color.Transparent, SXFocus.Fill, p))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val thumb = RoundedCornerShape(5.dp)
        Box(Modifier.width(w).height(40.dp).clip(thumb).background(Color(0xFF22254A))) {
            NetImage(
                url = e.image,
                widthDp = w,
                heightDp = 40.dp,
                contentScale = if (kind == Kind.LIVE) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(40.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(cleanTitle(e.name), color = lerp(Color.White, SXFocus.On, p), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                stringResource(when (kind) { Kind.VOD -> R.string.badge_movie; Kind.SERIES -> R.string.badge_series; Kind.LIVE -> R.string.badge_live }),
                yearOf(e.name)
            ).joinToString("  ·  ")
            Text(sub, color = lerp(SX.Muted, SXFocus.On.copy(alpha = 0.7f), p), fontSize = 10.5.sp, maxLines = 1)
        }
        Box(Modifier.size(6.dp).clip(CircleShape).background(when (kind) { Kind.VOD -> navThemeOf(NavIconType.MOVIES).c1; Kind.SERIES -> navThemeOf(NavIconType.SERIES).c1; Kind.LIVE -> navThemeOf(NavIconType.LIVE).c1 }))
    }
}
