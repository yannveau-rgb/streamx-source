package eu.streamx.app

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

sealed interface QrPayload {
    data class Xtream(val server: String, val user: String, val pass: String) : QrPayload
    data class M3u(val name: String, val url: String) : QrPayload
}

/** Trouve l'adresse IPv4 locale de la télé sur le réseau Wi-Fi / Ethernet. */
fun findLocalIpv4(): String? {
    val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return null
    val candidates = ArrayList<Pair<Int, String>>()
    for (ni in ifaces) {
        val ok = runCatching { ni.isUp && !ni.isLoopback && !ni.isVirtual }.getOrDefault(false)
        if (!ok) continue
        val n = ni.name.lowercase()
        if (n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("rmnet") || n.startsWith("dummy")) continue
        val ifaceScore = when {
            n.startsWith("wlan") || n.startsWith("wifi") -> 0
            n.startsWith("eth") || n.startsWith("en") -> 1
            else -> 2
        }
        for (addr in ni.inetAddresses) {
            if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                val ip = addr.hostAddress ?: continue
                val siteScore = if (addr.isSiteLocalAddress) 0 else 10
                candidates.add((siteScore + ifaceScore) to ip)
            }
        }
    }
    return candidates.minByOrNull { it.first }?.second
}

private val urlInTextRe = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
private val labelLineRe = Regex("""^\s*([^:=\n]{2,32})\s*[:=]\s*(.+?)\s*$""")

/**
 * Extrait les identifiants depuis un lien complet (`get.php?username=...&password=...`)
 * ou un message fournisseur multi-lignes.
 */
fun parseQrPaste(raw: String): QrPayload? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val urls = urlInTextRe.findAll(trimmed).map { it.value.trimEnd('.', ',', ';', ')') }.toList()
    for (u in urls) {
        tryExtractXtream(u)?.let { return QrPayload.Xtream(it.server, it.user, it.pass) }
    }

    var srv: String? = null
    var usr: String? = null
    var pwd: String? = null
    for (line in trimmed.lines()) {
        val m = labelLineRe.matchEntire(line) ?: continue
        val k = normalize(m.groupValues[1])
        val v = m.groupValues[2].trim().trim('"', '\'', '`')
        if (v.isEmpty()) continue
        when {
            srv == null && (k.contains("server") || k.contains("serveur") || k.contains("host") || k.contains("hote") || k.contains("url") || k.contains("adresse") || k.contains("dns")) -> srv = v
            usr == null && (k.contains("user") || k.contains("identifiant") || k.contains("login") || k.contains("compte") || k.contains("pseudo")) -> usr = v
            pwd == null && (k.contains("pass") || k.contains("mdp") || k.contains("mot de passe") || k.contains("cle")) -> pwd = v
        }
    }
    if (!srv.isNullOrBlank() && !usr.isNullOrBlank() && !pwd.isNullOrBlank()) {
        return QrPayload.Xtream(XtreamApi.normalize(srv), usr, pwd)
    }

    urls.firstOrNull()?.let { return QrPayload.M3u("", it) }
    return null
}

/**
 * Mini-serveur HTTP local éphémère pour recevoir les identifiants depuis un téléphone sur le même Wi-Fi.
 * Ne journalise jamais le jeton ni les identifiants reçus.
 */
