package eu.streamx.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Icônes de la barre latérale, dessinées pour StreamX : trait de 1,9 sur une grille de 24, bouts arrondis, et un aplat doux
 * du même blanc (14 % au repos, 32 % sur la rubrique active). Un seul dessin par rubrique : la couleur vient de `Icon(tint = …)`,
 * donc changer de couleur ne redessine rien. Les petites pastilles (têtes de notes, yeux, curseurs, triangle) sont pleines.
 */
object NavIcons {
    private class Shape(val soft: String?, val strokes: List<String>, val solids: List<String> = emptyList())

    private fun dot(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    private const val HOME = "M4 10.5 12 4l8 6.5V19a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 19z"
    private const val TV = "M3.5 7.5h17A1.5 1.5 0 0 1 22 9v9a1.5 1.5 0 0 1-1.5 1.5h-17A1.5 1.5 0 0 1 2 18V9a1.5 1.5 0 0 1 1.5-1.5z"
    private const val CLAP_BODY = "M3 10h18v9.5a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 19.5z"
    private const val CARD = "M3 9.5A1.5 1.5 0 0 1 4.5 8h15A1.5 1.5 0 0 1 21 9.5v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18.5z"
    private const val PILL = "M7.5 5h9A5 5 0 0 1 21.5 10v4a5 5 0 0 1-5 5h-9A5 5 0 0 1 2.5 14v-4A5 5 0 0 1 7.5 5z"
    private const val HEAD = "M12 6a7.5 7.5 0 1 1 0 15 7.5 7.5 0 0 1 0-15z"
    private const val HEART = "M12 20.5s-8.5-4.9-8.5-10.9A4.5 4.5 0 0 1 12 7a4.5 4.5 0 0 1 8.5 2.6c0 6-8.5 10.9-8.5 10.9z"

    private fun shape(t: NavIconType): Shape? = when (t) {
        NavIconType.HOME -> Shape(HOME, listOf(HOME, "M10 20.5V15h4v5.5"))
        NavIconType.LIVE -> Shape(TV, listOf(TV, "M8.5 3 12 7.5 15.5 3"))
        NavIconType.MOVIES -> Shape(CLAP_BODY, listOf(CLAP_BODY, "M2.6 7 19.2 2.6l.7 2.4L3.3 9.4z", "M8.4 5.5l1.6 2.3M13.8 4.1l1.6 2.3"))
        NavIconType.SERIES -> Shape(CARD, listOf(CARD, "M5 5h14", "M7.5 2.3h9"))
        NavIconType.KIDS -> Shape(
            HEAD, listOf(HEAD, "M5.73 9.38A2.6 2.6 0 1 1 8.81 6.7", "M18.27 9.38A2.6 2.6 0 1 0 15.19 6.7", "M9.5 16a3.2 3.2 0 0 0 5 0"),
            listOf(dot(9.3f, 12.5f, 1.2f), dot(14.7f, 12.5f, 1.2f))
        )
        NavIconType.MUSIC -> Shape(null, listOf("M9 18V5.5l11-2.5V16", "M9 9l11-2.5"), listOf(dot(6.5f, 18f, 2.5f), dot(17.5f, 16f, 2.5f)))
        NavIconType.TUBE -> Shape(PILL, listOf(PILL), listOf("M10 9.2v5.6l4.8-2.8z"))
        NavIconType.FAVORITES -> Shape(HEART, listOf(HEART))
        NavIconType.SETTINGS -> Shape(null, listOf("M4 6h16M4 12h16M4 18h16"), listOf(dot(9f, 6f, 2.3f), dot(15f, 12f, 2.3f), dot(7f, 18f, 2.3f)))
        else -> null
    }

    private val cache = HashMap<Pair<NavIconType, Boolean>, ImageVector?>()

    /** Le dessin de la rubrique ; `active` = aplat plus marqué. Renvoie null si la rubrique n'a pas d'icône dessinée. */
    @Synchronized
    fun get(type: NavIconType, active: Boolean): ImageVector? = cache.getOrPut(type to active) {
        val s = shape(type) ?: return@getOrPut null
        ImageVector.Builder("nav_$type", 24.dp, 24.dp, 24f, 24f).apply {
            if (s.soft != null) addPath(addPathNodes(s.soft), fill = SolidColor(Color.White), fillAlpha = if (active) 0.32f else 0.14f)
            for (d in s.strokes) addPath(
                addPathNodes(d), stroke = SolidColor(Color.White), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            )
            for (d in s.solids) addPath(addPathNodes(d), fill = SolidColor(Color.White))
        }.build()
    }
}
