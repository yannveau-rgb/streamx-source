package eu.streamx.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/** `name` = nom du profil, `ns` = espace où sont rangés son historique, ses favoris et ses catégories masquées. */
data class Account(val server: String, val user: String, val pass: String, val name: String = "", val ns: String = "") {
    val label: String get() = name.ifBlank { user }
    val isM3u: Boolean get() = pass.isEmpty() || server.startsWith("m3u://", ignoreCase = true) || server.contains(".m3u", ignoreCase = true) || server.contains("type=m3u", ignoreCase = true)
    val m3uUrl: String get() = if (server.startsWith("m3u://", ignoreCase = true)) server.substring(6) else server
}

data class AccountInfo(val expMillis: Long?, val status: String, val maxConnections: Int)

enum class LoginResult { OK, BAD_CREDENTIALS, NETWORK }

val LocalAccountInfo = compositionLocalOf<AccountInfo?> { null }

/** Extrait serveur, identifiant et mot de passe si l'adresse est un lien M3U fourni par un service Xtream. */
fun tryExtractXtream(rawUrl: String): Account? {
    try {
        val trimmed = rawUrl.trim()
        val uri = android.net.Uri.parse(trimmed)
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        val port = if (uri.port != -1) ":${uri.port}" else ""
        val server = "$scheme://$host$port"
        val user = uri.getQueryParameter("username")
        val pass = uri.getQueryParameter("password")
        if (!user.isNullOrBlank() && !pass.isNullOrBlank()) {
            return Account(server = server, user = user, pass = pass, name = user)
        }
        val segments = uri.pathSegments
        val plIdx = segments.indexOfFirst { it.equals("playlist", true) || it.equals("live", true) || it.equals("movie", true) || it.equals("series", true) }
        if (plIdx >= 0 && segments.size >= plIdx + 3) {
            val u = segments[plIdx + 1]
            val p = segments[plIdx + 2]
            if (u.isNotBlank() && p.isNotBlank() && !u.contains("=") && !p.contains("=")) {
                return Account(server = server, user = u, pass = p, name = u)
            }
        }
    } catch (_: Exception) {}
    return null
}

/** Profil actif : sert à ranger les données de chacun dans des fichiers séparés. */
object Profile {
    @Volatile var ns: String = ""
    fun prefs(base: String): String = if (ns.isEmpty()) base else base + "_" + ns
}

/** Plusieurs profils (abonnements ou personnes) sur la même télé, avec un profil actif. */
object AccountStore {
    private const val PREFS = "streamx_accounts"

