package eu.streamx.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.TreeMap

data class Episode(val id: String, val num: Int, val title: String, val ext: String)

internal object EpisodesCache {
    private val map = object : LinkedHashMap<String, Map<Int, List<Episode>>>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<Int, List<Episode>>>?) = size > 40
    }
    @Synchronized fun get(id: String): Map<Int, List<Episode>>? = map[id]
    @Synchronized fun put(id: String, v: Map<Int, List<Episode>>) { map[id] = v }
    @Synchronized fun clear() = map.clear()
    @Synchronized fun remove(id: String) { map.remove(id) }
}

private val episodePrefixRe = Regex("^.*?\\bS\\d{1,2}\\s*E\\d{1,3}\\s*[-–:·]?\\s*", RegexOption.IGNORE_CASE)

/** « True Detective - S01E01 - La longue obscurité » devient « La longue obscurité » (vide s'il ne reste que le préfixe). */
fun cleanEpisodeTitle(t: String): String = episodePrefixRe.replaceFirst(t.trim(), "").trim()

suspend fun XtreamApi.episodes(a: Account, seriesId: String): Map<Int, List<Episode>>? {
    EpisodesCache.get(seriesId)?.let { return it }
    return withContext(Dispatchers.IO) {
        try {
            val text = open(url(a, "get_series_info") + "&series_id=" + URLEncoder.encode(seriesId, "UTF-8"), 60000)
                .inputStream.bufferedReader().use { it.readText() }
            fun parse(arr: JSONArray): List<Episode> = (0 until arr.length()).mapNotNull {
                val o = arr.optJSONObject(it) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isBlank()) null else Episode(
                    id, o.optInt("episode_num", it + 1), cleanEpisodeTitle(o.optString("title")),
                    o.optString("container_extension").ifBlank { "mkv" }
                )
            }.distinctBy { it.id }
            val out = TreeMap<Int, List<Episode>>()
            val root = JSONObject(text)
            parseInfo(root.optJSONObject("info"))?.let { InfoCache.put("series:$seriesId", it) }
            when (val ep = root.opt("episodes")) {
                is JSONObject -> for (k in ep.keys()) {
                    val s = k.toIntOrNull() ?: continue
                    out[s] = parse(ep.optJSONArray(k) ?: continue)
                }
                is JSONArray -> for (i in 0 until ep.length()) {
                    val arr = ep.optJSONArray(i) ?: continue
                    out[i + 1] = parse(arr)
                }
            }
            EpisodesCache.put(seriesId, out)
            out
        } catch (e: Exception) {
            null
        }
    }
}

