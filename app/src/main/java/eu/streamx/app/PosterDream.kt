package eu.streamx.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Affiches mémorisées par l'accueil pour l'écran de veille (adresses d'images nettoyées + titres). */
object DreamPosters {
    private const val PREFS = "streamx_dream"

    fun save(c: Context, items: List<Pair<String, String>>) {
        if (items.isEmpty()) return
        val text = items.joinToString("\n") { it.first + "\t" + it.second.replace('\n', ' ').replace('\t', ' ') }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("list", text).apply()
    }

    fun load(c: Context): List<Pair<String, String>> =
        (c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("list", "") ?: "").lines().mapNotNull {
            val p = it.split('\t')
            if (p.size == 2 && p[0].isNotBlank()) p[0] to p[1] else null
        }
}

/**
 * Écran de veille de la télé : les affiches du moment, une à la fois, en fondu avec un lent zoom, titre et heure.
 * À choisir dans les réglages Android TV (Écran de veille) ; Réglages > Écran de veille y mène.
 */
class PosterDream : DreamService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val ctx = this
        val dm = resources.displayMetrics
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }

        fun layer(): Pair<FrameLayout, Triple<ImageView, ImageView, TextView>> {
            val f = FrameLayout(ctx).apply { alpha = 0f }
            val back = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.22f }
            val poster = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            val ph = (dm.heightPixels * 0.72f).toInt()
            val pw = (ph * 2f / 3f).toInt()
            val title = TextView(ctx).apply {
                setTextColor(Color.WHITE); textSize = 22f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setShadowLayer(8f, 0f, 2f, Color.BLACK)
            }
            f.addView(back, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            f.addView(poster, FrameLayout.LayoutParams(pw, ph, Gravity.CENTER_HORIZONTAL).apply { topMargin = (dm.heightPixels * 0.08f).toInt() })
            f.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = (dm.heightPixels * 0.05f).toInt() })
            return f to Triple(back, poster, title)
        }

        val a = layer()
        val b = layer()
        root.addView(a.first)
        root.addView(b.first)
        val clock = TextView(ctx).apply {
            setTextColor(0xCCFFFFFF.toInt()); textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
        }
        root.addView(clock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = (dm.heightPixels * 0.04f).toInt(); rightMargin = (dm.widthPixels * 0.04f).toInt()
        })
        setContentView(root)

        job = scope.launch {
            val items = DreamPosters.load(ctx).shuffled()
            val layers = listOf(a, b)
            var shown = 0
            val timeFmt = DateFormat.getTimeInstance(DateFormat.SHORT)
            launch { while (true) { clock.text = timeFmt.format(Date()); delay(20_000) } }
            if (items.isEmpty()) {
                // Pas encore d'affiches (l'accueil ne s'est pas chargé) : simple horloge.
                clock.textSize = 64f
                clock.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                return@launch
            }
            var i = 0
            while (true) {
                val (url, name) = items[i % items.size]
                i++
                val bmp = try {
                    val req = ImageRequest.Builder(ctx).data(url).size(Size(900, 1350)).allowHardware(false).build()
                    SingletonImageLoader.get(ctx).execute(req).image?.toBitmap()
                } catch (e: Throwable) { null }
                if (bmp == null) { delay(300); continue }
                val cur = layers[shown % 2]
                val prev = layers[(shown + 1) % 2]
                shown++
                val (back, poster, title) = cur.second
                back.setImageBitmap(bmp); poster.setImageBitmap(bmp); title.text = name
                cur.first.scaleX = 1f; cur.first.scaleY = 1f
                cur.first.animate().alpha(1f).setDuration(1500).start()
                cur.first.animate().scaleX(1.06f).scaleY(1.06f).setDuration(11_000).start()
                prev.first.animate().alpha(0f).setDuration(1500).start()
                delay(10_000)
            }
        }
    }

    override fun onDetachedFromWindow() {
        job?.cancel()
        scope.cancel()
        super.onDetachedFromWindow()
    }
}
