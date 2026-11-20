package eu.streamx.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/** Réglages de langue du lecteur, enregistrés sur la télé. */
object PlayerPrefs {
    private const val PREFS = "streamx_player"

    /** Voix préférée : "fr", "en" ou "orig" (laisser la piste par défaut du flux). */
    fun audio(c: Context): String = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("audio", "fr") ?: "fr"

    /** Sous-titres : "auto" (dans la langue de la voix préférée, seulement si la voix n'existe pas), "off", "fr" ou "en". */
    fun subs(c: Context): String = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("subs", "auto") ?: "auto"

    fun setAudio(c: Context, v: String) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("audio", v).apply()
    fun setSubs(c: Context, v: String) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("subs", v).apply()
}

fun streamUrl(a: Account, kindPath: String, id: String, ext: String): String {
    if (a.isM3u || id.startsWith("http://", ignoreCase = true) || id.startsWith("https://", ignoreCase = true)) return id
    val u = java.net.URLEncoder.encode(a.user, "UTF-8")
    val p = java.net.URLEncoder.encode(a.pass, "UTF-8")
    return XtreamApi.normalize(a.server) + "/$kindPath/$u/$p/$id.$ext"
}

/** Épisode à enchaîner : adresse, titre affiché, clé d'historique. */
data class QueueItem(val url: String, val title: String, val key: String)

/** Chaîne de la liste de zapping : titre, adresse, autres qualités. */
data class ZapItem(val title: String, val url: String, val alts: List<String> = emptyList())

fun playIntent(
    c: Context, url: String, title: String, live: Boolean,
    image: String? = null, hkey: String? = null, pos: Long = 0L, hkind: String = "", hcat: String = "",
    queue: List<QueueItem> = emptyList(),
    alts: List<String> = emptyList(), zap: List<ZapItem> = emptyList(), zapIdx: Int = -1
): Intent =
    Intent(c, PlayerActivity::class.java).putExtra("url", url).putExtra("title", title).putExtra("live", live)
        .putExtra("image", image).putExtra("hkey", hkey).putExtra("pos", pos).putExtra("hkind", hkind).putExtra("hcat", hcat)
        .putStringArrayListExtra("qUrl", ArrayList(queue.map { it.url }))
        .putStringArrayListExtra("qTitle", ArrayList(queue.map { it.title }))
        .putStringArrayListExtra("qKey", ArrayList(queue.map { it.key }))
        .putStringArrayListExtra("alts", ArrayList(alts))
        .putStringArrayListExtra("zTitles", ArrayList(zap.map { it.title }))
        .putStringArrayListExtra("zUrls", ArrayList(zap.map { it.url }))
        .putStringArrayListExtra("zAlts", ArrayList(zap.map { it.alts.joinToString("\n") }))
        .putExtra("zIdx", zapIdx)

class PlayerActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(AppLang.wrap(newBase))

    private var player: ExoPlayer? = null
    private var view: PlayerView? = null
    private var tracksApplied = false
    private var triedTs = false
    private lateinit var url: String
    private var curKey: String? = null
    private var curTitle = ""
    private var live = false
    private val queue = ArrayList<QueueItem>()
    private lateinit var nextHint: TextView
    private lateinit var flash: TextView
    private lateinit var info: TextView
    private lateinit var controls: PlayerControls
    private val hideControls = object : Runnable {
        override fun run() {
            if (controls.menuOpen) return
            if (player?.isPlaying == true) controls.hide() else ui.postDelayed(this, 2000)
        }
    }
    private fun showControls() {
        controls.show(curTitle)
        scheduleHide()
    }
    private fun scheduleHide() {
        ui.removeCallbacks(hideControls)
        ui.postDelayed(hideControls, 5000)
    }
    // Qualités de secours de la chaîne en cours, et liste de zapping (chaînes voisines dans la rangée d'où l'on vient).
    private val altUrls = ArrayList<String>()
    private val zap = ArrayList<ZapItem>()
    private var zapIdx = -1
    private var lastZapIdx = -1
    private val numBuf = StringBuilder()
    private val applyNumber = Runnable {
        val n = numBuf.toString().toIntOrNull()
        numBuf.setLength(0)
        if (n != null && n in 1..zap.size) zapTo(n - 1) else flash.visibility = View.GONE
    }
    private val hideInfo = Runnable { info.visibility = View.GONE }
    private val ui = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateNextHint()
            if (controls.visible) controls.refresh()
            ui.postDelayed(this, 500)
        }
    }

    private fun lang2(l: String?): String? = when (l?.lowercase()) {
        null, "", "und" -> null
        "fr", "fra", "fre" -> "fr"
        "en", "eng" -> "en"
        else -> Langs.codeOf(l.lowercase())
    }

    private fun bubble(gravity: Int, mb: Int, mt: Int): TextView = TextView(this).apply {
        visibility = View.GONE
        setTextColor(Color.WHITE)
        textSize = 15f
        setPadding(44, 22, 44, 22)
        background = GradientDrawable().apply { setColor(0xD9141733.toInt()); cornerRadius = 80f }
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity)
            .apply { setMargins(0, mt, 64, mb) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        url = intent.getStringExtra("url") ?: return finish()
        live = intent.getBooleanExtra("live", false)
        curKey = intent.getStringExtra("hkey")
        curTitle = intent.getStringExtra("title") ?: ""
        val qu = intent.getStringArrayListExtra("qUrl") ?: arrayListOf()
        val qt = intent.getStringArrayListExtra("qTitle") ?: arrayListOf()
        val qk = intent.getStringArrayListExtra("qKey") ?: arrayListOf()
        for (i in qu.indices) queue.add(QueueItem(qu[i], qt.getOrElse(i) { "" }, qk.getOrElse(i) { "" }))
        altUrls.addAll(intent.getStringArrayListExtra("alts") ?: arrayListOf())
        val zt = intent.getStringArrayListExtra("zTitles") ?: arrayListOf()
        val zu = intent.getStringArrayListExtra("zUrls") ?: arrayListOf()
        val za = intent.getStringArrayListExtra("zAlts") ?: arrayListOf()
        for (i in zu.indices) zap.add(ZapItem(zt.getOrElse(i) { "" }, zu[i], za.getOrElse(i) { "" }.split("\n").filter { it.isNotBlank() }))
        zapIdx = if (zap.isEmpty()) -1 else intent.getIntExtra("zIdx", -1)
        // Fond noir partout : les bandes autour d'une vidéo 16/9 ne doivent pas être grises.
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        val pv = PlayerView(this).apply {
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            useController = false
            setBackgroundColor(Color.BLACK)
            setShutterBackgroundColor(Color.BLACK)
        }
        view = pv
        nextHint = bubble(Gravity.BOTTOM or Gravity.END, 140, 0)
        flash = bubble(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 80).apply {
            (layoutParams as FrameLayout.LayoutParams).setMargins(0, 80, 0, 0)
        }
        // Bandeau de chaîne (numéro, nom, programme en cours) : en bas à gauche, quelques secondes.
        info = bubble(Gravity.BOTTOM or Gravity.START, 90, 0).apply {
            (layoutParams as FrameLayout.LayoutParams).setMargins(64, 0, 0, 90)
            textSize = 16f
        }
        val ctl = PlayerControls(this, { player }, live, { queue.isNotEmpty() }, { playNext() }, { flashMessage(it) })
        controls = ctl
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(pv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(ctl, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(nextHint)
            addView(flash)
            addView(info)
        }
        setContentView(root)
        pv.requestFocus()

        val audioPref = PlayerPrefs.audio(this)
        val subsMode = PlayerPrefs.subs(this)
        val http = DefaultHttpDataSource.Factory().setUserAgent("StreamX/" + BuildConfig.VERSION_NAME).setAllowCrossProtocolRedirects(true)
        val exo = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
        player = exo
        pv.player = exo

        var params = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        if (audioPref != "orig") params = params.setPreferredAudioLanguages(*Langs.tags(audioPref))
        exo.trackSelectionParameters = params.build()

        exo.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                if (tracksApplied) return
                val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (audioGroups.isEmpty()) return
                tracksApplied = true
                var playing: String? = null
                for (g in audioGroups) for (i in 0 until g.length) if (g.isTrackSelected(i)) playing = lang2(g.getTrackFormat(i).language)
                val base = if (audioPref == "orig") "fr" else audioPref
                val wanted: String? = when (subsMode) {
                    "off" -> null
                    "auto" -> if (playing != null && playing == base) null else base
                    else -> subsMode
                }
                val hasText = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }
                val b = exo.trackSelectionParameters.buildUpon()
                if (wanted == null || !hasText) {
                    b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else {
                    b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    b.setPreferredTextLanguages(*Langs.tags(wanted))
                }
                exo.trackSelectionParameters = b.build()
            }

            override fun onPlaybackStateChanged(state: Int) {
                // Fin d'un épisode : on enchaîne sur le suivant.
                if (state == Player.STATE_ENDED && queue.isNotEmpty()) playNext()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (live && !triedTs && url.endsWith(".m3u8")) {
                    triedTs = true
                    url = url.removeSuffix(".m3u8") + ".ts"
                    tracksApplied = false
                    exo.setMediaItem(MediaItem.fromUri(url))
                    exo.prepare()
                    exo.playWhenReady = true
                } else if (live && altUrls.isNotEmpty()) {
                    // Cette qualité ne démarre pas : on passe à la suivante (par ordre de qualité).
                    url = altUrls.removeAt(0)
                    triedTs = false
                    tracksApplied = false
                    exo.setMediaItem(MediaItem.fromUri(url))
                    exo.prepare()
                    exo.playWhenReady = true
                } else {
                    showErrorDialog()
                }
            }
        })
        exo.setMediaItem(MediaItem.fromUri(url))
        val startPos = intent.getLongExtra("pos", 0L)
        if (startPos > 0) exo.seekTo(startPos)
        exo.prepare()
        exo.playWhenReady = true
        ui.post(ticker)
        if (live) showChannelInfo() else showControls()
    }

    private var errorDialog: android.app.AlertDialog? = null

    /** Flux en erreur : proposer de réessayer, de redémarrer l'application (sans débrancher la box) ou de revenir. */
    private fun showErrorDialog() {
        if (isFinishing || errorDialog?.isShowing == true) return
        errorDialog = android.app.AlertDialog.Builder(this)
            .setMessage(getString(R.string.player_error))
            .setPositiveButton(R.string.retry) { _, _ ->
                tracksApplied = false
                player?.apply { setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = true }
            }
            .setNeutralButton(R.string.restart_app) { _, _ -> AppRestart.restart(this) }
            .setNegativeButton(R.string.back) { _, _ -> finish() }
            .show()
    }

    /** Change de chaîne dans la liste de zapping et affiche le bandeau. */
    private fun zapTo(i: Int) {
        if (i !in zap.indices) return
        lastZapIdx = zapIdx
        zapIdx = i
        val z = zap[i]
        curTitle = z.title
        url = z.url
        altUrls.clear()
        altUrls.addAll(z.alts)
        controls.setTitle(curTitle)
        tracksApplied = false
        triedTs = false
        player?.apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
        showChannelInfo()
    }

    /** « 4 · Canal+ » puis, si l'abonnement fournit un guide, « Maintenant : … · Ensuite : … ». */
    private fun showChannelInfo() {
        val n = if (zapIdx >= 0) "${zapIdx + 1} · " else ""
        info.text = n + curTitle
        info.visibility = View.VISIBLE
        ui.removeCallbacks(hideInfo)
        ui.postDelayed(hideInfo, 5000)
        val acc = AccountStore.load(this) ?: return
        if (acc.isM3u) return
        val id = url.substringAfterLast('/').substringBefore('.')
        val forUrl = url
        val title = curTitle
        Thread {
            val epg = runCatching { kotlinx.coroutines.runBlocking { XtreamApi.shortEpg(acc, id) } }.getOrNull()
            val now = System.currentTimeMillis()
            val cur = epg?.firstOrNull { it.startMs <= now && now < it.endMs }
            val next = epg?.firstOrNull { it.startMs >= (cur?.endMs ?: now) }
            if (cur != null || next != null) ui.post {
                if (url == forUrl) {
                    val l2 = buildString {
                        if (cur != null) append(getString(R.string.epg_now)).append(" : ").append(cur.title)
                        if (next != null) { if (isNotEmpty()) append("   ·   "); append(getString(R.string.epg_next)).append(" : ").append(next.title) }
                    }
                    info.text = n + title + "\n" + l2
                }
            }
        }.start()
    }

    /** Dans les 40 dernières secondes d'un épisode : proposition « Épisode suivant » (OK pour y aller). */
    private fun updateNextHint() {
        val p = player ?: return
        if (live || queue.isEmpty() || controls.visible) { nextHint.visibility = View.GONE; return }
        val d = p.duration
        if (d <= 0) return
        if (d - p.currentPosition <= 40_000) {
            nextHint.text = getString(R.string.player_next, queue[0].title)
            nextHint.visibility = View.VISIBLE
        } else {
            nextHint.visibility = View.GONE
        }
    }

    private fun playNext() {
        if (queue.isEmpty()) return
        saveProgress(ended = true)
        val n = queue.removeAt(0)
        curKey = n.key
        curTitle = n.title
        url = n.url
        tracksApplied = false
        triedTs = false
        nextHint.visibility = View.GONE
        controls.setTitle(curTitle)
        player?.apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    private fun flashMessage(text: String) {
        flash.text = text
        flash.visibility = View.VISIBLE
        ui.removeCallbacks(hideFlash)
        ui.postDelayed(hideFlash, 900)
    }

    private val hideFlash = Runnable { flash.visibility = View.GONE }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val p = player
        val v = view
        val down = e.action == KeyEvent.ACTION_DOWN
        // Panneau Vitesse / Voix / Sous-titres ouvert : Retour le ferme, le reste navigue dedans.
        if (controls.menuOpen) {
            if (down && (e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_ESCAPE)) { controls.closeMenu(); return true }
            if (down) scheduleHide()
            return super.dispatchKeyEvent(e)
        }
        // Commandes visibles : la navigation se fait entre les boutons ; Retour les masque.
        if (controls.visible) {
            if (down) {
                if (e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_ESCAPE) { controls.hide(); return true }
                scheduleHide()
            }
            if (down && p != null) when (e.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { p.playWhenReady = !p.playWhenReady; controls.refresh(); return true }
            }
            return super.dispatchKeyEvent(e)
        }
        if (p != null && down) when (e.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { p.playWhenReady = !p.playWhenReady; showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p.playWhenReady = true; return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.playWhenReady = false; showControls(); return true }
        }
        if (p != null && v != null && live && zap.size > 1 && down) {
            // Zapping : CH+/CH− ou haut/bas, numéro au pavé, « dernière chaîne ».
            val code = e.keyCode
            when {
                code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_DPAD_UP -> { zapTo((zapIdx + 1) % zap.size); return true }
                code == KeyEvent.KEYCODE_CHANNEL_DOWN || code == KeyEvent.KEYCODE_DPAD_DOWN -> { zapTo((zapIdx - 1 + zap.size) % zap.size); return true }
                code == KeyEvent.KEYCODE_LAST_CHANNEL -> { if (lastZapIdx >= 0) zapTo(lastZapIdx); return true }
                code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && numBuf.length < 3 -> {
                    numBuf.append(code - KeyEvent.KEYCODE_0)
                    flashMessage(numBuf.toString())
                    ui.removeCallbacks(applyNumber)
                    ui.postDelayed(applyNumber, 1500)
                    return true
                }
            }
        }
        if (p != null && v != null && down) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (nextHint.visibility == View.VISIBLE) playNext() else showControls()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { showControls(); return true }
                // Gauche / droite = reculer ou avancer de 10 secondes (pas pour le direct).
                KeyEvent.KEYCODE_DPAD_LEFT -> if (!live) {
                    p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0))
                    flashMessage("−10 s")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (!live) {
                    val target = p.currentPosition + 10_000
                    p.seekTo(if (p.duration > 0) target.coerceAtMost(p.duration) else target)
                    flashMessage("+10 s")
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    /** Mémorise où en est la lecture pour « Reprendre la lecture » (pas pour le direct). */
    private fun saveProgress(ended: Boolean = false) {
        val p = player ?: return
        val key = curKey ?: return
        if (live) return
        val dur = p.duration
        val pos = if (ended && dur > 0) dur else p.currentPosition
        if (dur <= 0 || pos < 20_000) return
        if (pos > dur * 0.95) {
            History.markSeen(this, key)
            History.remove(this, key)
            // Épisode terminé : « Reprendre » propose l'épisode suivant de la même saison (rien si la saison est finie).
            val nx = queue.firstOrNull()
            val seasonRx = Regex("S(\\d+) E\\d+")
            if (nx != null && key.startsWith("ep:") && seasonRx.find(curTitle)?.groupValues?.get(1) == seasonRx.find(nx.title)?.groupValues?.get(1)) {
                History.upsert(
                    this,
                    Watch(nx.key, nx.title, intent.getStringExtra("image"), maskUrl(AccountStore.load(this), nx.url), 0L, 0L,
                        intent.getStringExtra("hkind") ?: "", intent.getStringExtra("hcat") ?: "")
                )
            }
        }
        else History.upsert(
            this,
            Watch(
                key, curTitle, intent.getStringExtra("image"), maskUrl(AccountStore.load(this), url), pos, dur,
                intent.getStringExtra("hkind") ?: "", intent.getStringExtra("hcat") ?: ""
            )
        )
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        player?.pause()
    }

    override fun onDestroy() {
        errorDialog?.dismiss()
        ui.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }
}
