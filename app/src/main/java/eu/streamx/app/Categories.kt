package eu.streamx.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.Icon
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

data class Category(val id: String, val name: String)

enum class Kind(val action: String, val key: String) {
    LIVE("get_live_categories", "live"),
    VOD("get_vod_categories", "vod"),
    SERIES("get_series_categories", "series")
}

object HiddenStore {
    private const val PREFS = "streamx_hidden"
    private val cache = HashMap<String, Set<String>>()

    @Synchronized
    fun load(c: Context, k: Kind): Set<String> {
        val ck = Profile.ns + ":" + k.key
        cache[ck]?.let { return it }
        val loaded = c.getSharedPreferences(Profile.prefs(PREFS), Context.MODE_PRIVATE).getStringSet(k.key, emptySet())?.toSet() ?: emptySet()
        cache[ck] = loaded
        return loaded
    }

    @Synchronized
    fun save(c: Context, k: Kind, ids: Set<String>) {
        val copy = HashSet(ids)
        cache[Profile.ns + ":" + k.key] = copy
        c.getSharedPreferences(Profile.prefs(PREFS), Context.MODE_PRIVATE).edit().putStringSet(k.key, copy).apply()
        HiddenVersion.v++
    }

    @Synchronized
    fun clearAll(c: Context) {
        Kind.entries.forEach { cache.remove(Profile.ns + ":" + it.key) }
        c.getSharedPreferences(Profile.prefs(PREFS), Context.MODE_PRIVATE).edit().clear().apply()
        HiddenVersion.v++
    }
}

/** Groupes de catégories : un appui masque ou affiche d'un coup toutes les catégories du groupe. */
enum class CatGroup(val label: Int) {
    FR(R.string.grp_fr), EN(R.string.grp_en), UHD(R.string.grp_4k), SPORT(R.string.grp_sport),
    KIDS(R.string.grp_kids), MUSIC(R.string.grp_music), ADULT(R.string.grp_adult), OTHER(R.string.grp_other)
}

object CategoryRules {
    private val french = Regex(
        "(^|[^A-Z0-9])FR([^A-Z0-9]|$)|FRANCE|FRANCAIS|FRANÇAIS|FRENCH|VOSTFR|(^|[^A-Z])VF[FQI]?([^A-Z]|$)|MULTI",
        RegexOption.IGNORE_CASE
    )
    private val adult = Regex("ADULT|XXX|PORN|EROTI|(^|[^A-Z])SEX|\\+18|18\\+", RegexOption.IGNORE_CASE)
    private val english = Regex("(^|[^A-Z0-9])(EN|US|UK|USA)([^A-Z0-9]|$)|ENGLISH|BRITISH|AMERICAN", RegexOption.IGNORE_CASE)
    private val uhd = Regex("4K|UHD|2160|⁴ᴷ", RegexOption.IGNORE_CASE)
    private val sport = Regex("SPORT|FOOT|NBA|NFL|NHL|UFC|WWE|DAZN|BEIN|FORMULA|TENNIS|RUGBY|MOTOGP|BOXING|FIGHT", RegexOption.IGNORE_CASE)
    private val kids = Regex("KIDS|ENFANT|JEUNESSE|CARTOON|DISNEY|NICKEL|FAMILY|FAMILLE|JUNIOR|BABY", RegexOption.IGNORE_CASE)
    private val music = Regex("MUSIQUE|MUSIC|CONCERT|RADIO|MTV|NRJ|TRACE|MCM|MEZZO|STINGRAY|MELODY|OPERA|JAZZ|KARAOKE", RegexOption.IGNORE_CASE)

    fun isFrench(name: String) = french.containsMatchIn(name)

    // Marques d'une autre langue dans un nom de catégorie (arabe, turc, anglais, sous-titré…).
    private val foreignRx = Regex("(^|[^a-z0-9])(ar|arabic|arabe|arabes|turques?|turk|turkish|english|vost|sub|en|us|uk|ramadan|kurdistan)([^a-z0-9]|$)", RegexOption.IGNORE_CASE)
    // Chaîne dont le nom commence par « FR: », « |FR| » ou « FR - ».
    private val frPrefixRx = Regex("^\\s*(?:\\|?FR\\|?\\s*[:|-]|FR\\s+-)", RegexOption.IGNORE_CASE)

