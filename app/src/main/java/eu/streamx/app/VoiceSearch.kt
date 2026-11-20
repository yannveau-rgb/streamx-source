package eu.streamx.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import java.util.Locale

private enum class VoicePhase { IDLE, STARTING, LISTENING, ERROR }

/** Service de reconnaissance de Google TV (celui de la télécommande vocale) : bien plus réactif que l'ancien écran « Google qui écoute ». */
private val KATNISS = ComponentName("com.google.android.katniss", "com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService")

/**
 * Recherche vocale intégrée : l'appli écoute elle-même (SpeechRecognizer), affiche le micro qui réagit à la voix et le texte
 * au fur et à mesure, sans quitter StreamX. Si la reconnaissance intégrée ne démarre pas (service absent, micro refusé, aucune
 * réponse après 4 s), on retombe sur l'ancien écran vocal du système. Renvoie la fonction qui lance l'écoute.
 */
@Composable
fun rememberVoiceSearch(onResult: (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val onRes by rememberUpdatedState(onResult)
    var phase by remember { mutableStateOf(VoicePhase.IDLE) }
    var partial by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    var errorRes by remember { mutableStateOf(R.string.voice_none) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { onRes(it) }
        }
    }

    fun release() {
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
    }

    fun close() { release(); phase = VoicePhase.IDLE; partial = ""; level = 0f }

    fun fallbackToSystemScreen() {
        close()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        runCatching { legacy.launch(i) }
    }

    fun begin() {
        release()
        partial = ""; level = 0f
        val kat = runCatching { ctx.packageManager.getServiceInfo(KATNISS, 0); true }.getOrDefault(false)
        val sr = runCatching {
            if (kat) SpeechRecognizer.createSpeechRecognizer(ctx, KATNISS)
            else if (SpeechRecognizer.isRecognitionAvailable(ctx)) SpeechRecognizer.createSpeechRecognizer(ctx) else null
        }.getOrNull()
        if (sr == null) { fallbackToSystemScreen(); return }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { phase = VoicePhase.LISTENING }
            override fun onBeginningOfSpeech() { phase = VoicePhase.LISTENING }
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { level = 0f }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial = it }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (text.isNotEmpty()) { close(); onRes(text) } else { errorRes = R.string.voice_none; phase = VoicePhase.ERROR }
            }

            override fun onError(error: Int) {
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> { errorRes = R.string.voice_none; phase = VoicePhase.ERROR }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> fallbackToSystemScreen()
                    else -> { errorRes = R.string.voice_unavailable; phase = VoicePhase.ERROR }
                }
            }
        })
        recognizer = sr
        phase = VoicePhase.STARTING
        val tag = ctx.resources.configuration.locales[0].toLanguageTag()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        runCatching { sr.startListening(i) }.onFailure { fallbackToSystemScreen() }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) begin() else fallbackToSystemScreen()
    }

    // Aucune réaction du service en 4 s : on ne laisse pas l'utilisateur devant un micro muet.
    LaunchedEffect(phase) {
        if (phase == VoicePhase.STARTING) { delay(4000); if (phase == VoicePhase.STARTING) fallbackToSystemScreen() }
    }
    DisposableEffect(Unit) { onDispose { recognizer?.let { runCatching { it.destroy() } } } }

    if (phase != VoicePhase.IDLE) VoiceOverlay(phase, partial, level, errorRes, onCancel = { close() }, onRetry = { begin() })

    return {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
}

@Composable
private fun VoiceOverlay(phase: VoicePhase, partial: String, level: Float, errorRes: Int, onCancel: () -> Unit, onRetry: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(phase) { delay(50); runCatching { focus.requestFocus() } }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val breathe by pulse.animateFloat(1f, 1.12f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "breathe")
    val lvl by animateFloatAsState(level, tween(90), label = "level")
    val listening = phase == VoicePhase.LISTENING
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xE60B0D19)), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 560.dp).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(150.dp)) {
                    // Halo qui suit la voix, puis le micro.
                    Box(Modifier.size(110.dp).scale(if (listening) 1f + lvl * 0.55f else 1f).clip(CircleShape).background(Color(0x332DD4BF)))
                    Box(
                        Modifier.size(84.dp).scale(if (listening && lvl < 0.05f) breathe else 1f).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFF2DD4BF), Color(0xFF7B52FF)))),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.Mic, null, tint = Color.White, modifier = Modifier.size(40.dp)) }
                }
                val title = when (phase) {
                    VoicePhase.STARTING -> stringResource(R.string.voice_starting)
                    VoicePhase.LISTENING -> stringResource(R.string.voice_listening)
                    else -> stringResource(errorRes)
                }
                Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                // Ce que l'appli entend, en direct.
                Text(
                    if (partial.isBlank()) " " else "« $partial »", color = SX.Lilac, fontSize = 17.sp, textAlign = TextAlign.Center,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().height(70.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (phase == VoicePhase.ERROR) PillButton(stringResource(R.string.retry), true, Modifier.focusRequester(focus)) { onRetry() }
                    PillButton(stringResource(R.string.voice_cancel), false, if (phase == VoicePhase.ERROR) Modifier else Modifier.focusRequester(focus)) { onCancel() }
                }
            }
        }
    }
}
