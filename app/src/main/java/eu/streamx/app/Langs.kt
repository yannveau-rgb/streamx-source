package eu.streamx.app

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

/** Langue de l'application : « auto » (celle de la télé), « fr » ou « en ». Appliquée au démarrage de chaque écran. */
object AppLang {
    private const val PREFS = "streamx_lang"

    fun get(c: Context): String = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("app", "auto") ?: "auto"
    fun set(c: Context, v: String) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("app", v).apply()

    /** Enveloppe le contexte d'une activité pour qu'elle utilise la langue choisie (à appeler dans attachBaseContext). */
    fun wrap(base: Context): Context {
        val v = get(base)
        if (v == "auto") return base
        val loc = Locale(v)
        Locale.setDefault(loc)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(loc)
        return ContextWrapper(base.createConfigurationContext(cfg))
    }
}

/** Langues proposées pour les voix et les sous-titres : codes ISO 639-1, noms écrits dans leur propre langue. */
object Langs {
    private val codes = listOf(
        "fr", "en", "es", "de", "it", "pt", "nl", "ar", "tr", "ru", "pl", "ro", "el", "sv", "da", "no", "fi", "cs", "hu", "uk",
        "he", "zh", "ja", "ko", "hi", "fa", "th", "vi", "id", "ms", "bg", "sr", "hr", "sk", "sl", "lt", "lv", "et", "sq", "hy",
        "az", "ka", "bn", "ur", "ta", "te", "mk", "bs", "is", "ca", "eu", "gl", "ga", "af", "sw", "tl"
    )

    private val biblio = mapOf(
        "fr" to "fre", "de" to "ger", "nl" to "dut", "zh" to "chi", "cs" to "cze", "el" to "gre", "sq" to "alb", "hy" to "arm",
        "ka" to "geo", "mk" to "mac", "is" to "ice", "eu" to "baq", "ms" to "may", "fa" to "per"
    )

    fun label(code: String): String {
        val l = Locale(code)
        return l.getDisplayLanguage(l).replaceFirstChar { it.uppercase(l) }.ifBlank { code }
    }

    /** Étiquettes possibles d'une langue dans un flux (2 lettres, 3 lettres, variante bibliographique). */
    fun tags(code: String): Array<String> {
        val iso3 = runCatching { Locale(code).isO3Language }.getOrNull()
        return listOfNotNull(code, iso3, biblio[code]).distinct().toTypedArray()
    }

    /** Code à 2 lettres d'une étiquette de flux (2 ou 3 lettres) ; à défaut, ses deux premières lettres. */
    fun codeOf(tag: String): String = codes.firstOrNull { tag in tags(it) } ?: tag.take(2)

    /** Toutes les langues, triées par nom ; français et anglais en tête. */
    val all: List<Pair<String, String>> by lazy {
        val first = listOf("fr", "en")
        val rest = codes.filter { it !in first }.map { it to label(it) }.sortedBy { it.second.lowercase() }
        first.map { it to label(it) } + rest
    }
}

/**
 * Sélecteur déroulant : un bouton avec la valeur choisie qui ouvre une fenêtre listant les choix.
 * [special] : choix particuliers en tête (ex. « Automatique », « Désactivés »), sous forme (valeur, libellé).
 */
@Composable
fun LangPicker(
    value: String,
    special: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    languages: List<Pair<String, String>> = Langs.all
) {
    var open by remember { mutableStateOf(false) }
    val current = special.firstOrNull { it.first == value }?.second ?: Langs.label(value)
    ActionChip(current + "  ▾") { open = true }
    if (open) {
        val entries = special + languages
        val start = entries.indexOfFirst { it.first == value }.coerceAtLeast(0)
        val req = remember { FocusRequester() }
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color(0xCC05060F)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.width(720.dp).heightIn(max = 460.dp)
                        .glass(RoundedCornerShape(SX.RPanel), 1.1f).background(Color(0xE60E1024)).padding(20.dp)
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(entries.size) { i ->
                            val (v, label) = entries[i]
                            val sel = v == value
                            val pill = RoundedCornerShape(50)
                            Text(
                                label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(if (i == start) Modifier.focusRequester(req) else Modifier)
                                    .tvFocus(pill, onClick = { onPick(v); open = false })
                                    .then(
                                        if (sel) Modifier.clip(pill).background(Brush.horizontalGradient(listOf(SX.Violet, SX.VioletLight)))
                                        else Modifier.glass(pill, 1.2f)
                                    )
                                    .padding(horizontal = 16.dp, vertical = 9.dp)
                            )
                        }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(150); runCatching { req.requestFocus() } }
    }
}
