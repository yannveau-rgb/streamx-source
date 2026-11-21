package eu.streamx.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import java.util.Locale

private const val DARK = 0xFF0B0D19.toInt()
private const val PANEL = 0xF2181818.toInt()
private const val PILL_BG = 0xB3303030.toInt()

// Pouce « J'aime » (icônes Material, vides ou pleines).
private const val THUMB_OUTLINE = "M9 21h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.59 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2zM9 9l4.34-4.34L12 10h9v2l-3 7H9V9zM1 9h4v12H1z"
private const val THUMB_FILLED = "M1 21h4V9H1v12zm22-11c0-1.1-.9-2-2-2h-6.31l.95-4.57.03-.32c0-.41-.17-.79-.44-1.06L14.17 1 7.59 7.59C7.22 7.95 7 8.45 7 9v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-2z"

/**
 * Bouton rond du lecteur, dessiné à la main. Comme sur YouTube TV : au focus, il devient un rond blanc plein à l'icône sombre ;
 * sinon l'icône est blanche (sur un disque translucide pour les boutons de lecture).
 */
private class Glyph(ctx: Context, val kind: Int, private val px: Int, private val disc: Boolean) : View(ctx) {
    companion object { const val PLAY = 0; const val PAUSE = 1; const val PREV = 2; const val NEXT = 3; const val CC = 4; const val AUDIO = 5; const val SPEED = 6; const val THUMB = 7 }

    var shown = kind
    var on = false // pouce plein quand la vidéo est aimée
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    init { isFocusable = true; isClickable = true }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(px, px)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val r = w / 2f
        val color: Int
        if (isFocused) { fill.color = Color.WHITE; c.drawCircle(r, r, r, fill); color = DARK }
        else { if (disc) { fill.color = 0x40FFFFFF; c.drawCircle(r, r, r, fill) }; color = Color.WHITE }
        ink.color = color
        line.color = color
        line.strokeWidth = w * 0.07f
        val p = Path()
        when (shown) {
            PLAY -> { p.moveTo(.39f * w, .26f * w); p.lineTo(.39f * w, .74f * w); p.lineTo(.75f * w, .5f * w); p.close(); c.drawPath(p, ink) }
            PAUSE -> { p.addRect(.33f * w, .27f * w, .46f * w, .73f * w, Path.Direction.CW); p.addRect(.54f * w, .27f * w, .67f * w, .73f * w, Path.Direction.CW); c.drawPath(p, ink) }
            PREV -> { p.addRect(.28f * w, .3f * w, .35f * w, .7f * w, Path.Direction.CW); p.moveTo(.72f * w, .3f * w); p.lineTo(.72f * w, .7f * w); p.lineTo(.42f * w, .5f * w); p.close(); c.drawPath(p, ink) }
            NEXT -> { p.addRect(.65f * w, .3f * w, .72f * w, .7f * w, Path.Direction.CW); p.moveTo(.28f * w, .3f * w); p.lineTo(.28f * w, .7f * w); p.lineTo(.58f * w, .5f * w); p.close(); c.drawPath(p, ink) }
            CC -> {
                c.drawRoundRect(RectF(.16f * w, .27f * w, .84f * w, .73f * w), .09f * w, .09f * w, line)
                ink.textSize = .27f * w; ink.typeface = Typeface.DEFAULT_BOLD; ink.textAlign = Paint.Align.CENTER
                c.drawText("CC", .5f * w, .5f * w + ink.textSize * .35f, ink)
            }
            AUDIO -> {
                p.moveTo(.20f * w, .42f * w); p.lineTo(.32f * w, .42f * w); p.lineTo(.48f * w, .28f * w); p.lineTo(.48f * w, .72f * w); p.lineTo(.32f * w, .58f * w); p.lineTo(.20f * w, .58f * w); p.close()
                c.drawPath(p, ink)
                c.drawArc(RectF(.40f * w, .36f * w, .64f * w, .64f * w), -50f, 100f, false, line)
                c.drawArc(RectF(.34f * w, .27f * w, .78f * w, .73f * w), -50f, 100f, false, line)
            }
            THUMB -> {
                val path = androidx.core.graphics.PathParser.createPathFromPathData(if (on) THUMB_FILLED else THUMB_OUTLINE)
                val sc = w * 0.54f / 24f
                val m = Matrix()
                m.setScale(sc, sc)
                m.postTranslate((w - 24f * sc) / 2f, (w - 24f * sc) / 2f - w * 0.01f)
                path.transform(m)
                if (on && !isFocused) ink.color = 0xFF2DD4BF.toInt() // turquoise de l'appli quand c'est aimé
                c.drawPath(path, ink)
            }
            SPEED -> {
                c.drawArc(RectF(.2f * w, .24f * w, .8f * w, .84f * w), 150f, 240f, false, line)
                c.drawLine(.5f * w, .56f * w, .65f * w, .38f * w, line)
                c.drawCircle(.5f * w, .56f * w, .045f * w, ink)
            }
        }
    }
}

