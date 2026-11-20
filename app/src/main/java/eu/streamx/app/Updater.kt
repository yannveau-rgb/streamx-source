package eu.streamx.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Mises à jour hors Play Store.
 * Le fichier latest.json (voir BuildConfig.UPDATE_URL) contient :
 * { "versionCode": 3, "versionName": "0.3.0", "apkUrl": "https://.../streamx-0.3.0.apk",
 *   "sha256": "<empreinte hexadécimale de l'APK>", "notesFr": "...", "notesEn": "..." }
 * Android vérifie en plus que l'APK est signé avec la même clé que l'application installée.
 */
object Updater {
    data class Info(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val notesFr: String,
        val notesEn: String
    )

    private fun httpsOnly(url: String) = url.startsWith("https://")

    /** L'APK ne peut venir que de GitHub (même si le fichier latest.json était modifié, Android refuserait de toute façon une autre signature). */
    private fun trustedHost(url: String): Boolean = try {
        val h = URL(url).host.lowercase(Locale.ROOT)
        h == "github.com" || h.endsWith(".github.com") || h.endsWith(".githubusercontent.com")
    } catch (e: Exception) { false }

    suspend fun check(): Info? = withContext(Dispatchers.IO) {
        val url = BuildConfig.UPDATE_URL
        if (url.isBlank() || !httpsOnly(url)) return@withContext null
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            val text = c.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(text)
            val info = Info(
                j.getInt("versionCode"), j.getString("versionName"), j.getString("apkUrl"),
                j.getString("sha256").lowercase(Locale.ROOT), j.optString("notesFr"), j.optString("notesEn")
            )
            if (info.versionCode > BuildConfig.VERSION_CODE && httpsOnly(info.apkUrl) && trustedHost(info.apkUrl)) info else null
        } catch (e: Exception) {
            null
        }
    }

    /** Télécharge l'APK et vérifie son empreinte SHA-256. Renvoie null en cas d'échec. */
    suspend fun download(context: Context, info: Info, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "streamx-update.apk")
            val c = URL(info.apkUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 20000
            val total = c.contentLength.toLong()
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            c.inputStream.use { input ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
            val hash = md.digest().joinToString("") { "%02x".format(it) }
            if (hash == info.sha256) out else { out.delete(); null }
        } catch (e: Exception) {
            null
        }
    }

    /** Ouvre l'installeur d'Android. Renvoie false si l'autorisation « sources inconnues » manque (la page de réglage s'ouvre alors). */
    fun install(context: Context, apk: File): Boolean {
        // Sur certains appareils (Fire OS), la page de réglage ou l'installeur peut manquer : on ne plante pas, on renvoie false.
        try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return false
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return true
        } catch (e: Exception) {
            return false
        }
    }
}

