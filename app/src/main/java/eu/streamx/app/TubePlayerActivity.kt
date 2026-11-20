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
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

fun tubePlayIntent(c: Context, v: TVideo, queue: List<TVideo> = emptyList()): Intent =
    Intent(c, TubePlayerActivity::class.java).putExtra("v", v.toJson().toString())
        .putStringArrayListExtra("q", ArrayList(queue.take(30).map { it.toJson().toString() }))

/**
 * Lecteur de l'onglet Tube : mêmes commandes que le lecteur de StreamX, plus le pouce « J'aime » (dans les commandes, ou touche Menu)
 * et la lecture automatique : à la fin d'une vidéo, la suivante démarre toute seule parmi les suggestions de YouTube.
 */
class TubePlayerActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLang.wrap(newBase))

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var controls: PlayerControls
    private lateinit var nextHint: TextView
    private lateinit var flash: TextView
    private lateinit var status: TextView
    private lateinit var cur: TVideo
    private var liked = false
    private val queue = ArrayList<TVideo>()
    private val played = HashSet<String>()
    private var curPlayable: TPlayable? = null
    private var step = 0          // étape de repli en cours (0 = meilleure qualité)
    private var netRetries = 0    // coupures réseau déjà réessayées sur la même étape
    private var refreshes = 0     // nouvelles résolutions (liens refusés ou expirés) pour la vidéo en cours
    private var lastRefreshAt = 0L
    private var startAt = 0L      // position de reprise (ms) pour le prochain démarrage de la source
    private var level = 0         // qualité en cours dans TPlayable.ladder (0 = la meilleure) : baisse toute seule quand ça rame
    private var everReady = false
    private var stallMs = 0L      // temps passé à attendre des données depuis la dernière lecture fluide
    private var okMs = 0L
    private var graceUntil = 0L
    private var loadJob: Job? = null
    private val ui = Handler(Looper.getMainLooper())
    private var errorDialog: android.app.AlertDialog? = null

    private val hideControls = object : Runnable {
        override fun run() {
            if (controls.menuOpen) { ui.postDelayed(this, 2000); return }
            if (player?.isPlaying == true) { controls.hide() } else ui.postDelayed(this, 2000)
        }
    }
    private val ticker = object : Runnable {
        override fun run() {
            updateNextHint()
            watchStalls()
            if (controls.visible) controls.refresh()
            ui.postDelayed(this, 500)
        }
    }
    private val hideFlash = Runnable { flash.visibility = View.GONE }
    // Relance après une coupure réseau : annulée dès qu'on change de vidéo (sinon elle relancerait l'ancienne).
    private val netRetry = Runnable { val p = curPlayable; if (p != null && player != null) start(p) }

    private fun showControls() {
        controls.show(cur.title)
        controls.setLiked(liked)
        ui.removeCallbacks(hideControls)
        ui.postDelayed(hideControls, 5000)
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
        AccountStore.load(this) // fixe le profil actif (cœurs et historique) si le processus a été recréé directement ici
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        val first = runCatching { TVideo.fromJson(JSONObject(intent.getStringExtra("v") ?: "")) }.getOrNull() ?: return finish()
        cur = first
        intent.getStringArrayListExtra("q")?.forEach { s -> runCatching { queue.add(TVideo.fromJson(JSONObject(s))) } }

        view = PlayerView(this).apply {
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            useController = false
            setBackgroundColor(Color.BLACK)
            setShutterBackgroundColor(Color.BLACK)
        }
        nextHint = bubble(Gravity.BOTTOM or Gravity.END, 140, 0)
        flash = bubble(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 80).apply { (layoutParams as FrameLayout.LayoutParams).setMargins(0, 80, 0, 0) }
        status = bubble(Gravity.CENTER, 0, 0).apply { visibility = View.VISIBLE; text = getString(R.string.tube_loading) }
        controls = PlayerControls(this, { player }, false, { queue.any { it.id !in played } }, { playNext() }, { flashMessage(it) })
        controls.enableLike { toggleLike() }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(nextHint)
            addView(flash)
            addView(status)
        }
        setContentView(root)
        view.requestFocus()

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        view.player = exo
        // Repli HLS : du H.264 en 1080p au plus, que le décodeur de la télé sait lire (le VP9/AV1 du flux adaptatif le fait échouer).
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(1920, 1080).setPreferredVideoMimeType(MimeTypes.VIDEO_H264).build()
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    status.visibility = View.GONE; netRetries = 0; everReady = true
                    // Plus d'une minute après la dernière relance : un nouveau lien expiré pourra être renouvelé.
                    if (refreshes > 0 && android.os.SystemClock.elapsedRealtime() - lastRefreshAt > 60_000) refreshes = 0
                }
                // Fin naturelle : on enchaîne ; s'il n'y a plus rien à proposer, on revient à la liste.
                if (state == Player.STATE_ENDED) { if (hasNext()) playNext() else finish() }
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.w("StreamXTube", "player error: ${error.errorCodeName}")
                onPlaybackFailure(error)
            }
        })
        load(cur)
    }

    override fun onStart() {
        super.onStart()
        ui.removeCallbacks(ticker)
        ui.post(ticker)
    }

    private fun hasNext() = queue.any { it.id !in played }

    /** Résout puis lance une vidéo ; les suggestions de YouTube alimentent la file de lecture automatique. */
    private fun load(v: TVideo, resumeMs: Long = 0L, fresh: Boolean = false) {
        cur = v
        played += v.id
        step = 0
        netRetries = 0
        level = 0; everReady = false; stallMs = 0; okMs = 0; graceUntil = 0
        if (!fresh) refreshes = 0
        startAt = resumeMs
        ui.removeCallbacks(netRetry)
        controls.closeMenu()
        curPlayable = null
        liked = false
        controls.setLiked(false)
        controls.setTitle(v.title)
        status.text = getString(R.string.tube_loading)
        status.visibility = View.VISIBLE
        nextHint.visibility = View.GONE
        player?.stop()
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                val p = Tube.resolve(this@TubePlayerActivity, v)
                curPlayable = p
                cur = p.video
                // Lectures/écritures locales (JSON) hors du thread principal.
                liked = withContext(Dispatchers.IO) {
                    TubeStore.markWatched(this@TubePlayerActivity, p.video)
                    Tube.invalidate()
                    TubeStore.isLiked(this@TubePlayerActivity, p.video.id)
                }
                controls.setLiked(liked)
                val fresh = p.related.filter { it.id !in played && queue.none { q -> q.id == it.id } }
                // On garde la file courte et variée : les premières suggestions de YouTube suffisent.
                if (queue.size < 6) queue.addAll(fresh.take(12))
                controls.setTitle(cur.title)
                start(p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("StreamXTube", "resolve failed: ${e.javaClass.simpleName}")
                status.visibility = View.GONE
                showErrorDialog()
            }
        }
    }

    private fun factoryFor(url: String): DefaultHttpDataSource.Factory {
        val ua = runCatching {
            when {
                url.contains("c=VISIONOS") -> YoutubeParsingHelper.getVisionOsUserAgent(null)
                else -> Tube.UA
            }
        }.getOrDefault(Tube.UA)
        return DefaultHttpDataSource.Factory().setUserAgent(ua).setConnectTimeoutMs(15_000).setReadTimeoutMs(20_000)
    }

    // Les liens googlevideo se lisent par tranches (voir ChunkedDataSource) : en une seule requête, le serveur finit par ne plus rien envoyer.
    private fun progressive(url: String): MediaSource {
        val http = factoryFor(url)
        val chunked = DataSource.Factory { ChunkedDataSource(http) }
        return ProgressiveMediaSource.Factory(chunked).createMediaSource(MediaItem.fromUri(url))
    }

    private fun hls(url: String): MediaSource =
        HlsMediaSource.Factory(factoryFor(url)).createMediaSource(MediaItem.fromUri(url))

    /** Sources à essayer dans l'ordre : image et son séparés (meilleure qualité), flux unique, HLS. Direct : HLS seulement. */
    private fun candidates(p: TPlayable): List<() -> MediaSource> = buildList {
        if (p.video.live && p.hlsUrl != null) { add { hls(p.hlsUrl) }; return@buildList }
        if (p.videoUrl != null && p.audioUrl != null) add {
            val v = p.ladder.getOrNull(level)?.second ?: p.videoUrl
            MergingMediaSource(progressive(v), progressive(p.audioUrl))
        }
        if (p.muxedUrl != null) add { progressive(p.muxedUrl) }
        if (p.hlsUrl != null) add { hls(p.hlsUrl) }
    }

    /** Si la lecture attend des données plus de 6 s en tout, on passe à la qualité du dessous, à la même position. */
    private fun watchStalls() {
        val p = curPlayable ?: return
        val q = player ?: return
        if (p.video.live || step != 0 || p.ladder.size < 2 || !everReady || !q.playWhenReady) return
        if (android.os.SystemClock.elapsedRealtime() < graceUntil) return
        if (q.playbackState == Player.STATE_BUFFERING) { stallMs += 500; okMs = 0 }
        else if (q.playbackState == Player.STATE_READY) { okMs += 500; if (okMs >= 30_000) stallMs = 0 }
        if (stallMs >= 6_000 && level < p.ladder.size - 1) {
            level++
            stallMs = 0; okMs = 0
            graceUntil = android.os.SystemClock.elapsedRealtime() + 15_000
            startAt = q.currentPosition
            flashMessage(getString(R.string.tube_quality, "${p.ladder[level].first}p"))
            android.util.Log.i("StreamXTube", "qualité abaissée : ${p.ladder[level].first}p (niveau $level sur ${p.ladder.size - 1})")
            start(p, quiet = true)
        }
    }

    private fun start(p: TPlayable, quiet: Boolean = false) {
        val exo = player ?: return
        controls.setLive(p.video.live)
        if (p.video.live) exo.setPlaybackSpeed(1f)
        val list = candidates(p)
        val make = list.getOrNull(step)
        if (make == null) { status.visibility = View.GONE; showErrorDialog(); return }
        // Direct : on démarre au bord du direct (position par défaut), pas au début de la fenêtre.
        if (p.video.live) exo.setMediaSource(make()) else exo.setMediaSource(make(), startAt.coerceAtLeast(0L))
        exo.prepare()
        // Si l'appli est passée en arrière-plan pendant le chargement, on ne démarre pas le son dans le vide.
        exo.playWhenReady = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (!quiet) showControls()
    }

    /** Erreur de lecture : on réessaie sur place si c'est une coupure réseau, sinon on passe à la source suivante ; la position est gardée. */
    private fun onPlaybackFailure(error: PlaybackException) {
        val p = curPlayable
        val exo = player
        if (p == null || exo == null) { status.visibility = View.GONE; showErrorDialog(); return }
        val live = p.video.live
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            // Direct resté en pause trop longtemps : on revient simplement au bord du direct.
            exo.seekToDefaultPosition()
            exo.prepare()
            return
        }
        if (!live) startAt = exo.currentPosition
        val net = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        // Lien refusé ou expiré (403, 404, 410) : on redemande des liens tout neufs, à la même position, avant de baisser la qualité.
        val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (code != null && code in listOf(403, 404, 410) && refreshes < 1) {
            refreshes++
            lastRefreshAt = android.os.SystemClock.elapsedRealtime()
            load(cur, if (live) 0L else startAt, fresh = true)
            return
        }
        if (net) {
            // Une coupure réseau ne se règle pas en changeant de source : on réessaie sur place, puis on prévient.
            if (netRetries < 2) {
                netRetries++
                status.visibility = View.VISIBLE
                ui.removeCallbacks(netRetry)
                ui.postDelayed(netRetry, 1500L * netRetries)
            } else {
                status.visibility = View.GONE
                showErrorDialog()
            }
            return
        }
        // Source illisible : on passe à la suivante (flux unique, puis HLS), à la même position.
        step++
        if (step >= candidates(p).size) { status.visibility = View.GONE; showErrorDialog(); return }
        start(p)
    }

    private fun showErrorDialog() {
        if (isFinishing || errorDialog?.isShowing == true) return
        errorDialog = android.app.AlertDialog.Builder(this)
            .setMessage(getString(R.string.tube_error))
            .setPositiveButton(R.string.retry) { _, _ -> load(cur, if (curPlayable?.video?.live == true) 0L else startAt) }
            .setNeutralButton(R.string.tube_next_video) { _, _ -> if (hasNext()) playNext() else finish() }
            .setNegativeButton(R.string.back) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun playNext() {
        val n = queue.firstOrNull { it.id !in played }
        queue.removeAll { it.id in played }
        if (n == null) return
        queue.remove(n)
        load(n)
    }

    private fun updateNextHint() {
        val p = player ?: return
        val next = queue.firstOrNull { it.id !in played }
        if (next == null || controls.visible || curPlayable == null || p.playbackState == Player.STATE_IDLE || p.isCurrentMediaItemLive) {
            nextHint.visibility = View.GONE
            return
        }
        val d = p.duration
        if (d <= 0) return
        if (d - p.currentPosition <= 15_000) {
            nextHint.text = getString(R.string.tube_up_next, next.title)
            nextHint.visibility = View.VISIBLE
        } else {
            nextHint.visibility = View.GONE
        }
    }

    private fun toggleLike() {
        val v = cur
        lifecycleScope.launch {
            liked = withContext(Dispatchers.IO) { TubeStore.toggleLike(this@TubePlayerActivity, v).also { Tube.invalidate() } }
            controls.setLiked(liked)
            flashMessage(getString(if (liked) R.string.tube_liked else R.string.tube_unliked))
        }
    }

    private fun flashMessage(text: String) {
        flash.text = text
        flash.visibility = View.VISIBLE
        ui.removeCallbacks(hideFlash)
        ui.postDelayed(hideFlash, 1200)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val p = player
        val down = e.action == KeyEvent.ACTION_DOWN
        val code = e.keyCode
        // Version de test seulement : la touche Q simule une lecture qui rame (pour vérifier la baisse de qualité).
        if (BuildConfig.DEBUG && down && code == KeyEvent.KEYCODE_Q) { stallMs = 6_000; graceUntil = 0; flashMessage("test : lecture qui rame"); return true }
        if (down && (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_BOOKMARK || code == KeyEvent.KEYCODE_STAR) && e.repeatCount == 0) {
            toggleLike(); return true
        }
        if (controls.menuOpen) {
            if (down && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE)) {
                controls.closeMenu()
                ui.removeCallbacks(hideControls); ui.postDelayed(hideControls, 5000)
                return true
            }
            return super.dispatchKeyEvent(e)
        }
        if (controls.visible) {
            if (down) {
                if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) { controls.hide(); ui.removeCallbacks(hideControls); return true }
                ui.removeCallbacks(hideControls); ui.postDelayed(hideControls, 5000)
            }
            if (down && p != null) when (code) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { p.playWhenReady = !p.playWhenReady; controls.refresh(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { p.playWhenReady = true; controls.refresh(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.playWhenReady = false; controls.refresh(); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { if (hasNext()) playNext(); return true }
            }
            return super.dispatchKeyEvent(e)
        }
        if (p != null && down) when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { p.playWhenReady = !p.playWhenReady; showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p.playWhenReady = true; return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.playWhenReady = false; showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { if (hasNext()) playNext(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (nextHint.visibility == View.VISIBLE) playNext() else showControls()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { showControls(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0)); flashMessage("−10 s"); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val t = p.currentPosition + 10_000
                p.seekTo(if (p.duration > 0) t.coerceAtMost(p.duration) else t); flashMessage("+10 s"); return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(hideControls)
        player?.pause()
    }

    override fun onDestroy() {
        errorDialog?.dismiss()
        ui.removeCallbacksAndMessages(null)
        loadJob?.cancel()
        player?.release()
        player = null
        super.onDestroy()
    }
}