@Composable
fun SelectChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val pill = RoundedCornerShape(50)
    Text(
        label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = modifier
            .tvFocus(pill, onClick = onClick)
            .then(
                if (selected) Modifier.clip(pill).background(Brush.horizontalGradient(listOf(SX.Violet, SX.VioletLight)))
                else Modifier.glass(pill, 1.2f)
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
fun SeriesScreen(account: Account, item: Item, onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onBack)
    val tick = LocalResumeTick.current
    var data by remember(item.id) { mutableStateOf(EpisodesCache.get(item.id)) }
    var failed by remember(item.id) { mutableStateOf(false) }
    var season by remember(item.id) { mutableIntStateOf(data?.keys?.firstOrNull() ?: 1) }
    LaunchedEffect(item.id) {
        if (data == null) {
            val r = XtreamApi.episodes(account, item.id)
            if (r == null) failed = true else { data = r; season = r.keys.firstOrNull() ?: 1 }
        }
    }
    val histVer = HistoryVersion.v
    val watching = remember(tick, histVer, data) { History.load(ctx) }
    val seen = remember(tick, histVer, data) { History.seen(ctx) }

    fun play(e: Episode, s: Int, pos: Long) {
        val flat = data?.entries?.flatMap { (sn, l) -> l.map { sn to it } } ?: emptyList()
        val at = flat.indexOfFirst { it.second.id == e.id }
        val queue = if (at >= 0) flat.drop(at + 1).take(30).map { (sn, ep) ->
            QueueItem(streamUrl(account, "series", ep.id, ep.ext), cleanTitle(item.name) + " · S$sn E${ep.num}", "ep:" + ep.id)
        } else emptyList()
        ctx.startActivity(
            playIntent(
                ctx, streamUrl(account, "series", e.id, e.ext), cleanTitle(item.name) + " · S$s E${e.num}", false,
                item.image, "ep:" + e.id, pos, "series", item.cat, queue
            )
        )
    }

    val p = rememberPosterPalette(item.image, item.id)
    Box(Modifier.fillMaxSize()) {
        DetailAmbientBackdrop(item)
        Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
            Row(Modifier.padding(horizontal = 28.dp)) {
                PosterFrame(item.image, widthDp = 130.dp, heightDp = 195.dp, glowColor = p.first.copy(alpha = 0.42f))
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    val badge = RoundedCornerShape(SX.RBadge)
                    Text(
                        stringResource(R.string.badge_series),
                        color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.glass(badge, 0.95f).padding(horizontal = 9.dp, vertical = 2.5.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        cleanTitle(item.name), color = Color.White, fontSize = 28.sp, lineHeight = 28.sp, fontFamily = SX.Condensed,
                        fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    val meta = listOfNotNull(yearOf(item.name), item.rating.takeIf { it > 0f }?.let { "★ " + "%.1f".format(it) }).joinToString("  ·  ")
                    if (meta.isNotEmpty()) Text(meta, color = SX.Text2, fontSize = 11.5.sp, modifier = Modifier.padding(top = 3.dp))
                    InfoBlock(if (data != null) InfoCache.get("series:" + item.id) else null, Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(10.dp))
                    val d = data
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (d != null && d.isNotEmpty()) {
                            val ids = remember(d) { d.values.flatten().map { "ep:" + it.id }.toSet() }
                            val last = watching.firstOrNull { it.key in ids }
                            val found = last?.let { w -> d.entries.firstNotNullOfOrNull { (s, l) -> l.firstOrNull { "ep:" + it.id == w.key }?.let { s to it } } }
                            if (last != null && found != null) {
                                PillButton(stringResource(R.string.resume_episode, found.first, found.second.num), Icons.Filled.PlayArrow, true) {
                                    play(found.second, found.first, last.pos)
                                }
                            } else {
                                val s0 = d.keys.first()
                                val e0 = d[s0]!!.first()
                                PillButton(stringResource(R.string.play_episode, s0, e0.num), Icons.Filled.PlayArrow, true) { play(e0, s0, 0L) }
                            }
                        }
                        ListButton(Kind.SERIES, item)
                        TrailerButton(Kind.SERIES, item)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val d = data
            when {
                d == null -> Text(stringResource(if (failed) R.string.items_error else R.string.cat_loading), color = SX.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 28.dp))
                d.isEmpty() -> Text(stringResource(R.string.items_empty), color = SX.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 28.dp))
                else -> {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 4.dp)
                    ) {
                        items(d.keys.toList(), key = { it }) { s -> SelectChip(stringResource(R.string.season_label, s), s == season) { season = s } }
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 28.dp, bottom = 20.dp)) {
                        items(d[season] ?: emptyList(), key = { it.id }) { e ->
                            val shape = RoundedCornerShape(12.dp)
                            val key = "ep:" + e.id
                            val w = watching.firstOrNull { it.key == key }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(end = 28.dp)
                                    .tvFocus(shape, scale = 1.02f) { play(e, season, w?.pos ?: 0L) }
                                    .glass(shape, 0.75f)
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("E${e.num}", color = SX.VioletLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(42.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        e.title.ifBlank { stringResource(R.string.episode_label, e.num) },
                                        color = if (key in seen) SX.Muted else Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    if (w != null && w.dur > 0) {
                                        Spacer(Modifier.height(4.dp))
                                        Box(Modifier.fillMaxWidth().height(2.5.dp).clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF))) {
                                            Box(
                                                Modifier
                                                    .fillMaxWidth((w.pos.toFloat() / w.dur).coerceIn(0.02f, 1f))
                                                    .fillMaxHeight()
                                                    .background(Brush.horizontalGradient(listOf(SX.Violet, SX.VioletLight)))
                                            )
                                        }
                                    }
                                }
                                if (key in seen) Icon(Icons.Outlined.Check, null, tint = SX.Turquoise, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }
}