class QrLoginServer(
    private val onReady: (String?) -> Unit,
    private val onReceived: (QrPayload) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val closed = AtomicBoolean(false)
    private val completed = AtomicBoolean(false)
    @Volatile private var server: ServerSocket? = null

    fun start() {
        Thread({
            val ip = findLocalIpv4()
            if (ip == null) {
                if (!closed.get()) main.post { onReady(null) }
                return@Thread
            }
            val ss = runCatching { ServerSocket(0) }.getOrNull()
            if (ss == null || closed.get()) {
                runCatching { ss?.close() }
                if (!closed.get()) main.post { onReady(null) }
                return@Thread
            }
            server = ss
            val url = "http://$ip:${ss.localPort}/$token"
            main.post { if (!closed.get()) onReady(url) }

            while (!closed.get() && !ss.isClosed) {
                val sock = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                Thread({ handleClient(sock) }, "StreamX-QR-Client").apply { isDaemon = true }.start()
            }
        }, "StreamX-QR-Server").apply { isDaemon = true }.start()
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { server?.close() }
        }
    }

    private fun handleClient(sock: Socket) {
        sock.use { s ->
            runCatching {
                s.soTimeout = 10_000
                val req = readHttpRequest(s.getInputStream()) ?: return
                val path = req.target.substringBefore('?')
                if (path != "/$token") {
                    sendResponse(s, "404 Not Found", "text/plain; charset=utf-8", "Not found")
                    return
                }
                when (req.method.uppercase(Locale.ROOT)) {
                    "GET", "HEAD" -> sendResponse(s, "200 OK", "text/html; charset=utf-8", qrFormHtml(token), headOnly = req.method.equals("HEAD", true))
                    "POST" -> {
                        val params = parseFormUrlEncoded(req.body)
                        val payload = resolvePayload(params)
                        if (payload == null) {
                            sendResponse(s, "400 Bad Request", "text/html; charset=utf-8", qrErrorHtml(token))
                        } else if (completed.compareAndSet(false, true)) {
                            sendResponse(s, "200 OK", "text/html; charset=utf-8", qrSuccessHtml())
                            close()
                            main.post { onReceived(payload) }
                        } else {
                            sendResponse(s, "200 OK", "text/html; charset=utf-8", qrSuccessHtml())
                        }
                    }
                    else -> sendResponse(s, "405 Method Not Allowed", "text/plain; charset=utf-8", "Method not allowed")
                }
            }
        }
    }

    private fun resolvePayload(params: Map<String, String>): QrPayload? {
        val mode = params["mode"].orEmpty()
        return when (mode) {
            "paste" -> parseQrPaste(params["paste"].orEmpty())
            "m3u" -> {
                val url = params["m3u_url"].orEmpty().trim()
                if (url.isEmpty()) null else QrPayload.M3u(params["m3u_name"].orEmpty().trim(), url)
            }
            else -> {
                val srv = params["server"].orEmpty().trim()
                val usr = params["user"].orEmpty().trim()
                val pwd = params["pass"].orEmpty()
                if (usr.isEmpty() && pwd.isEmpty()) {
                    tryExtractXtream(srv)?.let { return QrPayload.Xtream(it.server, it.user, it.pass) }
                }
                if (srv.isEmpty() || usr.isEmpty() || pwd.isEmpty()) null
                else QrPayload.Xtream(XtreamApi.normalize(srv), usr, pwd)
            }
        }
    }

    private class HttpReq(val method: String, val target: String, val body: String)

    private fun readHttpRequest(input: InputStream): HttpReq? {
        val buf = ByteArray(1024)
        val raw = ByteArrayOutputStream()
        var headerEnd = -1
        while (raw.size() < 16 * 1024) {
            val n = input.read(buf)
            if (n <= 0) break
            raw.write(buf, 0, n)
            val bytes = raw.toByteArray()
            headerEnd = indexOfDoubleCrlf(bytes)
            if (headerEnd >= 0) break
        }
        if (headerEnd < 0) return null
        val all = raw.toByteArray()
        val headerStr = String(all, 0, headerEnd, Charsets.ISO_8859_1)
        val lines = headerStr.split("\r\n", "\n")
        val first = lines.firstOrNull()?.split(' ') ?: return null
        if (first.size < 2) return null
        val method = first[0]
        val target = first[1]

        var contentLength = 0
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0 && lines[i].substring(0, idx).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = lines[i].substring(idx + 1).trim().toIntOrNull()?.coerceIn(0, 64 * 1024) ?: 0
            }
        }

        val sepLen = if (headerEnd + 3 < all.size && all[headerEnd] == '\r'.code.toByte()) 4 else 2
        val bodyStart = headerEnd + sepLen
        val bodyOut = ByteArrayOutputStream(contentLength)
        if (bodyStart < all.size) {
            val already = (all.size - bodyStart).coerceAtMost(contentLength)
            bodyOut.write(all, bodyStart, already)
        }
        while (bodyOut.size() < contentLength) {
            val need = (contentLength - bodyOut.size()).coerceAtMost(buf.size)
            val n = input.read(buf, 0, need)
            if (n <= 0) break
            bodyOut.write(buf, 0, n)
        }
        return HttpReq(method, target, bodyOut.toString(Charsets.UTF_8.name()))
    }

    private fun indexOfDoubleCrlf(b: ByteArray): Int {
        for (i in 0..b.size - 4) {
            if (b[i] == 13.toByte() && b[i + 1] == 10.toByte() && b[i + 2] == 13.toByte() && b[i + 3] == 10.toByte()) return i
        }
        for (i in 0..b.size - 2) {
            if (b[i] == 10.toByte() && b[i + 1] == 10.toByte()) return i
        }
        return -1
    }

    private fun parseFormUrlEncoded(body: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (pair in body.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = if (eq >= 0) pair.substring(0, eq) else pair
            val v = if (eq >= 0) pair.substring(eq + 1) else ""
            runCatching {
                out[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
            }
        }
        return out
    }

    private fun sendResponse(sock: Socket, status: String, contentType: String, body: String, headOnly: Boolean = false) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        val out = sock.getOutputStream()
        out.write(headers)
        if (!headOnly) out.write(bytes)
        out.flush()
    }
}

