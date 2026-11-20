package eu.streamx.app

import android.content.Context
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

data class Fav(
    val kind: String,
    val id: String,
    val name: String,
    val image: String?,
    val ext: String?,
    val cat: String = "",
    val rating: Float = 0f,
    val hub: String = ""
) {
    fun toItem() = Item(
        id = id,
        name = name,
        image = image,
        ext = ext,
        cat = cat,
        rating = rating,
        kind = Kind.entries.firstOrNull { it.key == kind } ?: Kind.VOD
    )
}

object FavVersion {
    var v by mutableIntStateOf(0)
}

object Favorites {
    private const val PREFS = "streamx_favs"
    private const val KEY = "items"
    private val listCache = HashMap<String, List<Fav>>()
    private val keyCache = HashMap<String, Set<String>>()

    @Synchronized
    fun load(c: Context): List<Fav> {
        val ns = Profile.ns
        listCache[ns]?.let { return it }
        val raw = c.getSharedPreferences(Profile.prefs(PREFS), Context.MODE_PRIVATE).getString(KEY, null)
        if (raw == null) {
            listCache[ns] = emptyList()
            keyCache[ns] = emptySet()
            return emptyList()
        }
        val loaded = try {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Fav(
                    kind = o.optString("kind"),
                    id = o.optString("id"),
                    name = o.optString("name"),
                    image = o.optString("image").ifEmpty { null },
                    ext = o.optString("ext").ifEmpty { null },
                    cat = o.optString("cat"),
                    rating = o.optDouble("rating", 0.0).toFloat(),
                    hub = o.optString("hub")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
        listCache[ns] = loaded
        keyCache[ns] = loaded.mapTo(HashSet(loaded.size * 2)) { it.kind + ":" + it.id }
        return loaded
    }

    @Synchronized
    private fun save(c: Context, list: List<Fav>) {
        val ns = Profile.ns
        listCache[ns] = list
        keyCache[ns] = list.mapTo(HashSet(list.size * 2)) { it.kind + ":" + it.id }
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject()
                    .put("kind", it.kind)
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("image", it.image ?: "")
                    .put("ext", it.ext ?: "")
                    .put("cat", it.cat)
                    .put("rating", it.rating.toDouble())
                    .put("hub", it.hub)
            )
        }
        c.getSharedPreferences(Profile.prefs(PREFS), Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
        FavVersion.v++
    }

    @Synchronized
    fun contains(c: Context, kind: Kind, id: String): Boolean {
        val ns = Profile.ns
        val keys = keyCache[ns] ?: run { load(c); keyCache[ns] ?: emptySet() }
        return (kind.key + ":" + id) in keys
    }

    /** Ajoute ou retire le titre. Renvoie true s'il est maintenant dans la liste. */
    @Synchronized
    fun toggle(c: Context, kind: Kind, item: Item, hub: String = ""): Boolean {
        val l = load(c)
        return if (contains(c, kind, item.id)) {
            save(c, l.filterNot { it.kind == kind.key && it.id == item.id })
            false
        } else {
            save(c, listOf(Fav(kind.key, item.id, item.name, item.image, item.ext, item.cat, item.rating, hub)) + l)
            true
        }
    }

    @Synchronized
    fun clear(c: Context) {
        val ns = Profile.ns
        listCache.remove(ns)
        keyCache.remove(ns)
        c.getSharedPreferences(Profile.prefs(PREFS), Context.MODE_PRIVATE).edit().clear().apply()
        FavVersion.v++
    }
}

@Composable
fun FavoritesScreen(account: Account, onDetail: OpenDetail) {
    val ctx = LocalContext.current
    val fv = FavVersion.v
    val hv = HiddenVersion.v
    val cv = Catalog.version
    val hiddenVod = remember(hv) { HiddenStore.load(ctx, Kind.VOD) }
    val hiddenSeries = remember(hv) { HiddenStore.load(ctx, Kind.SERIES) }
    val hiddenLive = remember(hv) { HiddenStore.load(ctx, Kind.LIVE) }

    val favs = remember(fv, hv, hiddenVod, hiddenSeries, hiddenLive) {
        Favorites.load(ctx).filter { f ->
            when (f.kind) {
                Kind.VOD.key -> f.cat !in hiddenVod
                Kind.SERIES.key -> f.cat !in hiddenSeries
                Kind.LIVE.key -> f.cat !in hiddenLive
                else -> true
            }
        }
    }

    // Classification intelligente et tri alphabétique des favoris
    val liveCatNames = remember(cv) { Catalog.categoriesOf(Kind.LIVE)?.associate { it.id to it.name } ?: emptyMap() }
    val vodCatNames = remember(cv) { Catalog.categoriesOf(Kind.VOD)?.associate { it.id to it.name } ?: emptyMap() }
    val seriesCatNames = remember(cv) { Catalog.categoriesOf(Kind.SERIES)?.associate { it.id to it.name } ?: emptyMap() }

    fun isHubFav(f: Fav, theme: HubTheme): Boolean {
        if (f.hub == (if (theme == HubTheme.KIDS) "kids" else "music")) return true
        val kind = when (f.kind) {
            Kind.LIVE.key -> Kind.LIVE
            Kind.SERIES.key -> Kind.SERIES
            else -> Kind.VOD
        }
        val catName = when (kind) {
            Kind.LIVE -> liveCatNames[f.cat]
            Kind.SERIES -> seriesCatNames[f.cat]
            Kind.VOD -> vodCatNames[f.cat]
        } ?: ""
        return (catName.isNotEmpty() && HubMatcher.matchesCategory(theme, catName)) ||
            HubMatcher.matchesEntryTitle(theme, kind, f.name)
    }

    val kidsLiveFavs = remember(favs, cv) {
        favs.filter { it.kind == Kind.LIVE.key && isHubFav(it, HubTheme.KIDS) }
            .sortedBy { normalize(cleanChannelName(it.name)) }
    }
    val kidsVodFavs = remember(favs, cv) {
        favs.filter { it.kind != Kind.LIVE.key && isHubFav(it, HubTheme.KIDS) }
            .sortedBy { normalize(cleanTitle(it.name)) }
    }
    val musicLiveFavs = remember(favs, cv) {
        favs.filter { it.kind == Kind.LIVE.key && isHubFav(it, HubTheme.MUSIC) }
            .sortedBy { normalize(cleanChannelName(it.name)) }
    }
    val musicVodFavs = remember(favs, cv) {
        favs.filter { it.kind != Kind.LIVE.key && isHubFav(it, HubTheme.MUSIC) }
            .sortedBy { normalize(cleanTitle(it.name)) }
    }
    val generalLiveFavs = remember(favs, cv) {
        favs.filter { it.kind == Kind.LIVE.key }
            .sortedBy { normalize(cleanChannelName(it.name)) }
    }
    val movieFavs = remember(favs) {
        favs.filter { it.kind == Kind.VOD.key }
            .sortedBy { normalize(cleanTitle(it.name)) }
    }
    val seriesFavs = remember(favs) {
        favs.filter { it.kind == Kind.SERIES.key }
            .sortedBy { normalize(cleanTitle(it.name)) }
    }

    var filter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("all") }
    var focusedLive by remember { mutableStateOf<Item?>(null) }
    val theme = navThemeOf(NavIconType.FAVORITES)

