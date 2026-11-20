package eu.streamx.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay

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
