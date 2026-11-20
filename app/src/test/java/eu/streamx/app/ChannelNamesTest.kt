package eu.streamx.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Noms de chaînes réels de l'abonnement (relevés par la revue) : plus jamais de « s. », « port4. » ou « E KIDS ». */
class ChannelNamesTest {
    private fun ch(raw: String, expected: String) = assertEquals(raw, expected, cleanChannelName(raw))
    private fun cat(raw: String, expected: String) = assertEquals(raw, expected, cleanLiveCategoryName(raw))

    @Test fun canalFoot() { ch("FR:Canal+Foot.SD", "Canal+ Foot"); ch("FR:Canal+Foot.QHD", "Canal+ Foot") }
    @Test fun bein() { ch("FR:beINSport4.HD", "beIN Sports 4") }
    @Test fun chainesSimples() {
        ch("FR:Mangas.SD", "Mangas"); ch("FR:Disney.Channel.HD", "Disney Channel"); ch("FR:TF1.HD", "TF1")
        ch("FR:France2.FHD", "France 2"); ch("FR:Arte.HD", "Arte"); ch("FR:BFM.TV.HD", "BFM TV"); ch("FR:M6.4k", "M6")
    }
    @Test fun autresPrefixes() {
        ch("KID:Cartoon.Network", "Cartoon Network"); ch("UK: ITV1", "ITV1"); ch("DSTV: SuperSport Variety 1", "SuperSport Variety 1")
        ch("|FR| TF1 FHD HEVC", "TF1"); ch("FR - TF1 HD", "TF1"); ch("FR:Disney+Cinema", "Disney+ Cinema")
    }
    @Test fun categories() {
        cat("SPORTS FR", "SPORTS FR"); cat("FRANCE KIDS", "FRANCE KIDS"); cat("NO Category", "NO Category")
        cat("FR: SPORT", "SPORT"); cat("|FR| CHAINES TNT", "CHAINES TNT")
    }
}