    Box(
        Modifier
            .fillMaxSize()
            .cachedDraw {
                drawRect(
                    Brush.radialGradient(
                        listOf(theme.c1.copy(alpha = 0.24f), Color.Transparent),
                        center = Offset(size.width * 0.78f, size.height * 0.14f),
                        radius = size.width * 0.48f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(theme.c2.copy(alpha = 0.18f), Color.Transparent),
                        center = Offset(size.width * 0.24f, size.height * 0.20f),
                        radius = size.width * 0.38f
                    )
                )
            }
    ) {
        Column(Modifier.fillMaxSize().padding(top = 22.dp)) {
            PageHeader(
                NavIconType.FAVORITES, stringResource(R.string.favorites), stringResource(R.string.favs_subtitle),
                modifier = Modifier.padding(horizontal = 28.dp)
            )
            Spacer(Modifier.height(12.dp))

            if (favs.isEmpty()) {
                Text(
                    stringResource(R.string.favs_empty),
                    color = SX.Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)
                )
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 4.dp)
                ) {
                    item(key = "all") {
                        SelectChip(stringResource(R.string.facet_all), filter == "all") { filter = "all" }
                    }
                    if (generalLiveFavs.isNotEmpty()) {
                        item(key = "live") {
                            SelectChip(stringResource(R.string.favs_live) + " (${generalLiveFavs.size})", filter == "live") { filter = "live" }
                        }
                    }
                    val kidsTotal = kidsLiveFavs.size + kidsVodFavs.size
                    if (kidsTotal > 0) {
                        item(key = "kids") {
                            SelectChip(stringResource(R.string.nav_kids) + " ($kidsTotal)", filter == "kids") { filter = "kids" }
                        }
                    }
                    val musicTotal = musicLiveFavs.size + musicVodFavs.size
                    if (musicTotal > 0) {
                        item(key = "music") {
                            SelectChip(stringResource(R.string.nav_music) + " ($musicTotal)", filter == "music") { filter = "music" }
                        }
                    }
                    if (movieFavs.isNotEmpty()) {
                        item(key = "movies") {
                            SelectChip(stringResource(R.string.favs_movies) + " (${movieFavs.size})", filter == "movies") { filter = "movies" }
                        }
                    }
                    if (seriesFavs.isNotEmpty()) {
                        item(key = "series") {
                            SelectChip(stringResource(R.string.favs_series) + " (${seriesFavs.size})", filter == "series") { filter = "series" }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))

                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 28.dp, bottom = 16.dp)
                ) {
                    if ((filter == "all" || filter == "live") && generalLiveFavs.isNotEmpty()) {
                        item(key = "row_live") {
                            FavShelf(stringResource(R.string.favs_live), generalLiveFavs, Kind.LIVE, account, { focusedLive = it }, onDetail)
                        }
                    }
                    if ((filter == "all" || filter == "kids") && kidsLiveFavs.isNotEmpty()) {
                        item(key = "row_kids_live") {
                            FavShelf(stringResource(R.string.favs_kids_live), kidsLiveFavs, Kind.LIVE, account, { focusedLive = it }, onDetail)
                        }
                    }
                    if ((filter == "all" || filter == "kids") && kidsVodFavs.isNotEmpty()) {
                        item(key = "row_kids_vod") {
                            FavShelf("Films & Séries favoris · Enfants", kidsVodFavs, Kind.VOD, account, null, onDetail)
                        }
                    }
                    if ((filter == "all" || filter == "music") && musicLiveFavs.isNotEmpty()) {
                        item(key = "row_music_live") {
                            FavShelf(stringResource(R.string.favs_music_live), musicLiveFavs, Kind.LIVE, account, { focusedLive = it }, onDetail)
                        }
                    }
                    if ((filter == "all" || filter == "music") && musicVodFavs.isNotEmpty()) {
                        item(key = "row_music_vod") {
                            FavShelf("Concerts & Titres favoris · Musique", musicVodFavs, Kind.VOD, account, null, onDetail)
                        }
                    }
                    if ((filter == "all" || filter == "movies") && movieFavs.isNotEmpty()) {
                        item(key = "row_movies") {
                            FavShelf(stringResource(R.string.favs_movies), movieFavs, Kind.VOD, account, null, onDetail)
                        }
                    }
                    if ((filter == "all" || filter == "series") && seriesFavs.isNotEmpty()) {
                        item(key = "row_series") {
                            FavShelf(stringResource(R.string.favs_series), seriesFavs, Kind.SERIES, account, null, onDetail)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavShelf(
    title: String,
    list: List<Fav>,
    kind: Kind,
    account: Account,
    onFocusLive: ((Item) -> Unit)?,
    onDetail: OpenDetail
) {
    if (list.isEmpty()) return
    val ctx = LocalContext.current
    val landscape = kind == Kind.LIVE
    Text(
        title,
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 2.dp)
    )
    LazyRow(
        Modifier.height(if (landscape) 112.dp else 192.dp).bleedStart(),
        horizontalArrangement = Arrangement.spacedBy(if (landscape) 11.dp else 10.dp),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 7.dp)
    ) {
        items(list, key = { it.kind + ":" + it.id }) { f ->
            val item = f.toItem()
            val itemKind = when (f.kind) {
                Kind.LIVE.key -> Kind.LIVE
                Kind.SERIES.key -> Kind.SERIES
                else -> Kind.VOD
            }
            if (landscape) {
                LiveChannelCard(
                    item = item,
                    hubTag = f.hub,
                    onFocus = if (onFocusLive != null) ({ onFocusLive(item) }) else null,
                    onClick = { openItem(ctx, account, itemKind, item, onDetail) }
                )
            } else {
                ItemCard(item, false) { openItem(ctx, account, itemKind, item, onDetail) }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}
