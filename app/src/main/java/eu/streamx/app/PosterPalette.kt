package eu.streamx.app

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.toArgb
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

/** Couleur d'ambiance courante (affiche à l'écran) : la goutte de la barre latérale la reprend quand le focus est dans le contenu. */
object Accent {
    var color by mutableStateOf<Color?>(null)
}

/** Palette tirée de l'affiche : (lumineuse, profonde, fond sombre), au même format que [heroPalette]. */
object PosterPalette {
    private val cache = object : LinkedHashMap<String, Triple<Color, Color, Color>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Triple<Color, Color, Color>>?) = size > 120
    }

    fun cached(url: String): Triple<Color, Color, Color>? = synchronized(cache) { cache[url] }

    suspend fun load(ctx: android.content.Context, url: String): Triple<Color, Color, Color>? {
        cached(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val req = ImageRequest.Builder(ctx).data(sanitizeImageUrl(url) ?: return@withContext null).size(Size(48, 72)).allowHardware(false).build()
                val bmp: Bitmap = SingletonImageLoader.get(ctx).execute(req).image?.toBitmap() ?: return@withContext null
                val p = from(bmp)
                synchronized(cache) { cache[url] = p }
                p
            } catch (e: Throwable) {
                null
            }
        }
    }

    /** Teinte dominante pondérée par la saturation (les gris et noirs de l'affiche comptent peu). */
    private fun from(b: Bitmap): Triple<Color, Color, Color> {
        val w = b.width
        val h = b.height
        val hsv = FloatArray(3)
        var sx = 0.0; var sy = 0.0; var wsum = 0.0
        for (y in 0 until h step 2) for (x in 0 until w step 2) {
            android.graphics.Color.colorToHSV(b.getPixel(x, y), hsv)
            val wt = (hsv[1] * hsv[2]).toDouble().let { it * it } + 0.001
            val a = Math.toRadians(hsv[0].toDouble())
            sx += Math.cos(a) * wt; sy += Math.sin(a) * wt; wsum += wt
        }
        val hue = ((Math.toDegrees(Math.atan2(sy, sx)) + 360.0) % 360.0).toFloat()
        // Saturation moyenne de l'affiche : une affiche terne donne une ambiance discrète.
        var sat = 0f; var n = 0
        for (y in 0 until h step 3) for (x in 0 until w step 3) {
            android.graphics.Color.colorToHSV(b.getPixel(x, y), hsv); sat += hsv[1]; n++
        }
        val s = (sat / n.coerceAtLeast(1)).coerceIn(0.35f, 0.85f)
        fun c(sv: Float, vv: Float) = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sv, vv)))
        return Triple(c((s + 0.25f).coerceAtMost(0.9f), 1f), c(s, 0.62f), c(0.55f, 0.13f))
    }
}

/** Palette de l'affiche ; en attendant (ou sans image), celle de secours choisie par l'identifiant. */
@Composable
fun rememberPosterPalette(url: String?, fallbackKey: String): Triple<Color, Color, Color> {
    val ctx = LocalContext.current
    val fb = heroPalette[fallbackKey.hashCode().mod(heroPalette.size)]
    if (url == null) return fb
    val p by produceState(PosterPalette.cached(url), url) { if (value == null) value = PosterPalette.load(ctx, url) }
    return p ?: fb
}

/** Publie la couleur d'ambiance pendant que l'écran est affiché. */
@Composable
fun PublishAccent(color: Color) {
    val argb = color.toArgb()
    DisposableEffect(argb) {
        Accent.color = color
        onDispose { if (Accent.color?.toArgb() == argb) Accent.color = null }
    }
}
