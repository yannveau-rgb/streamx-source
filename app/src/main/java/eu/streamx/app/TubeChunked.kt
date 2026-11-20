package eu.streamx.app

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import kotlin.math.min

/**
 * Lecture « par morceaux » : au lieu d'une seule requête sans fin, on demande la vidéo par tranches de quelques Mo (en-tête Range).
 * Les serveurs vidéo de YouTube brident ou coupent les très longues requêtes ; NewPipe et yt-dlp procèdent de la même façon.
 */
@UnstableApi
class ChunkedDataSource(private val factory: DataSource.Factory, private val chunk: Long = CHUNK) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var inner: DataSource? = null
    private var spec: DataSpec? = null
    private var pos = 0L
    private var end = C.LENGTH_UNSET.toLong() // fin absolue (exclue) : connue dès la première réponse (Content-Range)
    private var curLen = 0L                   // taille demandée pour la tranche en cours
    private var chunkRead = 0L                // octets déjà lus dans la tranche en cours

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
        inner?.addTransferListener(transferListener)
    }

    private fun openChunk() {
        val s = spec ?: return
        val remaining = if (end != C.LENGTH_UNSET.toLong()) end - pos else Long.MAX_VALUE
        val len = min(chunk, remaining)
        // Un refus ponctuel du serveur (403, 5xx) ne doit pas arrêter la lecture : on réessaie la tranche, puis on laisse le lecteur décider.
        var d: DataSource
        var attempt = 0
        while (true) {
            d = factory.createDataSource()
            listeners.forEach { d.addTransferListener(it) }
            try {
                d.open(s.buildUpon().setPosition(pos).setLength(len).build())
                break
            } catch (e: IOException) {
                runCatching { d.close() } // toujours fermer : sinon les écouteurs de débit restent « en transfert »
                val retryable = e is HttpDataSource.InvalidResponseCodeException && (e.responseCode == 403 || e.responseCode in 500..599)
                if (!retryable || ++attempt >= 3) throw e
                try { Thread.sleep(400L * attempt) } catch (ie: InterruptedException) { Thread.currentThread().interrupt(); throw e }
            }
        }
        if (end == C.LENGTH_UNSET.toLong()) {
            // « bytes 0-2097151/12345678 » : le total après la barre oblique.
            val cr = d.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()
            val total = cr?.substringAfterLast('/')?.trim()?.toLongOrNull()
            if (total != null && total > 0) end = total
        }
        curLen = len
        chunkRead = 0L
        inner = d
    }

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        pos = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.position + dataSpec.length else C.LENGTH_UNSET.toLong()
        openChunk()
        return if (end != C.LENGTH_UNSET.toLong()) end - dataSpec.position else C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (end != C.LENGTH_UNSET.toLong() && pos >= end) return C.RESULT_END_OF_INPUT
        var n = inner?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        if (n == C.RESULT_END_OF_INPUT) {
            inner?.close()
            inner = null
            // Total connu : fini si on l'a atteint. Total inconnu : une tranche pleine signifie qu'il y a sûrement une suite ;
            // une tranche plus courte que demandée, c'est la fin du fichier.
            if (end != C.LENGTH_UNSET.toLong()) { if (pos >= end) return C.RESULT_END_OF_INPUT }
            else if (chunkRead < curLen) return C.RESULT_END_OF_INPUT
            try {
                openChunk()
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                if (e.responseCode == 416) return C.RESULT_END_OF_INPUT // demande au-delà de la fin du fichier
                throw e
            }
            n = inner?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        }
        if (n > 0) { pos += n; chunkRead += n }
        return n
    }

    override fun getUri(): Uri? = inner?.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = inner?.responseHeaders ?: emptyMap()

    override fun close() {
        try { inner?.close() } finally { inner = null; spec = null }
    }

    companion object {
        const val CHUNK = 2L * 1024 * 1024
    }
}
