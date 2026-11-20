package eu.streamx.app

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date

/** Un programme du guide : titre et horaires. */
data class Epg(val title: String, val startMs: Long, val endMs: Long)

private fun b64(s: String): String = try { String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim() } catch (e: Exception) { s }

/** Programme en cours et suivants d'une chaîne (guide fourni par l'abonnement, quand il existe). */
suspend fun XtreamApi.shortEpg(a: Account, streamId: String, full: Boolean = false): List<Epg>? = withContext(Dispatchers.IO) {
    try {
        // « get_short_epg » ne renvoie parfois que les programmes à venir ; la table complète contient aussi celui en cours.
        val action = if (full) "get_simple_data_table" else "get_short_epg"
        val text = open(url(a, action) + "&stream_id=" + URLEncoder.encode(streamId, "UTF-8") + if (full) "" else "&limit=3", 15000)
            .inputStream.bufferedReader().use { it.readText() }
        val arr = JSONObject(text).optJSONArray("epg_listings") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            val s = o.optString("start_timestamp").toLongOrNull() ?: return@mapNotNull null
            val e = o.optString("stop_timestamp").toLongOrNull() ?: return@mapNotNull null
            val t = b64(o.optString("title"))
            if (t.isBlank()) null else Epg(t, s * 1000L, e * 1000L)
        }.sortedBy { it.startMs }
    } catch (e: Exception) {
        null
    }
}

/** Programme en cours d'une chaîne, avec un cache de 2 minutes (la vignette survolée le réclame à chaque passage). */
object EpgNow {
    private val cache = HashMap<String, Pair<Long, List<Epg>>>()

    suspend fun current(a: Account, streamId: String): Epg? {
        val now = System.currentTimeMillis()
        val list = synchronized(this) { cache[streamId]?.takeIf { now - it.first < 120_000L }?.second }
            ?: run {
                var l = XtreamApi.shortEpg(a, streamId)
                if (l == null || l.none { it.startMs <= now && now < it.endMs }) {
                    val f = XtreamApi.shortEpg(a, streamId, full = true)
                    if (f != null && f.isNotEmpty()) l = f
                }
                l?.also { synchronized(this) { cache[streamId] = now to it } }
            }
        return list?.firstOrNull { it.startMs <= now && now < it.endMs }
    }
}

/** Bandeau EPG enrichi au bas de « TV en direct » : logo de la chaîne, nom nettoyé, programme en cours avec jauge dégradée et programme suivant. */
@Composable
fun EpgBar(channel: Item?, epg: List<Epg>?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(60.dp)
            .glass(shape, 0.95f)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (channel == null) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(SX.Turquoise))
            Spacer(Modifier.width(9.dp))
            Text(stringResource(R.string.epg_hint), color = SX.Muted, fontSize = 11.5.sp)
            return@Row
        }

        // Vignette du logo de la chaîne
        val logoShape = RoundedCornerShape(9.dp)
        Box(
            Modifier
                .width(56.dp)
                .height(42.dp)
                .clip(logoShape)
                .background(Color(0xFF14182D))
                .border(0.6.dp, Color(0x30FFFFFF), logoShape)
                .padding(5.dp),
            contentAlignment = Alignment.Center
        ) {
            val logoUrl = ChannelLogos.logo(channel.name, channel.image)
            if (!logoUrl.isNullOrBlank()) {
                NetImage(
                    url = logoUrl,
                    widthDp = 46.dp,
                    heightDp = 32.dp,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                )
            } else {
                Text(
                    text = cleanChannelName(channel.name).take(4),
                    color = Color.White,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        val ctx = LocalContext.current
        val fv = FavVersion.v
        val isFav = remember(channel.id, fv) { Favorites.contains(ctx, Kind.LIVE, channel.id) }
        val cleanName = remember(channel.name) { cleanChannelName(channel.name) }
        val now = System.currentTimeMillis()
        val cur = epg?.firstOrNull { it.startMs <= now && now < it.endMs }
        val next = epg?.firstOrNull { it.startMs >= (cur?.endMs ?: now) }
        val df = DateFormat.getTimeInstance(DateFormat.SHORT)

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = cleanName,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x283DE0C5))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = stringResource(R.string.epg_now).uppercase(),
                        color = SX.Turquoise,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.weight(1f))
                // Indication claire pour ajouter/retirer la chaîne en favoris à la télécommande
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isFav) Color(0x33EC4899) else Color(0x1FFFFFFF))
                        .border(0.6.dp, if (isFav) Color(0x80EC4899) else Color(0x33FFFFFF), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(if (isFav) R.string.epg_fav_on else R.string.epg_fav_hint),
                        color = if (isFav) Color(0xFFF9A8D4) else SX.Muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            when {
                epg == null -> Text(stringResource(R.string.cat_loading), color = SX.Muted, fontSize = 10.5.sp)
                cur == null && next == null -> Text(stringResource(R.string.epg_none), color = SX.Muted, fontSize = 10.5.sp)
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (cur != null) {
                        Text(
                            text = cur.title,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Box(
                            Modifier
                                .padding(horizontal = 9.dp)
                                .height(4.dp)
                                .width(85.dp)
                                .clip(CircleShape)
                                .background(Color(0x2EFFFFFF))
                        ) {
                            val frac = ((now - cur.startMs).toFloat() / (cur.endMs - cur.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                            Box(
                                Modifier
                                    .fillMaxWidth(frac)
                                    .fillMaxHeight()
                                    .background(Brush.horizontalGradient(listOf(SX.Violet, SX.Turquoise)))
                            )
                        }
                        Text(
                            text = df.format(Date(cur.startMs)) + " – " + df.format(Date(cur.endMs)),
                            color = SX.Muted,
                            fontSize = 10.5.sp,
                            maxLines = 1
                        )
                    }
                    if (next != null) {
                        Text(
                            text = "   •   " + stringResource(R.string.epg_next) + " : " + next.title + " (" + df.format(Date(next.startMs)) + ")",
                            color = SX.Muted,
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(0.9f, fill = false)
                        )
                    }
                }
            }
        }
    }
}