/**
 * Commandes du lecteur, sur le modèle de YouTube TV : titre en haut à gauche, temps et barre épaisse blanche sur toute la largeur,
 * puis une rangée avec les sauts à gauche, précédent / grand rond lecture-pause / suivant au centre, et à droite
 * les réglages (sous-titres, voix, vitesse). Panneaux Vitesse / Voix / Sous-titres sur le côté.
 */
@UnstableApi
class PlayerControls(
    private val ctx: Context,
    private val player: () -> Player?,
    isLive: Boolean,
    private val hasNext: () -> Boolean,
    private val onNext: () -> Unit,
    private val onSeekFlash: (String) -> Unit,
) : FrameLayout(ctx) {

    private var live = isLive

    /** Affiche le pouce « J'aime » dans la rangée de droite (onglet Tube) et lui donne son action. */
    fun enableLike(onClick: () -> Unit) {
        likeBtn.visibility = View.VISIBLE
        likeBtn.setOnClickListener { onClick() }
    }

    fun setLiked(liked: Boolean) {
        likeBtn.on = liked
        likeBtn.invalidate()
    }

    /** Bascule entre vidéo à la demande et direct (onglet Tube : un même lecteur enchaîne les deux). */
    fun setLive(l: Boolean) {
        live = l
        val vis = if (l) View.GONE else View.VISIBLE
        bar.visibility = vis
        durView.visibility = vis
        listOf<View>(prevBtn, backBtn, fwdBtn, speedBtn).forEach { it.visibility = vis }
        posView.text = if (l) ctx.getString(R.string.player_live) else ""
        posView.setTextColor(if (l) 0xFF3DE0C5.toInt() else 0xFFE8E8E8.toInt())
    }

    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val title = TextView(ctx)
    private val sub = TextView(ctx)
    private val bar = LayoutInflater.from(ctx).inflate(R.layout.pc_timebar, null, false) as DefaultTimeBar
    private val posView = TextView(ctx)
    private val durView = TextView(ctx)
    private val timeRow = FrameLayout(ctx)
    private val playBtn = Glyph(ctx, Glyph.PLAY, dp(52), true)
    private val prevBtn = Glyph(ctx, Glyph.PREV, dp(40), true)
    private val nextBtn = Glyph(ctx, Glyph.NEXT, dp(40), true)
    private val backBtn = pill("−10 s")
    private val fwdBtn = pill("+10 s")
    private val subsBtn = Glyph(ctx, Glyph.CC, dp(40), false)
    private val audioBtn = Glyph(ctx, Glyph.AUDIO, dp(40), false)
    private val speedBtn = Glyph(ctx, Glyph.SPEED, dp(40), false)
    private val likeBtn = Glyph(ctx, Glyph.THUMB, dp(40), false)
    private val menu = LinearLayout(ctx)
    private val menuScroll = ScrollView(ctx)
    private var menuOwner: View? = null
    private var scrubbing = false

    val visible get() = visibility == View.VISIBLE
    val menuOpen get() = menuScroll.visibility == View.VISIBLE

    /** Pastille de texte : gris translucide, blanche et à texte sombre au focus (comme les boutons de YouTube TV). */
    private fun pill(label: String) = TextView(ctx).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        setPadding(dp(20), dp(9), dp(20), dp(9))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(40).toFloat() })
            addState(intArrayOf(), GradientDrawable().apply { setColor(PILL_BG); cornerRadius = dp(40).toFloat() })
        }
        setOnFocusChangeListener { v, f -> (v as TextView).setTextColor(if (f) DARK else Color.WHITE) }
    }

    init {
        visibility = View.GONE
        clipChildren = false
        // Voile sombre sur toute l'image (YouTube assombrit la vidéo), plus dense en haut et en bas.
        setBackgroundColor(0x59000000)

        // Haut : titre (et épisode).
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xB3000000.toInt(), 0x00000000))
            setPadding(dp(48), dp(34), dp(48), dp(60))
        }
        title.apply { setTextColor(Color.WHITE); textSize = 26f; typeface = Typeface.DEFAULT_BOLD; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        sub.apply { setTextColor(0xFFE0E0E0.toInt()); textSize = 15f; setPadding(0, dp(4), 0, 0) }
        top.addView(title); top.addView(sub)
        addView(top, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Bas : temps, barre, commandes.
        val bottom = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
            setPadding(dp(48), dp(80), dp(48), dp(26))
            clipChildren = false
        }
        listOf(posView, durView).forEach { it.setTextColor(0xFFE8E8E8.toInt()); it.textSize = 14f; it.typeface = Typeface.DEFAULT_BOLD }
        posView.text = if (live) ctx.getString(R.string.player_live) else ""
        if (live) posView.setTextColor(0xFF3DE0C5.toInt())
        timeRow.addView(posView, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START))
        timeRow.addView(durView, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END))
        bottom.addView(timeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        bar.apply {
            setKeyTimeIncrement(10_000)
            isFocusable = true
            addListener(object : TimeBar.OnScrubListener {
                override fun onScrubStart(timeBar: TimeBar, position: Long) { scrubbing = true }
                override fun onScrubMove(timeBar: TimeBar, position: Long) { posView.text = fmt(position) }
                override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                    scrubbing = false
                    if (!canceled) player()?.seekTo(position)
                }
            })
        }
        // La barre de Media3 garde une marge interne d'environ 11 dp de chaque côté : on la compense pour qu'elle soit alignée sur les temps.
        bottom.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)).apply { setMargins(-dp(11), 0, -dp(11), 0) })

        val gap = dp(10)
        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            listOf<View>(backBtn, fwdBtn).forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, gap, 0) }) }
        }
        val center = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            listOf<View>(prevBtn, playBtn, nextBtn).forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(8), 0, dp(8), 0) }) }
        }
        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply { setColor(PILL_BG); cornerRadius = dp(40).toFloat() }
            listOf<View>(likeBtn, subsBtn, audioBtn, speedBtn).forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(2), 0, dp(2), 0) }) }
        }
        val row = FrameLayout(ctx).apply {
            clipChildren = false
            addView(left, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
            addView(center, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(right, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        }
        bottom.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(bottom, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Panneau latéral des menus.
        menu.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        menuScroll.apply {
            visibility = View.GONE
            background = GradientDrawable().apply { setColor(PANEL); cornerRadius = dp(24).toFloat() }
            addView(menu)
        }
        addView(menuScroll, LayoutParams(dp(380), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(0, 0, dp(48), 0) })

        likeBtn.visibility = View.GONE // visible seulement si le lecteur propose « J'aime » (onglet Tube)

        // Actions.
        playBtn.setOnClickListener { togglePlay() }
        prevBtn.setOnClickListener { player()?.seekTo(0); onSeekFlash("0:00") }
        nextBtn.setOnClickListener { onNext() }
        backBtn.setOnClickListener { seekBy(-10_000) }
        fwdBtn.setOnClickListener { seekBy(10_000) }
        speedBtn.setOnClickListener { openSpeed() }
        audioBtn.setOnClickListener { openTracks(C.TRACK_TYPE_AUDIO) }
        subsBtn.setOnClickListener { openTracks(C.TRACK_TYPE_TEXT) }

        // Direct : ni barre, ni sauts, ni vitesse, ni précédent ; « DIRECT » à la place du temps.
        if (live) {
            bar.visibility = View.GONE
            durView.visibility = View.GONE
            listOf<View>(prevBtn, backBtn, fwdBtn, speedBtn).forEach { it.visibility = View.GONE }
        }
    }

    private fun fmt(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }

    private fun togglePlay() {
        val p = player() ?: return
        if (p.playbackState == Player.STATE_ENDED) { p.seekTo(0); p.playWhenReady = true } else p.playWhenReady = !p.playWhenReady
        refresh()
    }

    private fun seekBy(delta: Long) {
        val p = player() ?: return
        val t = p.currentPosition + delta
        p.seekTo(if (p.duration > 0) t.coerceIn(0, p.duration) else t.coerceAtLeast(0))
        onSeekFlash(if (delta < 0) "−10 s" else "+10 s")
        refresh()
    }

    /** « L'institut · S1 E3 » devient un titre et, dessous, « S1 E3 ». */
    fun setTitle(t: String) {
        val m = Regex("^(.*?)\\s*[·\\-–]\\s*(S\\d+\\s*E\\d+.*)$").find(t)
        if (m != null) { title.text = m.groupValues[1]; sub.text = m.groupValues[2]; sub.visibility = View.VISIBLE }
        else { title.text = t; sub.visibility = View.GONE }
    }

    fun show(currentTitle: String) {
        setTitle(currentTitle)
        refresh()
        visibility = View.VISIBLE
        if (!playBtn.isFocused) playBtn.requestFocus()
    }

    fun hide() { closeMenu(); visibility = View.GONE }

    /** Met à jour barre, temps et icônes (appelé toutes les 500 ms quand les commandes sont visibles). */
    fun refresh() {
        val p = player() ?: return
        playBtn.shown = if (p.playWhenReady && p.playbackState != Player.STATE_ENDED) Glyph.PAUSE else Glyph.PLAY
        playBtn.invalidate()
        nextBtn.visibility = if (hasNext()) View.VISIBLE else View.GONE
        if (!live) {
            val dur = p.duration.coerceAtLeast(0)
            if (!scrubbing) { bar.setPosition(p.currentPosition); posView.text = fmt(p.currentPosition) }
            bar.setDuration(if (dur > 0) dur else C.TIME_UNSET)
            bar.setBufferedPosition(p.bufferedPosition)
            durView.text = if (dur > 0) fmt(dur) else ""
        }
    }

    private fun trimNum(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

    // ----- Menus -----

    fun closeMenu() {
        if (!menuOpen) return
        menuScroll.visibility = View.GONE
        menuOwner?.requestFocus()
    }

    /** Coche tracée (#3DE0C5 au repos, turquoise foncé sur fond blanc focalisé) ; transparente sinon pour garder l'alignement. */
    private fun checkDrawable(on: Boolean, focused: Boolean = false): android.graphics.drawable.Drawable {
        val sz = dp(22)
        val bmp = android.graphics.Bitmap.createBitmap(sz, sz, android.graphics.Bitmap.Config.ARGB_8888)
        if (on) {
            val c = android.graphics.Canvas(bmp)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = if (focused) 0xFF0B6B5C.toInt() else 0xFF3DE0C5.toInt()
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = sz * 0.14f; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
            }
            val path = android.graphics.Path().apply {
                moveTo(sz * 0.18f, sz * 0.54f); lineTo(sz * 0.40f, sz * 0.76f); lineTo(sz * 0.82f, sz * 0.26f)
            }
            c.drawPath(path, paint)
        }
        return android.graphics.drawable.BitmapDrawable(ctx.resources, bmp)
    }

    private fun item(label: String, selected: Boolean, onPick: () -> Unit) = TextView(ctx).apply {
        text = label
        compoundDrawablePadding = dp(12)
        setCompoundDrawablesRelativeWithIntrinsicBounds(checkDrawable(selected, false), null, null, null)
        setTextColor(Color.WHITE)
        textSize = 20f
        isFocusable = true
        isClickable = true
        setPadding(dp(20), dp(14), dp(20), dp(14))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(14).toFloat() })
            addState(intArrayOf(), GradientDrawable().apply { setColor(0x00000000) })
        }
        setOnFocusChangeListener { v, f ->
            (v as TextView).apply {
                setTextColor(if (f) DARK else Color.WHITE)
                setCompoundDrawablesRelativeWithIntrinsicBounds(checkDrawable(selected, f), null, null, null)
            }
        }
        setOnClickListener { onPick() }
    }

    private fun openMenu(owner: View, heading: String, entries: List<Triple<String, Boolean, () -> Unit>>) {
        menuOwner = owner
        menu.removeAllViews()
        menu.addView(TextView(ctx).apply {
            text = heading
            setTextColor(0xFFBDBDBD.toInt())
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(20), dp(4), dp(20), dp(12))
        })
        if (entries.isEmpty()) {
            menu.addView(TextView(ctx).apply { text = ctx.getString(R.string.pl_none); setTextColor(0xFFBDBDBD.toInt()); textSize = 18f; setPadding(dp(20), dp(8), dp(20), dp(8)) })
            menuScroll.visibility = View.VISIBLE
            return
        }
        val focusIdx = entries.indexOfFirst { it.second }.coerceAtLeast(0)
        entries.forEach { (label, sel, act) ->
            menu.addView(item(label, sel) { act(); closeMenu(); refresh() })
        }
        menuScroll.visibility = View.VISIBLE
        menu.getChildAt(focusIdx + 1).requestFocus()
    }

    private fun openSpeed() {
        val p = player() ?: return
        val cur = p.playbackParameters.speed
        val list = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        openMenu(speedBtn, ctx.getString(R.string.pl_speed), list.map { s ->
            Triple(if (s == 1f) ctx.getString(R.string.pl_normal) else trimNum(s) + "×", s == cur) { p.setPlaybackSpeed(s) }
        })
    }

    private fun langName(code: String?): String? {
        val c = code?.lowercase()
        if (c.isNullOrBlank() || c == "und") return null
        val name = Locale(c.take(2)).getDisplayLanguage(Locale.getDefault())
        return if (name.isNullOrBlank() || name.equals(c, true)) c.uppercase() else name.replaceFirstChar { it.uppercase() }
    }

    private fun openTracks(type: Int) {
        val p = player() ?: return
        val isText = type == C.TRACK_TYPE_TEXT
        val entries = ArrayList<Triple<String, Boolean, () -> Unit>>()
        val groups = p.currentTracks.groups.filter { it.type == type && it.isSupported }
        if (isText) {
            val anySelected = !p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) && groups.any { it.isSelected }
            entries.add(Triple(ctx.getString(R.string.pl_off), !anySelected) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            })
        }
        var n = 0
        for (g in groups) for (i in 0 until g.length) {
            n++
            val f = g.getTrackFormat(i)
            val base = langName(f.language) ?: f.label ?: ctx.getString(R.string.pl_track, n)
            val extra = if (!isText && f.channelCount > 0) when (f.channelCount) { 1 -> " · mono"; 2 -> " · " + ctx.getString(R.string.pl_stereo); 6 -> " · 5.1"; 8 -> " · 7.1"; else -> "" } else ""
            val sel = g.isTrackSelected(i) && !(isText && p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
            entries.add(Triple(base + extra, sel) {
                val b = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, listOf(i)))
                if (isText) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                p.trackSelectionParameters = b.build()
            })
        }
        openMenu(if (isText) subsBtn else audioBtn, ctx.getString(if (isText) R.string.pl_subs else R.string.pl_audio), entries)
    }
}
