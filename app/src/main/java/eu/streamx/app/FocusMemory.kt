package eu.streamx.app

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.delay

/**
 * Contrôleur de focus sûr : vérifie qu'un nœud est bien attaché à l'écran avant de demander le focus,
 * ce qui évite les plantages `FocusRequester is not initialized` sur les listes recyclées ou en chargement.
 */
class SafeFocus {
    val requester = FocusRequester()
    @Volatile var attached = false

    fun orDefault(): FocusRequester = if (attached) requester else FocusRequester.Default

    fun tryFocus(): Boolean = if (attached) runCatching { requester.requestFocus() }.isSuccess else false
}

@Composable
fun rememberSafeFocus(): SafeFocus = remember { SafeFocus() }

fun Modifier.safeFocusTarget(sf: SafeFocus): Modifier = composed {
    DisposableEffect(sf) { onDispose { sf.attached = false } }
    this.onGloballyPositioned { sf.attached = it.isAttached }.focusRequester(sf.requester)
}

/** Définit le point d'entrée logique (en haut de la page) lorsqu'on arrive depuis la barre latérale. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.tvPageEntry(entry: SafeFocus, fallback: SafeFocus = entry): Modifier =
    focusProperties { enter = { if (entry.attached) entry.requester else fallback.orDefault() } }.focusGroup()

/** Relie directement la flèche Haut ou Bas à une cible précise (ex. puce du haut ↔ bouton en haut à droite). */
fun Modifier.tvUpTarget(target: SafeFocus): Modifier = focusProperties { up = target.orDefault() }
fun Modifier.tvDownTarget(target: SafeFocus): Modifier = focusProperties { down = target.orDefault() }

/** Intercepte la flèche Haut sur une rangée entière (ex. barre de puces) pour sauter vers un bouton en haut à droite. */
fun Modifier.tvUpAction(target: SafeFocus): Modifier = onPreviewKeyEvent { ev ->
    ev.key == androidx.compose.ui.input.key.Key.DirectionUp &&
        ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
        target.tryFocus()
}

/** Permet aux sous-écrans internes (ex. gestion des catégories) de signaler qu'on ne doit pas sortir vers la barre latérale à gauche. */
val LocalNotifySubScreen = compositionLocalOf<(Boolean) -> Unit> { {} }

/**
 * Retour depuis une fiche : la page d'origine est recréée (son défilement est conservé par MainShell),
 * et cette mémoire redonne le focus à la vignette qui l'avait au moment d'ouvrir la fiche.
 */
object FocusMemory {
    /** Clé de la vignette qui a le focus en ce moment (vide si le focus est ailleurs). */
    var last: String? = null
    /** Clé retenue à l'ouverture d'une fiche. */
    var saved: String? = null
    /** Clé à refocaliser : la vignette qui la reconnaît à son apparition prend le focus. */
    var restore: String? = null

    fun onOpenDetail() { saved = last }
    fun onCloseDetail() { restore = saved; saved = null }
}

/** Identifie la rangée dans laquelle une vignette s'affiche (un même titre peut figurer dans plusieurs rangées). */
val LocalRowKey = compositionLocalOf { "" }

@Composable
fun RowKey(key: String, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRowKey provides key, content = content)
}

/** Demande de focus + notification de focus pour une vignette. */
class CardFocus(val requester: FocusRequester, val note: (Boolean) -> Unit)

@Composable
fun rememberCardFocus(id: String): CardFocus {
    val key = LocalRowKey.current + "|" + id
    val fr = remember { FocusRequester() }
    LaunchedEffect(key) {
        if (FocusMemory.restore != key) return@LaunchedEffect
        // Le nœud n'est pas toujours prêt dès la première image : on réessaie un moment.
        repeat(20) {
            if (FocusMemory.restore != key) return@LaunchedEffect
            try {
                fr.requestFocus()
                FocusMemory.restore = null
                return@LaunchedEffect
            } catch (_: Exception) {
                delay(50)
            }
        }
    }
    return remember(key) {
        CardFocus(fr) { has ->
            if (has) FocusMemory.last = key else if (FocusMemory.last == key) FocusMemory.last = null
        }
    }
}
