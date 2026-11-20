package eu.streamx.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class M3uList(val name: String, val url: String)

data class M3uChannel(val name: String, val group: String, val logo: String?, val url: String)

/** Listes M3U ajoutées par la personne (une liste d'adresses par profil, avec copie globale). */
object M3uStore {
    private const val PREFS = "streamx_m3u"

    fun load(c: Context): List<M3uList> {
        val arr = try { JSONArray(c.getSharedPreferences(Profile.prefs(PREFS), Context.MODE_PRIVATE).getString("list", "[]")) } catch (e: Exception) { JSONArray() }
        val current = (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            M3uList(o.optString("name"), SecretBox.decrypt(o.optString("url")).orEmpty()).takeIf { l -> l.url.isNotBlank() }
        }
        if (current.isNotEmpty()) return current
        return findAllAcrossProfiles(c)
    }

    /** Retrouve toutes les listes enregistrées sur la télé, quel que soit le profil actif. */
    fun findAllAcrossProfiles(c: Context): List<M3uList> {
        val map = linkedMapOf<String, M3uList>()
        fun addFrom(spName: String) {
            try {
                val p = c.getSharedPreferences(spName, Context.MODE_PRIVATE)
                val s = p.getString("list", "[]") ?: "[]"
                val arr = JSONArray(s)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val url = SecretBox.decrypt(o.optString("url")).orEmpty().trim()
                    val name = o.optString("name").trim()
                    if (url.isNotBlank() && !map.containsKey(url)) {
                        map[url] = M3uList(name, url)
                    }
                }
            } catch (_: Exception) {}
        }
        addFrom(PREFS)
        if (Profile.ns.isNotBlank()) addFrom(Profile.prefs(PREFS))
        try {
            val spDir = java.io.File(c.applicationInfo.dataDir, "shared_prefs")
            if (spDir.exists() && spDir.isDirectory) {
                spDir.listFiles { f -> f.name.startsWith("streamx_m3u") && f.name.endsWith(".xml") }?.forEach { f ->
                    addFrom(f.name.removeSuffix(".xml"))
                }
            }
        } catch (_: Exception) {}
        return map.values.toList()
    }

    private fun save(c: Context, l: List<M3uList>) {
        val arr = JSONArray()
        l.forEach { arr.put(JSONObject().put("name", it.name).put("url", SecretBox.encrypt(it.url) ?: it.url)) }
        val s = arr.toString()
        c.getSharedPreferences(Profile.prefs(PREFS), Context.MODE_PRIVATE).edit().putString("list", s).apply()
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("list", s).apply()
    }

    fun add(c: Context, l: M3uList) = save(c, load(c).filter { it.url != l.url } + l)
    fun remove(c: Context, l: M3uList) = save(c, load(c).filter { it.url != l.url })
}

private val attrRe = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

/** Lit une liste M3U ligne par ligne (jusqu'à 40 000 chaînes). Renvoie null si l'adresse n'est pas une liste lisible. */
suspend fun parseM3u(address: String): List<M3uChannel>? = withContext(Dispatchers.IO) {
    try {
        val u = if (address.startsWith("http://") || address.startsWith("https://")) address else "http://$address"
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "StreamX/" + BuildConfig.VERSION_NAME)
        val out = ArrayList<M3uChannel>()
        var name = ""; var group = ""; var logo: String? = null
        var seenHeader = false
        c.inputStream.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#EXTM3U")) { seenHeader = true; continue }
                if (line.startsWith("#EXTINF")) {
                    seenHeader = true
                    val attrs = attrRe.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    group = attrs["group-title"]?.trim().orEmpty()
                    logo = attrs["tvg-logo"]?.trim()?.takeIf { it.startsWith("http") }
                    name = line.substringAfterLast(',', "").trim().ifBlank { attrs["tvg-name"].orEmpty() }
                } else if (!line.startsWith("#")) {
                    val web = line.startsWith("http://", ignoreCase = true) || line.startsWith("https://", ignoreCase = true)
                    if (web && seenHeader && name.isNotBlank()) out.add(M3uChannel(name, group.intern(), logo, line))
                    name = ""; group = ""; logo = null
                    if (out.size >= 40_000) break
                }
            }
        }
        if (seenHeader) out else null
    } catch (e: Exception) {
        null
    }
}

