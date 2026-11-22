package eu.streamx.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private class Results(val movies: List<Entry>, val series: List<Entry>, val live: List<Entry>) {
    fun isEmpty() = movies.isEmpty() && series.isEmpty() && live.isEmpty()
}

internal fun search(kind: Kind, q: String, hidden: Set<String>, active: () -> Boolean): List<Entry> {
    val found = ArrayList<Entry>()
    for (e in Catalog.list(kind)) {
        if (!active()) return emptyList()
        if (e.cat in hidden) continue
        val n = e.name
        if (n.contains(q, ignoreCase = true) || (n.any { it.code > 127 } && normalize(n).contains(q))) {
            found.add(e)
            if (found.size >= 200) break
        }
    }
    return found.distinctBy { it.id }.sortedBy { if (normalize(cleanTitle(it.name)).startsWith(q)) 0 else 1 }.take(30)
}

@Composable
fun SearchScreen(
    account: Account,
    initial: String = "",
    onQueryChange: (String) -> Unit = {},
    onClose: () -> Unit,
    onDetail: OpenDetail
) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    var query by remember { mutableStateOf(initial) }
    val inputFocus = rememberSafeFocus()
    val firstResult = rememberSafeFocus()
    var hasFocusedOnce by remember { mutableStateOf(false) }
    var firstQueryRun by remember { mutableStateOf(true) }
    val cv = Catalog.version
    val hv = HiddenVersion.v
    val results by produceState<Results?>(null, query, cv, hv) {
        val q = normalize(query.trim())
        if (q.length < 2) {
            firstQueryRun = false
            value = null
        } else {
            if (!firstQueryRun) delay(250)
            firstQueryRun = false
            value = withContext(Dispatchers.Default) {
                val active = { isActive }
                Results(
                    search(Kind.VOD, q, HiddenStore.load(ctx, Kind.VOD), active),
                    search(Kind.SERIES, q, HiddenStore.load(ctx, Kind.SERIES), active),
                    search(Kind.LIVE, q, HiddenStore.load(ctx, Kind.LIVE), active)
                )
            }
        }
    }
    LaunchedEffect(Unit) {
        if (initial.isBlank()) {
            delay(40)
            inputFocus.tryFocus()
            hasFocusedOnce = true
        }
    }
    LaunchedEffect(results) {
        val r = results ?: return@LaunchedEffect
        if (!hasFocusedOnce && initial.isNotBlank()) {
            if (FocusMemory.restore != null) {
                hasFocusedOnce = true
                return@LaunchedEffect
            }
            if (r.isEmpty()) {
                delay(40)
                inputFocus.tryFocus()
                hasFocusedOnce = true
                return@LaunchedEffect
            }
            repeat(15) {
                delay(40)
                if (firstResult.tryFocus()) {
                    hasFocusedOnce = true
                    return@LaunchedEffect
                }
            }
            inputFocus.tryFocus()
            hasFocusedOnce = true
        }
    }
    Column(Modifier.fillMaxSize().tvPageEntry(if (initial.isBlank()) inputFocus else firstResult, inputFocus).verticalScroll(rememberScrollState()).padding(start = 28.dp, top = 24.dp)) {
        Text(stringResource(R.string.search_title), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        GlassInputBox(
            value = query,
            onValueChange = { query = it; onQueryChange(it) },
            hint = stringResource(R.string.search_hint),
            shape = RoundedCornerShape(50),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            fontSizeSp = 13f,
            horizontalPadding = 16.dp,
            verticalPadding = 11.dp,
            modifier = Modifier.padding(end = 28.dp).fillMaxWidth(),
            fieldModifier = Modifier.safeFocusTarget(inputFocus),
            onSubmit = { firstResult.tryFocus() }
        )
        Spacer(Modifier.height(12.dp))
        val r = results
        when {
            Catalog.list(Kind.VOD).isEmpty() && Catalog.loading ->
                Text(stringResource(R.string.catalog_loading), color = SX.Muted, fontSize = 12.sp)
            r == null -> Unit
            r.isEmpty() -> Text(stringResource(R.string.search_empty), color = SX.Muted, fontSize = 12.sp)
            else -> {
                var assignedFirst = false
                if (r.movies.isNotEmpty()) {
                    ResultRow(R.string.movies, r.movies, Kind.VOD, account, firstResult, !assignedFirst, onDetail)
                    assignedFirst = true
                }
                if (r.series.isNotEmpty()) {
                    ResultRow(R.string.series, r.series, Kind.SERIES, account, firstResult, !assignedFirst, onDetail)
                    assignedFirst = true
                }
                if (r.live.isNotEmpty()) {
                    ResultRow(R.string.live, r.live, Kind.LIVE, account, firstResult, !assignedFirst, onDetail)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ResultRow(
    title: Int,
    list: List<Entry>,
    kind: Kind,
    account: Account,
    firstFocus: SafeFocus,
    attachFirst: Boolean,
    onDetail: OpenDetail
) {
    if (list.isEmpty()) return
    val ctx = LocalContext.current
    Text(stringResource(title), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 2.dp))
    RowKey("search_${kind.key}") {
        LazyRow(
            Modifier.bleedStart(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 7.dp)
        ) {
            items(list, key = { it.id }) { e ->
                ItemCard(
                    e.toItem(),
                    kind == Kind.LIVE,
                    extraFocus = if (attachFirst && e.id == list.first().id) firstFocus else null
                ) { openItem(ctx, account, kind, e.toItem(), onDetail) }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}