    private fun readAll(c: Context): MutableList<Account> {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.contains("list")) {
            // Reprise de l'ancien format (un seul compte) : ses données restent dans l'espace d'origine ("").
            val old = c.getSharedPreferences("streamx_account", Context.MODE_PRIVATE)
            val s = old.getString("server", null)
            val u = old.getString("user", null)
            val w = old.getString("pass", null)
            val l = if (s != null && u != null && w != null) mutableListOf(Account(s, u, w, u, "")) else mutableListOf()
            write(c, l, 0)
            old.edit().clear().apply()
            return l
        }
        val arr = try { JSONArray(p.getString("list", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            val rawPass = o.optString("pass")
            val pass = if (rawPass.isEmpty()) "" else (SecretBox.decrypt(rawPass) ?: return@mapNotNull null)
            val server = SecretBox.decrypt(o.optString("server")) ?: return@mapNotNull null   // adresse M3U : chiffrée (elle contient souvent identifiant et mot de passe)
            Account(server, o.optString("user"), pass, o.optString("name"), o.optString("ns"))
        }.toMutableList().also { list ->
            // Ancien format (mot de passe en clair) : on le réécrit chiffré au premier chargement.
            val plain = (0 until arr.length()).any { i ->
                val o = arr.optJSONObject(i)
                val clearPass = o?.optString("pass")?.let { it.isNotEmpty() && !SecretBox.isEncrypted(it) } == true
                val clearM3u = o?.optString("server")?.let { it.startsWith("m3u://", ignoreCase = true) } == true
                clearPass || clearM3u
            }
            if (plain && list.isNotEmpty()) write(c, list, p.getInt("active", 0))
        }
    }

    private fun write(c: Context, l: List<Account>, active: Int) {
        val arr = JSONArray()
        l.forEach {
            val enc = if (it.pass.isEmpty()) "" else (SecretBox.encrypt(it.pass) ?: it.pass)
            val srv = if (it.isM3u) (SecretBox.encrypt(it.server) ?: it.server) else it.server
            arr.put(JSONObject().put("server", srv).put("user", it.user).put("pass", enc).put("name", it.name).put("ns", it.ns))
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("list", arr.toString()).putInt("active", active).apply()
    }

    fun all(c: Context): List<Account> = readAll(c)

    /** Profil actif (et sélection de son espace de données). */
    fun load(c: Context): Account? {
        val l = readAll(c)
        val i = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("active", 0)
        val a = l.getOrNull(i) ?: l.firstOrNull()
        Profile.ns = a?.ns ?: ""
        return a
    }

    /** Ajoute le profil (ou met à jour le même abonnement) et l'active. */
    fun save(c: Context, a: Account): Account {
        val l = readAll(c)
        val i = l.indexOfFirst { it.server == a.server && it.user == a.user }
        val saved = if (i >= 0) a.copy(name = l[i].name.ifBlank { a.label }, ns = l[i].ns)
        else a.copy(name = a.label, ns = java.lang.Long.toHexString(System.nanoTime()).takeLast(6))
        if (i >= 0) l[i] = saved else l.add(saved)
        write(c, l, l.indexOf(saved))
        Profile.ns = saved.ns
        return saved
    }

    fun setActive(c: Context, a: Account) {
        val l = readAll(c)
        val i = l.indexOfFirst { it.server == a.server && it.user == a.user }
        if (i >= 0) write(c, l, i)
        Profile.ns = a.ns
    }

    /** Retire un profil. Renvoie le profil à activer ensuite, s'il en reste. */
    fun remove(c: Context, a: Account): Account? {
        val l = readAll(c)
        l.removeAll { it.server == a.server && it.user == a.user }
        write(c, l, 0)
        val next = l.firstOrNull()
        Profile.ns = next?.ns ?: ""
        return next
    }
}
object XtreamApi {
    fun normalize(server: String): String {
        var s = server.trim().trimEnd('/')
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return s
    }

    internal fun url(a: Account, action: String? = null): String {
        val q = "username=" + URLEncoder.encode(a.user, "UTF-8") + "&password=" + URLEncoder.encode(a.pass, "UTF-8")
        return normalize(a.server) + "/player_api.php?" + q + (if (action != null) "&action=$action" else "")
    }

    internal fun open(u: String, readTimeoutMs: Int = 30000): HttpURLConnection {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = readTimeoutMs
        c.setRequestProperty("User-Agent", "StreamX/" + BuildConfig.VERSION_NAME)
        return c
    }

    /** Vérifie le compte. Renvoie les infos d'abonnement, ou la raison de l'échec. */
    suspend fun login(a: Account): Pair<LoginResult, AccountInfo?> = withContext(Dispatchers.IO) {
        if (a.isM3u) {
            return@withContext LoginResult.OK to AccountInfo(null, "Actif (Playlist M3U)", 1)
        }
        try {
            val text = open(url(a)).inputStream.bufferedReader().use { it.readText() }
            val ui = JSONObject(text).optJSONObject("user_info") ?: return@withContext LoginResult.BAD_CREDENTIALS to null
            if (ui.optInt("auth", 0) != 1) return@withContext LoginResult.BAD_CREDENTIALS to null
            // exp_date vide, « null » ou 0 = abonnement sans date de fin.
            val exp = ui.optString("exp_date", "").toLongOrNull()?.takeIf { it > 0 }?.let { it * 1000L }
            LoginResult.OK to AccountInfo(exp, ui.optString("status", ""), ui.optString("max_connections", "0").toIntOrNull() ?: 0)
        } catch (e: org.json.JSONException) {
            LoginResult.BAD_CREDENTIALS to null
        } catch (e: Exception) {
            LoginResult.NETWORK to null
        }
    }

    suspend fun categories(a: Account, action: String): List<Category>? = withContext(Dispatchers.IO) {
        try {
            val text = open(url(a, action), 60000).inputStream.bufferedReader().use { it.readText() }
            val arr = org.json.JSONArray(text)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Category(o.optString("category_id"), o.optString("category_name").trim())
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Les 60 premiers éléments d'une catégorie (affiche, nom, identifiant). */
    suspend fun items(a: Account, kind: Kind, categoryId: String): List<Item>? = withContext(Dispatchers.IO) {
        try {
            val action = when (kind) { Kind.LIVE -> "get_live_streams"; Kind.VOD -> "get_vod_streams"; Kind.SERIES -> "get_series" }
            val idKey = if (kind == Kind.SERIES) "series_id" else "stream_id"
            val imgKey = if (kind == Kind.SERIES) "cover" else "stream_icon"
            val text = open(url(a, action) + "&category_id=" + URLEncoder.encode(categoryId, "UTF-8"), 60000)
                .inputStream.bufferedReader().use { it.readText() }
            val arr = org.json.JSONArray(text)
            (0 until arr.length()).mapNotNull {
                val o = arr.optJSONObject(it) ?: return@mapNotNull null
                val id = o.optString(idKey)
                if (id.isBlank()) return@mapNotNull null
                val img = o.optString(imgKey).trim()
                Item(
                    id, o.optString("name").trim(), if (img.isBlank() || img == "null") null else img,
                    o.optString("container_extension").takeIf { it.isNotBlank() && it != "null" }, categoryId,
                    o.optString("rating").toFloatOrNull() ?: 0f,
                    kind
                )
            }.distinctBy { it.id }.take(60)
        } catch (e: Exception) {
            null
        }
    }
}

fun formatCount(n: Int?): String = if (n == null) "—" else NumberFormat.getIntegerInstance().format(n)

@Composable
fun StreamXApp() {
    val ctx = LocalContext.current
    var account by remember { mutableStateOf(AccountStore.load(ctx)) }
    var info by remember { mutableStateOf<AccountInfo?>(null) }
    var resumeTick by remember { mutableStateOf(0) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    var adding by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(AccountStore.all(ctx)) }
    // Plateformes (TMDB) : copie enregistrée, rafraîchie une fois par semaine, en tâche de fond.
    LaunchedEffect(Unit) { ProviderIndex.ensure(ctx) }
    // Logos propres des chaînes françaises (liste enregistrée, rafraîchie toutes les deux semaines).
    LaunchedEffect(Unit) { ChannelLogos.ensure(ctx) }
    LaunchedEffect(account) {
        val a = account ?: return@LaunchedEffect
        // Le catalogue (cache disque d'abord) n'attend plus la réponse du serveur : les deux démarrent ensemble.
        ImageHosts.provider = if (a.isM3u) null else XtreamApi.normalize(a.server).trimEnd('/')
        kotlinx.coroutines.coroutineScope {
            launch { info = XtreamApi.login(a).second }
            launch { Catalog.load(a, ctx.cacheDir) }
        }
    }
    // Changer de profil : les listes en mémoire sont celles de l'autre abonnement, on les vide.
    fun activate(acc: Account?) {
        ItemCache.clear()
        EpisodesCache.clear()
        info = null
        if (acc != null) AccountStore.setActive(ctx, acc) else Profile.ns = ""
        account = acc
        adding = false
        profiles = AccountStore.all(ctx)
    }
    val a = account
    if (a == null || adding) {
        LoginScreen(onCancel = if (a != null) ({ adding = false }) else null) { acc, i ->
            val saved = AccountStore.save(ctx, acc)
            ItemCache.clear()
            EpisodesCache.clear()
            info = i
            account = saved
            adding = false
            profiles = AccountStore.all(ctx)
        }
    } else {
        CompositionLocalProvider(LocalAccountInfo provides info, LocalResumeTick provides resumeTick) {
            androidx.compose.runtime.key(a.server, a.user) {
                if (ParentalStore.version >= 0 && ParentalStore.kidsMode(ctx)) KidsShell(a) else MainShell(
                    account = a, profiles = profiles,
                    onSwitch = { activate(it) }, onAddProfile = { adding = true }
                ) {
                    // Déconnexion = on retire ce profil et ses données ; s'il en reste un autre, on bascule dessus.
                    History.clear(ctx)
                    HiddenStore.clearAll(ctx)
                    Favorites.clear(ctx)
                    TubeStore.clearAll(ctx)
                    Catalog.wipe(ctx.cacheDir)
                    Catalog.clear()
                    EpisodesCache.clear()
                    val next = AccountStore.remove(ctx, a)
                    activate(next)
                }
            }
        }
    }
}
@Composable
fun LoginField(label: String, value: String, onChange: (String) -> Unit, password: Boolean = false, uri: Boolean = false) {
    Text(label, color = SX.Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
    GlassInputBox(
        value = value,
        onValueChange = onChange,
        shape = RoundedCornerShape(12.dp),
        password = password,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else if (uri) KeyboardType.Uri else KeyboardType.Ascii,
            imeAction = ImeAction.Next
        ),
        horizontalPadding = 12.dp,
        verticalPadding = 10.dp,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(10.dp))
}

@Composable
fun LoginScreen(onCancel: (() -> Unit)? = null, onSuccess: (Account, AccountInfo?) -> Unit) {
    if (onCancel != null) androidx.activity.compose.BackHandler(onBack = onCancel)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val savedLists = remember { M3uStore.findAllAcrossProfiles(ctx) }
    var tab by remember { mutableIntStateOf(if (savedLists.isNotEmpty()) 1 else 0) }

    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    var m3uName by remember { mutableStateOf(savedLists.firstOrNull()?.name ?: "") }
    var m3uUrl by remember { mutableStateOf(savedLists.firstOrNull()?.url ?: "") }

    var busy by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    Box(
        Modifier.fillMaxSize().cachedDraw(essential = true) {
            drawRect(Brush.verticalGradient(listOf(SX.BgTop, SX.BgBottom)))
            drawRect(Brush.radialGradient(listOf(Color(0x308B5CF6), Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width * 0.25f, size.height * 0.25f), radius = size.width * 0.55f))
            drawRect(Brush.radialGradient(listOf(Color(0x2206B6D4), Color.Transparent), center = androidx.compose.ui.geometry.Offset(size.width * 0.80f, size.height * 0.78f), radius = size.width * 0.48f))
        },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.width(420.dp).glass(RoundedCornerShape(20.dp), 0.9f).padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StreamXLogoIcon(Modifier.size(36.dp))
                Spacer(Modifier.width(10.dp))
                androidx.compose.foundation.Image(painterResource(R.drawable.wordmark), contentDescription = "StreamX", modifier = Modifier.height(26.dp))
            }
            Text("Connecte ton abonnement ou ta playlist", color = SX.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))

            // Deux onglets : Xtream ou Playlist M3U
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectChip("Abonnement Xtream", tab == 0) { tab = 0; errorText = null }
                SelectChip("Playlist M3U", tab == 1) { tab = 1; errorText = null }
            }

            if (tab == 0) {
                LoginField(stringResource(R.string.login_server), server, { server = it; errorText = null }, uri = true)
                LoginField(stringResource(R.string.login_user), user, { user = it; errorText = null })
                LoginField(stringResource(R.string.login_pass), pass, { pass = it; errorText = null }, password = true)

                val pill = RoundedCornerShape(50)
                Text(
                    if (busy) stringResource(R.string.logging_in) else stringResource(R.string.login_button),
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth()
                        .tvFocus(pill) {
                            if (!busy && server.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) {
                                busy = true
                                errorText = null
                                val acc = Account(XtreamApi.normalize(server), user.trim(), pass)
                                scope.launch {
                                    val (res, i) = XtreamApi.login(acc)
                                    busy = false
                                    if (res == LoginResult.OK) {
                                        onSuccess(acc, i)
                                    } else {
                                        errorText = if (res == LoginResult.NETWORK) "Serveur injoignable. Vérifie l'adresse et ta connexion."
                                        else "Identifiants refusés par le serveur."
                                    }
                                }
                            }
                        }
                        .clip(pill).background(Brush.horizontalGradient(listOf(SX.Violet, SX.VioletLight)))
                        .padding(vertical = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            } else {
                if (savedLists.isNotEmpty()) {
                    Text("Playlist enregistrée sur cette télé :", color = SX.VioletLight, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
                    savedLists.take(2).forEach { saved ->
                        val cardShape = RoundedCornerShape(12.dp)
                        Row(
                            Modifier.fillMaxWidth()
                                .tvFocus(cardShape) {
                                    if (!busy) {
                                        busy = true
                                        errorText = null
                                        scope.launch {
                                            val url = saved.url.trim()
                                            val extracted = tryExtractXtream(url)
                                            if (extracted != null) {
                                                val (res, i) = XtreamApi.login(extracted)
                                                if (res == LoginResult.OK) {
                                                    busy = false
                                                    onSuccess(extracted, i)
                                                    return@launch
                                                }
                                            }
                                            val acc = Account(
                                                server = "m3u://" + url.removePrefix("m3u://"),
                                                user = saved.name.ifBlank { "Playlist M3U" },
                                                pass = "",
                                                name = saved.name.ifBlank { "Playlist M3U" }
                                            )
                                            busy = false
                                            onSuccess(acc, AccountInfo(null, "Actif (Playlist M3U)", 1))
                                        }
                                    }
                                }
                                .glass(cardShape, 0.95f)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("▶", color = SX.VioletLight, fontSize = 13.sp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(saved.name.ifBlank { "Playlist M3U" }, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(runCatching { java.net.URI(saved.url).host }.getOrNull() ?: "playlist", color = SX.Muted, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)   // l'hôte seulement : l'adresse complète contient souvent des identifiants
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Ou saisir une autre adresse M3U :", color = SX.Muted, fontSize = 10.5.sp, modifier = Modifier.padding(bottom = 6.dp))
                }

                LoginField("Nom (facultatif)", m3uName, { m3uName = it; errorText = null })
                LoginField("Adresse URL de la playlist", m3uUrl, { m3uUrl = it; errorText = null }, uri = true)

                val pill = RoundedCornerShape(50)
                Text(
                    if (busy) "Chargement de la playlist..." else "Charger la playlist",
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth()
                        .tvFocus(pill) {
                            if (!busy && m3uUrl.isNotBlank()) {
                                busy = true
                                errorText = null
                                scope.launch {
                                    val url = m3uUrl.trim()
                                    val extracted = tryExtractXtream(url)
                                    if (extracted != null) {
                                        val (res, i) = XtreamApi.login(extracted)
                                        if (res == LoginResult.OK) {
                                            M3uStore.add(ctx, M3uList(m3uName.ifBlank { extracted.user }, url))
                                            busy = false
                                            onSuccess(extracted, i)
                                            return@launch
                                        }
                                    }
                                    val chs = parseM3u(url)
                                    if (chs != null && chs.isNotEmpty()) {
                                        val displayName = m3uName.trim().ifBlank { "Playlist M3U" }
                                        val acc = Account(
                                            server = "m3u://" + url.removePrefix("m3u://"),
                                            user = displayName,
                                            pass = "",
                                            name = displayName
                                        )
                                        M3uStore.add(ctx, M3uList(displayName, url))
                                        busy = false
                                        onSuccess(acc, AccountInfo(null, "Actif (Playlist M3U)", 1))
                                    } else {
                                        busy = false
                                        errorText = "Impossible de lire la playlist M3U. Vérifie l'adresse URL."
                                    }
                                }
                            }
                        }
                        .clip(pill).background(Brush.horizontalGradient(listOf(SX.Violet, SX.VioletLight)))
                        .padding(vertical = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            if (onCancel != null) {
                val pill = RoundedCornerShape(50)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.cancel), color = Color.White, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().tvFocus(pill, onClick = onCancel).glass(pill, 1.2f).padding(vertical = 8.dp)
                )
            }

            if (errorText != null) {
                Text(errorText!!, color = Color(0xFFFF6B7A), fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

/** Version courte pour la carte Abonnement de la barre latérale (« Jusqu'au 08/11/2026 »). */
@Composable
fun expiryShort(info: AccountInfo?): String {
    if (info == null) return stringResource(R.string.not_connected)
    if (info.status.contains("M3U", ignoreCase = true)) return "Playlist active"
    val exp = info.expMillis ?: return stringResource(R.string.unlimited)
    val date = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(exp))
    return stringResource(if (exp < System.currentTimeMillis()) R.string.expired_short else R.string.active_short, date)
}

@Composable
fun expiryText(info: AccountInfo?): String {
    if (info == null) return stringResource(R.string.not_connected)
    if (info.status.contains("M3U", ignoreCase = true)) return "Playlist M3U active"
    val exp = info.expMillis ?: return stringResource(R.string.unlimited)
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(exp))
    return stringResource(if (exp < System.currentTimeMillis()) R.string.expired_on else R.string.active_until, date)
}