@Composable
fun M3uScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var lists by remember { mutableStateOf(M3uStore.load(ctx)) }
    var selected by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(lists.isEmpty()) }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<String?>(null) }
    val current = lists.getOrNull(selected)
    val channels by produceState<List<M3uChannel>?>(null, current?.url) {
        value = null
        group = null
        current?.let { value = parseM3u(it.url) ?: emptyList() }
    }
    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 24.dp)) {
        Row(Modifier.padding(end = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.m3u), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            ActionChip(stringResource(R.string.back)) { onBack() }
        }
        Spacer(Modifier.height(10.dp))
        if (lists.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 28.dp, top = 3.dp, bottom = 3.dp)) {
                items(lists.size) { i -> SelectChip(lists[i].name.ifBlank { lists[i].url.take(24) }, i == selected && !adding) { selected = i; adding = false } }
                item { ActionChip(stringResource(R.string.m3u_add)) { adding = true } }
                if (current != null && !adding) item {
                    ActionChip(stringResource(R.string.m3u_remove)) {
                        M3uStore.remove(ctx, current)
                        lists = M3uStore.load(ctx)
                        selected = 0
                        adding = lists.isEmpty()
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (adding) {
            val panelShape = RoundedCornerShape(SX.RPanel)
            Column(Modifier.width(420.dp).glass(panelShape, 0.85f).padding(20.dp)) {
                Text(stringResource(R.string.m3u_help), color = SX.Muted, fontSize = 11.5.sp, modifier = Modifier.padding(bottom = 10.dp))
                LoginField(stringResource(R.string.m3u_name_label), name, { name = it })
                LoginField(stringResource(R.string.m3u_url_label), address, { address = it }, uri = true)
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    ActionChip(stringResource(R.string.m3u_save)) {
                        if (address.isNotBlank()) {
                            M3uStore.add(ctx, M3uList(name.trim().ifBlank { address.trim().substringAfter("://").take(24) }, address.trim()))
                            lists = M3uStore.load(ctx)
                            selected = lists.size - 1
                            adding = false
                            name = ""; address = ""
                        }
                    }
                    if (lists.isNotEmpty()) ActionChip(stringResource(R.string.cancel)) { adding = false }
                }
            }
        } else if (current != null) {
            val list = channels
            when {
                list == null -> Text(stringResource(R.string.cat_loading), color = SX.Muted, fontSize = 13.sp)
                list.isEmpty() -> Text(stringResource(R.string.m3u_error), color = SX.Danger, fontSize = 13.sp)
                else -> {
                    val groups = remember(list) { list.map { it.group }.filter { it.isNotBlank() }.distinct().take(60) }
                    if (groups.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 28.dp, top = 3.dp, bottom = 3.dp)) {
                            item { SelectChip(stringResource(R.string.facet_all), group == null) { group = null } }
                            items(groups) { g -> SelectChip(g, group == g) { group = g } }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    val shown = remember(list, group) { if (group == null) list else list.filter { it.group == group } }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
                        items(shown.size, key = { it }) { i ->
                            val ch = shown[i]
                            val shape = RoundedCornerShape(12.dp)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(end = 28.dp)
                                    .tvFocus(shape, cornerRadius = 12.dp, scale = 1.02f) {
                                        val vod = Regex("\\.(mp4|mkv|avi)(\\?.*)?$", RegexOption.IGNORE_CASE).containsMatchIn(ch.url)
                                        ctx.startActivity(playIntent(ctx, ch.url, ch.name, !vod, ch.logo))
                                    }
                                    .glass(shape, 0.7f)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.width(54.dp).height(32.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF22254A))) {
                                    NetImage(
                                        url = ch.logo,
                                        widthDp = 54.dp,
                                        heightDp = 32.dp,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(ch.name, color = Color.White, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (ch.group.isNotBlank()) Text(ch.group, color = SX.Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Spacer(Modifier.height(5.dp))
                        }
                    }
                }
            }
        }
    }
}