    /**
     * Catégories françaises, reconnues par leur NOM puis par leur CONTENU : une catégorie de chaînes dont la moitié
     * au moins porte le préfixe « FR: » est française (ex. MUSIC TV, CANAL+LIVE), même sans « FR » dans son nom.
     * Films et séries : française sauf marque d'une autre langue.
     */
    fun frenchIds(kind: Kind, list: List<Category>): Set<String> {
        val ratio: Map<String, Float>? = if (kind == Kind.LIVE) {
            val total = HashMap<String, Int>()
            val fr = HashMap<String, Int>()
            for (e in Catalog.list(Kind.LIVE)) {
                total[e.cat] = (total[e.cat] ?: 0) + 1
                if (frPrefixRx.containsMatchIn(e.name)) fr[e.cat] = (fr[e.cat] ?: 0) + 1
            }
            total.mapValues { (k, v) -> (fr[k] ?: 0).toFloat() / v }
        } else null
        return list.filter { c ->
            when {
                isFrench(c.name) -> true
                foreignRx.containsMatchIn(normalize(c.name)) -> false
                kind == Kind.LIVE -> (ratio?.get(c.id) ?: 0f) >= 0.5f
                else -> true
            }
        }.mapTo(HashSet()) { it.id }
    }
    fun isAdult(name: String) = adult.containsMatchIn(name)

    fun inGroup(name: String, g: CatGroup): Boolean = when (g) {
        CatGroup.FR -> isFrench(name)
        CatGroup.EN -> english.containsMatchIn(name)
        CatGroup.UHD -> uhd.containsMatchIn(name)
        CatGroup.SPORT -> sport.containsMatchIn(name)
        CatGroup.KIDS -> kids.containsMatchIn(name)
        CatGroup.MUSIC -> music.containsMatchIn(name)
        CatGroup.ADULT -> isAdult(name)
        CatGroup.OTHER -> !isFrench(name) && !english.containsMatchIn(name)
    }
}

