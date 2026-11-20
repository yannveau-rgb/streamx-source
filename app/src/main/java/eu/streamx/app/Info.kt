package eu.streamx.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/** Résumé, genres, durée et casting d'un titre, tels que les fournit l'abonnement. */
data class Info(val plot: String, val cast: String, val genre: String, val duration: String, val director: String)

object InfoCache {
    private val map = object : LinkedHashMap<String, Info>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Info>?) = size > 60
    }

    @Synchronized fun get(key: String): Info? = map[key]
    @Synchronized fun put(key: String, v: Info) { map[key] = v }
}

private fun clean(s: String?): String = s?.trim()?.takeIf { it.isNotBlank() && it != "null" && it != "N/A" } ?: ""

fun parseInfo(o: JSONObject?): Info? {
    o ?: return null
    val i = Info(
        clean(o.optString("plot").ifBlank { o.optString("description") }), clean(o.optString("cast").ifBlank { o.optString("actors") }),
        clean(o.optString("genre")), clean(o.optString("duration")), clean(o.optString("director"))
    )
    return if (i.plot.isEmpty() && i.cast.isEmpty() && i.genre.isEmpty() && i.duration.isEmpty()) null else i
}

suspend fun XtreamApi.vodInfo(a: Account, id: String): Info? {
    InfoCache.get("vod:$id")?.let { return it }
    return withContext(Dispatchers.IO) {
        try {
            val text = open(url(a, "get_vod_info") + "&vod_id=" + URLEncoder.encode(id, "UTF-8"), 30000).inputStream.bufferedReader().use { it.readText() }
            parseInfo(JSONObject(text).optJSONObject("info"))?.also { InfoCache.put("vod:$id", it) }
        } catch (e: Exception) {
            null
        }
    }
}

/** Genres · durée, résumé et casting sous le titre d'une fiche. */
@Composable
fun InfoBlock(info: Info?, modifier: Modifier = Modifier) {
    if (info == null) return
    Column(modifier) {
        val line = listOf(info.genre, info.duration).filter { it.isNotEmpty() }.joinToString("  ·  ")
        if (line.isNotEmpty()) Text(line, color = Color(0xFFD6C2FF), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (info.plot.isNotEmpty()) {
            Text(info.plot, color = SX.Text2, fontSize = 12.5.sp, lineHeight = 18.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).widthIn(max = 520.dp))
        }
        if (info.cast.isNotEmpty()) {
            Text(
                stringResource(R.string.info_cast, info.cast), color = SX.Muted, fontSize = 11.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp).widthIn(max = 520.dp)
            )
        }
    }
}