/** Génère et dessine un QR code net sur fond blanc arrondi avec marge calme. */
@Composable
fun QrCodeImage(content: String, size: Dp = 156.dp, modifier: Modifier = Modifier) {
    val matrix = remember(content) {
        runCatching<BitMatrix> {
            QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(
                    EncodeHintType.MARGIN to 0,
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
                )
            )
        }.getOrNull()
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(10.dp)
    ) {
        if (matrix != null) {
            Canvas(Modifier.size(size - 20.dp)) {
                val cols = matrix.width
                val rows = matrix.height
                if (cols <= 0 || rows <= 0) return@Canvas
                val cw = this.size.width / cols
                val ch = this.size.height / rows
                val drawW = ceil(cw.toDouble()).toFloat()
                val drawH = ceil(ch.toDouble()).toFloat()
                val ink = Color(0xFF0B0D19)
                for (y in 0 until rows) {
                    for (x in 0 until cols) {
                        if (matrix.get(x, y)) {
                            drawRect(
                                color = ink,
                                topLeft = Offset(x * cw, y * ch),
                                size = Size(drawW, drawH)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun initialLang(): String = if (Locale.getDefault().language.equals("fr", ignoreCase = true)) "fr" else "en"

private fun qrPageShell(bodyHtml: String): String {
    val lang = initialLang()
    return """<!doctype html>
<html lang="$lang">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>StreamX</title>
<style>
  :root{--bg:#0b0d19;--card:#14172b;--line:rgba(255,255,255,.14);--text:#f5f6fa;--muted:#a6acc8;--acc:#7b52ff;--acc2:#3de0c5;}
  *{box-sizing:border-box}
  body{margin:0;min-height:100vh;background:radial-gradient(circle at 18% 12%,rgba(123,82,255,.26),transparent 45%),radial-gradient(circle at 85% 88%,rgba(61,224,197,.18),transparent 45%),var(--bg);color:var(--text);font:16px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;padding:20px 14px 36px;}
  main{max-width:460px;margin:0 auto;}
  .top{display:flex;align-items:center;justify-content:space-between;margin-bottom:14px;}
  .brand{font-weight:800;font-size:20px;letter-spacing:-.2px;}
  .lang button{background:transparent;color:var(--muted);border:1px solid var(--line);border-radius:999px;padding:5px 11px;font:600 13px inherit;cursor:pointer;}
  .lang button[aria-pressed="true"]{color:#fff;border-color:var(--acc2);}
  .card{background:rgba(20,23,43,.92);border:1px solid var(--line);border-radius:18px;padding:18px;}
  h1{margin:0 0 6px;font-size:20px;}
  p.sub{margin:0 0 16px;color:var(--muted);font-size:14px;}
  .tabs{display:flex;gap:8px;margin-bottom:16px;flex-wrap:wrap;}
  .tab{flex:1;min-width:95px;padding:9px 10px;border-radius:999px;border:1px solid var(--line);background:rgba(255,255,255,.05);color:var(--muted);font:600 13.5px inherit;cursor:pointer;text-align:center;}
  .tab.on{background:rgba(61,224,197,.16);border-color:var(--acc2);color:#fff;}
  label{display:block;color:var(--muted);font-size:12.5px;margin:0 0 5px;}
  input,textarea{width:100%;background:rgba(255,255,255,.07);border:1px solid var(--line);border-radius:12px;color:#fff;padding:11px 12px;font:15px inherit;margin:0 0 12px;outline:none;}
  input:focus,textarea:focus{border-color:var(--acc2);}
  textarea{min-height:110px;resize:vertical;}
  .btn{width:100%;border:0;border-radius:999px;padding:13px 18px;background:linear-gradient(135deg,#7b52ff,#3de0c5);color:#0b0d19;font:700 15.5px inherit;cursor:pointer;margin-top:4px;}
  .pane{display:none;} .pane.on{display:block;}
  .note{margin-top:14px;color:var(--muted);font-size:12.5px;text-align:center;}
  [data-l]{display:none;} html[lang="fr"] [data-l="fr"],html[lang="en"] [data-l="en"]{display:revert;}
</style>
</head>
<body>
<main>
  <div class="top">
    <div class="brand">StreamX</div>
    <div class="lang"><button type="button" id="bfr" aria-pressed="true">FR</button> <button type="button" id="ben" aria-pressed="false">EN</button></div>
  </div>
  <div class="card">
$bodyHtml
  </div>
  <p class="note"><span data-l="fr">Connexion locale directe sur ton Wi-Fi. Rien ne sort de chez toi.</span><span data-l="en">Direct local Wi-Fi connection. Nothing leaves your home network.</span></p>
</main>
<script>
(function(){
  var fr=document.getElementById("bfr"),en=document.getElementById("ben");
  function setL(l){document.documentElement.lang=l;fr.setAttribute("aria-pressed",l==="fr");en.setAttribute("aria-pressed",l==="en");}
  fr.onclick=function(){setL("fr");}; en.onclick=function(){setL("en");};
  setL(document.documentElement.lang||"fr");
})();
</script>
</body>
</html>"""
}

private fun qrFormHtml(token: String): String = qrPageShell(
    """    <h1><span data-l="fr">Envoyer tes identifiants à la télé</span><span data-l="en">Send your details to the TV</span></h1>
    <p class="sub"><span data-l="fr">Colle le lien ou le message de ton fournisseur, ou remplis les champs.</span><span data-l="en">Paste your provider link or message, or fill in the fields.</span></p>
    <div class="tabs">
      <button type="button" class="tab on" data-tab="paste"><span data-l="fr">Lien / Message</span><span data-l="en">Link / Message</span></button>
      <button type="button" class="tab" data-tab="xtream">Xtream</button>
      <button type="button" class="tab" data-tab="m3u">M3U</button>
    </div>
    <form method="post" action="/$token" id="f">
      <input type="hidden" name="mode" id="mode" value="paste">
      <div class="pane on" id="p-paste">
        <label for="paste"><span data-l="fr">Lien complet (get.php, M3U) ou message fournisseur</span><span data-l="en">Full link (get.php, M3U) or provider message</span></label>
        <textarea name="paste" id="paste" placeholder="http://exemple.com:8080/get.php?username=...&password=..."></textarea>
      </div>
      <div class="pane" id="p-xtream">
        <label for="srv"><span data-l="fr">Adresse du serveur</span><span data-l="en">Server address</span></label>
        <input type="url" name="server" id="srv" autocomplete="off" autocorrect="off" autocapitalize="none" placeholder="http://exemple.com:8080">
        <label for="usr"><span data-l="fr">Identifiant</span><span data-l="en">Username</span></label>
        <input type="text" name="user" id="usr" autocomplete="off" autocorrect="off" autocapitalize="none">
        <label for="pwd"><span data-l="fr">Mot de passe</span><span data-l="en">Password</span></label>
        <input type="password" name="pass" id="pwd" autocomplete="off">
      </div>
      <div class="pane" id="p-m3u">
        <label for="mn"><span data-l="fr">Nom (facultatif)</span><span data-l="en">Name (optional)</span></label>
        <input type="text" name="m3u_name" id="mn">
        <label for="mu"><span data-l="fr">Adresse de la playlist M3U</span><span data-l="en">M3U playlist URL</span></label>
        <input type="url" name="m3u_url" id="mu" autocomplete="off" autocorrect="off" autocapitalize="none" placeholder="http://...">
      </div>
      <button type="submit" class="btn"><span data-l="fr">Envoyer à la télé</span><span data-l="en">Send to TV</span></button>
    </form>
    <script>
    (function(){
      var tabs=document.querySelectorAll(".tab"),mode=document.getElementById("mode");
      tabs.forEach(function(b){
        b.onclick=function(){
          var t=b.getAttribute("data-tab"); mode.value=t;
          tabs.forEach(function(x){x.classList.toggle("on",x===b);});
          ["paste","xtream","m3u"].forEach(function(id){document.getElementById("p-"+id).classList.toggle("on",id===t);});
        };
      });
    })();
    </script>"""
)

private fun qrSuccessHtml(): String = qrPageShell(
    """    <h1><span data-l="fr">Envoyé à la télé ✓</span><span data-l="en">Sent to your TV ✓</span></h1>
    <p class="sub" style="margin-bottom:0"><span data-l="fr">La connexion démarre sur ta télé. Tu peux fermer cette page.</span><span data-l="en">Signing in on your TV now. You can close this page.</span></p>"""
)

private fun qrErrorHtml(token: String): String = qrPageShell(
    """    <h1><span data-l="fr">Champs incomplets</span><span data-l="en">Missing details</span></h1>
    <p class="sub"><span data-l="fr">Vérifie les informations saisies puis réessaie.</span><span data-l="en">Check the entered details and try again.</span></p>
    <a href="/$token" class="btn" style="display:block;text-align:center;text-decoration:none"><span data-l="fr">Retour</span><span data-l="en">Back</span></a>"""
)