@Composable
fun CategoriesScreen(kind: Kind, account: Account, title: Int, onDone: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val cv = Catalog.version
    var cats by remember(kind, account, cv) { mutableStateOf(Catalog.categoriesOf(kind)) }
    var failed by remember(kind, account) { mutableStateOf(false) }
    var hidden by remember(kind) { mutableStateOf(HiddenStore.load(ctx, kind)) }
    var query by remember(kind) { mutableStateOf("") }
    LaunchedEffect(kind, account) {
        if (cats == null) {
            failed = false
            val r = Catalog.ensureCategories(account, ctx.cacheDir, kind)
            if (r == null) failed = true else cats = r
        }
    }
    fun update(ids: Set<String>) {
        hidden = ids
        HiddenStore.save(ctx, kind, ids)
    }

    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 24.dp)) {
        val list = cats
        PageHeader(
            when (kind) { Kind.VOD -> NavIconType.MOVIES; Kind.SERIES -> NavIconType.SERIES; Kind.LIVE -> NavIconType.LIVE },
            stringResource(title),
            if (list != null) stringResource(R.string.cat_visible, list.count { it.id !in hidden }, list.size) else null
        )
        Spacer(Modifier.height(10.dp))
        if (list != null) {
            // Langue détectée aussi par le contenu (préfixe « FR: » des chaînes), pas seulement par le nom de la catégorie.
            val frSet = remember(list, kind, Catalog.version) { CategoryRules.frenchIds(kind, list) }
            val visible = list.count { it.id !in hidden }
            val firstFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                delay(40)
                runCatching { firstFocus.requestFocus() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                if (onDone != null) ActionChip(stringResource(R.string.cat_done), Modifier.focusRequester(firstFocus)) { onDone() }
                ActionChip(
                    stringResource(R.string.cat_fr_only),
                    if (onDone == null) Modifier.focusRequester(firstFocus) else Modifier
                ) {
                    update(list.filter { it.id !in frSet || CategoryRules.isAdult(it.name) }.map { it.id }.toSet())
                }
                ActionChip(stringResource(R.string.cat_hide_adult)) {
                    update(hidden + list.filter { CategoryRules.isAdult(it.name) }.map { it.id })
                }
                ActionChip(stringResource(R.string.cat_show_all)) { update(emptySet()) }
            }
            Spacer(Modifier.height(10.dp))
            val groups = remember(list, frSet) {
                CatGroup.entries.map { g ->
                    g to list.filter {
                        when (g) {
                            CatGroup.FR -> it.id in frSet
                            CatGroup.OTHER -> it.id !in frSet && CategoryRules.inGroup(it.name, CatGroup.OTHER)
                            else -> CategoryRules.inGroup(it.name, g)
                        }
                    }
                }.filter { it.second.isNotEmpty() }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 3.dp)) {
                items(groups, key = { it.first.name }) { (g, members) ->
                    val allVisible = members.all { it.id !in hidden }
                    SelectChip(stringResource(g.label) + " (" + members.size + ")", allVisible) {
                        val ids = members.map { it.id }.toSet()
                        if (allVisible) update(hidden + ids)
                        else update(hidden - (if (g == CatGroup.ADULT) ids else members.filter { !CategoryRules.isAdult(it.name) }.map { it.id }.toSet()))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val shown = remember(list, query) {
                if (query.isBlank()) list else {
                    val q = normalize(query.trim())
                    list.filter { normalize(it.name).contains(q) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                GlassInputBox(
                    value = query,
                    onValueChange = { query = it },
                    hint = stringResource(R.string.cat_search_hint),
                    shape = RoundedCornerShape(50),
                    fontSizeSp = 11.5f,
                    horizontalPadding = 16.dp,
                    verticalPadding = 8.dp,
                    modifier = Modifier.width(260.dp)
                )
                if (query.isNotBlank()) {
                    ActionChip(stringResource(R.string.cat_show_list)) { update(hidden - shown.map { it.id }.toSet()) }
                    ActionChip(stringResource(R.string.cat_hide_list)) { update(hidden + shown.map { it.id }) }
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
                items(shown, key = { it.id }) { c ->
                    val on = c.id !in hidden
                    CategoryRow(c.name, on) { update(if (on) hidden + c.id else hidden - c.id) }
                    Spacer(Modifier.height(6.dp))
                }
            }
        } else {
            Text(
                stringResource(if (failed) R.string.cat_error else R.string.cat_loading),
                color = SX.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
fun ActionChip(
    label: String, modifier: Modifier = Modifier, icon: ImageVector? = null,
    trailingIcon: ImageVector? = null, onClick: () -> Unit
) {
    val lowRam = LocalLowRamMode.current
    val pill = RoundedCornerShape(50)
    var focused by remember { mutableStateOf(false) }
    val pState = rememberFocusProgress(focused)
    val p = pState.value
    val content = lerp(Color(0xB8FFFFFF), SXFocus.On, p)
    Row(
        modifier.height(36.dp)
            .onFocusChanged { focused = it.isFocused }
            .tvFocus(pill, scale = 1.08f, decorate = false, onClick = onClick)
            .focusShadow({ pState.value }, pill, enabled = !lowRam)
            .clip(pill).background(lerp(Color.Transparent, SXFocus.Fill, p))
            .border(1.dp, SXFocus.RestStroke.copy(alpha = 0.15f * (1f - p)), pill)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { Icon(icon, null, tint = content, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(label, color = content, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        if (trailingIcon != null) { Spacer(Modifier.width(4.dp)); Icon(trailingIcon, null, tint = content, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
fun CategoryRow(name: String, on: Boolean, badge: String? = null, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(SX.RTile)
    var focused by remember { mutableStateOf(false) }
    val p = rememberFocusProgress(focused).value
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .tvFocus(shape, scale = 1.0f, decorate = false, onClick = onToggle)
            .clip(shape)
            .background(lerp(if (on) Color(0x14FFFFFF) else Color(0x08FFFFFF), SXFocus.Fill, p))
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name, color = lerp(if (on) Color.White else SX.Muted, SXFocus.On, p), fontSize = 13.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        if (badge != null) {
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(lerp(Color(0x1CFFFFFF), Color(0x1A0B0D19), p))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(badge, color = lerp(SX.Text2, SXFocus.On, p), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .width(34.dp)
                .height(20.dp)
                .clip(CircleShape)
                .background(if (on) lerp(SX.Turquoise, Color(0xFF0D9488), p) else lerp(Color(0x33FFFFFF), Color(0x380B0D19), p)),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(Modifier.padding(2.dp).size(16.dp).clip(CircleShape).background(Color.White))
        }
    }
}

private class HubCat(val kind: Kind, val cat: Category, val tagRes: Int)

/** Gestion des catégories d'un univers (Enfants ou Musique) : ne montre que les catégories de cet univers (Chaînes, Films, Séries). */
@Composable
fun HubCategoriesScreen(theme: HubTheme, account: Account, initialFilter: String = "all", onDone: () -> Unit) {
    val ctx = LocalContext.current
    val cv = Catalog.version
    var catsLive by remember(account, cv) { mutableStateOf(Catalog.categoriesOf(Kind.LIVE)) }
    var catsVod by remember(account, cv) { mutableStateOf(Catalog.categoriesOf(Kind.VOD)) }
    var catsSeries by remember(account, cv) { mutableStateOf(Catalog.categoriesOf(Kind.SERIES)) }
    var failed by remember(account) { mutableStateOf(false) }

    LaunchedEffect(account) {
        if (catsLive == null) catsLive = Catalog.ensureCategories(account, ctx.cacheDir, Kind.LIVE)
        if (catsVod == null) catsVod = Catalog.ensureCategories(account, ctx.cacheDir, Kind.VOD)
        if (catsSeries == null) catsSeries = Catalog.ensureCategories(account, ctx.cacheDir, Kind.SERIES)
        if (catsLive == null && catsVod == null && catsSeries == null) failed = true
    }

    var hiddenLive by remember(theme) { mutableStateOf(HiddenStore.load(ctx, Kind.LIVE)) }
    var hiddenVod by remember(theme) { mutableStateOf(HiddenStore.load(ctx, Kind.VOD)) }
    var hiddenSeries by remember(theme) { mutableStateOf(HiddenStore.load(ctx, Kind.SERIES)) }

    fun hiddenOf(k: Kind): Set<String> = when (k) {
        Kind.LIVE -> hiddenLive
        Kind.VOD -> hiddenVod
        Kind.SERIES -> hiddenSeries
    }

    fun setHidden(k: Kind, ids: Set<String>) {
        when (k) {
            Kind.LIVE -> hiddenLive = ids
            Kind.VOD -> hiddenVod = ids
            Kind.SERIES -> hiddenSeries = ids
        }
        HiddenStore.save(ctx, k, ids)
    }

    fun applyBulk(targets: List<HubCat>, show: (HubCat) -> Boolean) {
        for (k in Kind.entries) {
            val sub = targets.filter { it.kind == k }
            if (sub.isEmpty()) continue
            val next = hiddenOf(k).toMutableSet()
            for (item in sub) {
                if (show(item)) next.remove(item.cat.id) else next.add(item.cat.id)
            }
            setHidden(k, next)
        }
    }

    val liveItems = remember(catsLive, theme) {
        catsLive.orEmpty().filter { HubMatcher.categoryMode(theme, Kind.LIVE, it.name) > 0 }
            .map { HubCat(Kind.LIVE, it, R.string.badge_live) }
    }
    val vodItems = remember(catsVod, theme) {
        catsVod.orEmpty().filter { HubMatcher.categoryMode(theme, Kind.VOD, it.name) > 0 }
            .map { HubCat(Kind.VOD, it, R.string.badge_movie) }
    }
    val seriesItems = remember(catsSeries, theme) {
        catsSeries.orEmpty().filter { HubMatcher.categoryMode(theme, Kind.SERIES, it.name) > 0 }
            .map { HubCat(Kind.SERIES, it, R.string.badge_series) }
    }
    val allItems = remember(liveItems, vodItems, seriesItems) { liveItems + vodItems + seriesItems }

    var tab by remember(theme, initialFilter) {
        mutableStateOf(
            when (initialFilter) {
                "live" -> if (liveItems.isNotEmpty()) "live" else "all"
                "vod" -> if (vodItems.isNotEmpty()) "vod" else "all"
                "series" -> if (seriesItems.isNotEmpty()) "series" else "all"
                else -> "all"
            }
        )
    }
    var query by remember(theme) { mutableStateOf("") }

    val activeItems = when (tab) {
        "live" -> liveItems
        "vod" -> vodItems
        "series" -> seriesItems
        else -> allItems
    }
    val ready = catsLive != null || catsVod != null || catsSeries != null

    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 24.dp)) {
        PageHeader(
            if (theme == HubTheme.KIDS) NavIconType.KIDS else NavIconType.MUSIC,
            stringResource(if (theme == HubTheme.KIDS) R.string.nav_kids else R.string.nav_music),
            if (ready) stringResource(R.string.cat_visible, activeItems.count { it.cat.id !in hiddenOf(it.kind) }, activeItems.size) else null
        )
        Spacer(Modifier.height(10.dp))
        if (ready) {
            val frLive = remember(catsLive, cv) { catsLive?.let { CategoryRules.frenchIds(Kind.LIVE, it) } ?: emptySet() }
            val frVod = remember(catsVod, cv) { catsVod?.let { CategoryRules.frenchIds(Kind.VOD, it) } ?: emptySet() }
            val frSeries = remember(catsSeries, cv) { catsSeries?.let { CategoryRules.frenchIds(Kind.SERIES, it) } ?: emptySet() }
            val firstFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                delay(40)
                runCatching { firstFocus.requestFocus() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                ActionChip(stringResource(R.string.cat_done), Modifier.focusRequester(firstFocus)) { onDone() }
                if (activeItems.isNotEmpty()) {
                    ActionChip(stringResource(R.string.cat_fr_only)) {
                        applyBulk(activeItems) { item ->
                            val frSet = when (item.kind) { Kind.LIVE -> frLive; Kind.VOD -> frVod; Kind.SERIES -> frSeries }
                            item.cat.id in frSet
                        }
                    }
                    ActionChip(stringResource(R.string.cat_show_all)) {
                        applyBulk(activeItems) { true }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 3.dp)) {
                item(key = "all") {
                    SelectChip(stringResource(R.string.facet_all) + " (${allItems.size})", tab == "all") { tab = "all" }
                }
                if (liveItems.isNotEmpty()) {
                    item(key = "live") {
                        SelectChip(
                            stringResource(if (theme == HubTheme.KIDS) R.string.hub_kids_live else R.string.hub_music_live) + " (${liveItems.size})",
                            tab == "live"
                        ) { tab = "live" }
                    }
                }
                if (vodItems.isNotEmpty()) {
                    item(key = "vod") {
                        SelectChip(
                            stringResource(if (theme == HubTheme.KIDS) R.string.movies else R.string.hub_music_vod) + " (${vodItems.size})",
                            tab == "vod"
                        ) { tab = "vod" }
                    }
                }
                if (seriesItems.isNotEmpty()) {
                    item(key = "series") {
                        SelectChip(stringResource(R.string.series) + " (${seriesItems.size})", tab == "series") { tab = "series" }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val shown = remember(activeItems, query) {
                if (query.isBlank()) activeItems else {
                    val q = normalize(query.trim())
                    activeItems.filter { normalize(it.cat.name).contains(q) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                GlassInputBox(
                    value = query,
                    onValueChange = { query = it },
                    hint = stringResource(R.string.cat_search_hint),
                    shape = RoundedCornerShape(50),
                    fontSizeSp = 11.5f,
                    horizontalPadding = 16.dp,
                    verticalPadding = 8.dp,
                    modifier = Modifier.width(260.dp)
                )
                if (query.isNotBlank() && shown.isNotEmpty()) {
                    ActionChip(stringResource(R.string.cat_show_list)) { applyBulk(shown) { true } }
                    ActionChip(stringResource(R.string.cat_hide_list)) { applyBulk(shown) { false } }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (shown.isEmpty()) {
                Text(stringResource(R.string.search_empty), color = SX.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
                    items(shown, key = { it.kind.key + ":" + it.cat.id }) { item ->
                        val h = hiddenOf(item.kind)
                        val on = item.cat.id !in h
                        CategoryRow(
                            name = item.cat.name,
                            on = on,
                            badge = if (tab == "all") stringResource(item.tagRes) else null
                        ) {
                            setHidden(item.kind, if (on) h + item.cat.id else h - item.cat.id)
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        } else {
            Text(
                stringResource(if (failed) R.string.cat_error else R.string.cat_loading),
                color = SX.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}
