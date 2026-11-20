package eu.streamx.app

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Météo actuelle (Open-Meteo, sans clé). Position approximative déduite de l'adresse IP une seule fois, puis gardée en local. */
object Weather {
    class Now(val temp: Int, val code: Int, val day: Boolean)

    private const val PREFS = "streamx_weather"
    private const val TTL_MS = 30 * 60 * 1000L
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    suspend fun current(ctx: Context): Now? = withContext(Dispatchers.IO) {
        val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = sp.getLong("at", 0L)
        if (System.currentTimeMillis() - at < TTL_MS && sp.contains("temp")) {
            return@withContext Now(sp.getInt("temp", 0), sp.getInt("code", 0), sp.getBoolean("day", true))
        }
        var lat = sp.getString("lat", null)
        var lon = sp.getString("lon", null)
        if (lat == null || lon == null) {
            val j = get("https://ipwho.is/?fields=success,latitude,longitude")?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (j != null && j.optBoolean("success", false)) {
                lat = j.optDouble("latitude").toString(); lon = j.optDouble("longitude").toString()
                sp.edit().putString("lat", lat).putString("lon", lon).apply()
            }
        }
        if (lat == null || lon == null) return@withContext null
        val body = get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day&timezone=auto")
            ?: return@withContext null
        val c = runCatching { JSONObject(body).getJSONObject("current") }.getOrNull() ?: return@withContext null
        val now = Now(c.getDouble("temperature_2m").roundToInt(), c.getInt("weather_code"), c.optInt("is_day", 1) == 1)
        sp.edit().putLong("at", System.currentTimeMillis()).putInt("temp", now.temp).putInt("code", now.code).putBoolean("day", now.day).apply()
        now
    }
}

private enum class Sky { CLEAR, PARTLY, CLOUD, FOG, RAIN, SNOW, STORM }

private fun skyOf(code: Int) = when (code) {
    0 -> Sky.CLEAR
    1, 2 -> Sky.PARTLY
    3 -> Sky.CLOUD
    45, 48 -> Sky.FOG
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> Sky.RAIN
    71, 73, 75, 77, 85, 86 -> Sky.SNOW
    95, 96, 99 -> Sky.STORM
    else -> Sky.CLOUD
}

private val SunC = Color(0xFFFBBF24)
private val MoonC = Color(0xFFC7D2FE)
private val CloudC = Color(0xFFE5E7EF)
private val RainC = Color(0xFF7DD3FC)

private fun DrawScope.sun(c: Offset, r: Float, w: Float) {
    drawCircle(SunC, r, c)
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val d = Offset(cos(a).toFloat(), sin(a).toFloat())
        drawLine(SunC, c + d * (r * 1.45f), c + d * (r * 1.95f), w, StrokeCap.Round)
    }
}

private fun DrawScope.moon(c: Offset, r: Float) {
    drawCircle(MoonC, r, c)
    drawCircle(Color(0xFF0B0D1A), r * 0.85f, c + Offset(r * 0.55f, -r * 0.35f))
}

private fun DrawScope.cloud(x: Float, y: Float, s: Float, col: Color = CloudC) {
    drawCircle(col, s * 0.30f, Offset(x + s * 0.30f, y + s * 0.55f))
    drawCircle(col, s * 0.38f, Offset(x + s * 0.62f, y + s * 0.38f))
    drawCircle(col, s * 0.28f, Offset(x + s * 0.90f, y + s * 0.58f))
    drawRoundRect(col, Offset(x + s * 0.30f, y + s * 0.55f), androidx.compose.ui.geometry.Size(s * 0.60f, s * 0.31f), androidx.compose.ui.geometry.CornerRadius(s * 0.1f))
}

@Composable
private fun WeatherGlyph(code: Int, day: Boolean, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.07f
        when (skyOf(code)) {
            Sky.CLEAR -> if (day) sun(Offset(s / 2, s / 2), s * 0.2f, w) else moon(Offset(s / 2, s / 2), s * 0.3f)
            Sky.PARTLY -> {
                if (day) sun(Offset(s * 0.36f, s * 0.36f), s * 0.15f, w) else moon(Offset(s * 0.36f, s * 0.36f), s * 0.22f)
                cloud(s * 0.12f, s * 0.30f, s * 0.82f)
            }
            Sky.CLOUD -> cloud(s * 0.05f, s * 0.2f, s * 0.9f)
            Sky.FOG -> {
                cloud(s * 0.08f, s * 0.08f, s * 0.84f)
                for (i in 0..1) drawLine(CloudC, Offset(s * 0.2f, s * (0.72f + i * 0.14f)), Offset(s * 0.8f, s * (0.72f + i * 0.14f)), w, StrokeCap.Round)
            }
            Sky.RAIN -> {
                cloud(s * 0.08f, s * 0.04f, s * 0.84f)
                for (i in 0..2) drawLine(RainC, Offset(s * (0.32f + i * 0.17f), s * 0.70f), Offset(s * (0.26f + i * 0.17f), s * 0.88f), w, StrokeCap.Round)
            }
            Sky.SNOW -> {
                cloud(s * 0.08f, s * 0.04f, s * 0.84f)
                for (i in 0..2) drawCircle(Color.White, s * 0.045f, Offset(s * (0.30f + i * 0.2f), s * (0.76f + (i % 2) * 0.08f)))
            }
            Sky.STORM -> {
                cloud(s * 0.08f, s * 0.04f, s * 0.84f, Color(0xFFB4BAD0))
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(s * 0.54f, s * 0.58f); lineTo(s * 0.40f, s * 0.80f); lineTo(s * 0.52f, s * 0.80f); lineTo(s * 0.44f, s * 0.98f)
                    lineTo(s * 0.68f, s * 0.72f); lineTo(s * 0.55f, s * 0.72f); close()
                }
                drawPath(p, SunC)
            }
        }
    }
}

/** Heure (mise à jour à la minute) et météo discrète, façon Google TV. */
@Composable
fun HomeClockWeather(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var time by remember { mutableStateOf(clockText()) }
    var wx by remember { mutableStateOf<Weather.Now?>(null) }
    LaunchedEffect(Unit) {
        while (true) { time = clockText(); delay(60_000L - System.currentTimeMillis() % 60_000L) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            Weather.current(ctx)?.let { wx = it }
            delay(10 * 60_000L)
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(time, color = Color.White.copy(alpha = 0.9f), fontSize = 20.sp, fontWeight = FontWeight.Medium)
        wx?.let {
            Spacer(Modifier.width(12.dp))
            WeatherGlyph(it.code, it.day, 20.dp)
            Spacer(Modifier.width(5.dp))
            Text("${it.temp}°", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

private fun clockText(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
