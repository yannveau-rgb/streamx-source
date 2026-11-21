package eu.streamx.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * « Univers » de films et de séries (Marvel, Star Wars, Disney, Pat'Patrouille…) reconnus par le titre.
 * Chaque univers devient une rangée (et une pastille) dès qu'il a assez de titres dans l'abonnement.
 */
object Franchises {
    class Universe(val key: String, val label: String, val rx: Regex)

    private fun u(key: String, label: String, rx: String) = Universe(key, label, Regex(rx))

    /** Films et séries : les grandes sagas. */
    val GENERAL = listOf(
        u("marvel", "Marvel", "(^|\\b)(marvel|avengers|iron man|captain america|thor|spider[\\s-]*man|black panther|guardians of the galaxy|gardiens de la galaxie|x[\\s-]*men|doctor strange|ant[\\s-]*man|venom|hulk|wolverine|eternals|shang[\\s-]*chi|fantastic four|4 fantastiques|daredevil|moon knight|loki|wandavision|hawkeye|she[\\s-]*hulk|ms marvel|agatha)(\\b|$)"),
        u("dc", "DC Comics", "(^|\\b)(batman|superman|wonder woman|aquaman|justice league|joker|the flash|shazam|suicide squad|green lantern|black adam|watchmen|peacemaker|the batman|titans|arrow|supergirl|harley quinn|blue beetle)(\\b|$)"),
        u("starwars", "Star Wars", "star wars|mandalorian|andor|ahsoka|obi[\\s-]*wan|clone wars|bad batch|skeleton crew|rogue one|solo a star"),
        u("potter", "Harry Potter", "harry potter|animaux fantastiques|fantastic beasts"),
        u("lotr", "Seigneur des anneaux", "seigneur des anneaux|lord of the rings|le hobbit|the hobbit|anneaux de pouvoir|rings of power"),
        u("bond", "James Bond", "james bond|(^|\\b)007(\\b|$)|casino royale|skyfall|spectre|mourir peut attendre|no time to die"),
        u("fast", "Fast & Furious", "fast (and|&|et) furious|fast x|fast five|hobbs"),
        u("mission", "Mission Impossible", "mission impossible|mission: impossible"),
        u("jurassic", "Jurassic Park", "jurassic"),
        u("got", "Game of Thrones", "game of thrones|house of the dragon|le trone de fer|knight of the seven"),
        u("trek", "Star Trek", "star trek"),
        u("matrix", "Matrix & Cie", "(^|\\b)matrix(\\b|$)|john wick|the continental"),
        u("alien", "Alien & Predator", "(^|\\b)alien(s)?(\\b|$)|predator|prometheus"),
        u("pirates", "Pirates des Caraïbes", "pirates des caraibes|pirates of the caribbean"),
        u("walking", "The Walking Dead", "walking dead|fear the walking|daryl dixon|dead city"),
        u("pixar", "Pixar", "toy story|le monde de nemo|finding nemo|dory|ratatouille|wall[\\s-]*e|coco|vice[\\s-]*versa|inside out|monstres (et|&) cie|monsters,? inc|indestructibles|incredibles|la[\\s-]*haut|(^|\\b)cars(\\b|$)|rebelle|(^|\\b)soul(\\b|$)|luca|elementaire|buzz l.eclair|lightyear|onward|en avant|1001 pattes|a bug s life"),
        u("disney", "Disney", "disney|reine des neiges|frozen|roi lion|lion king|vaiana|moana|encanto|raiponce|tangled|aladdin|mulan|hercule|tarzan|bambi|dumbo|peter pan|cendrillon|cinderella|la belle et la bete|beauty and the beast|la petite sirene|little mermaid|blanche neige|snow white|zootopie|zootopia|les 101 dalmatiens|le livre de la jungle|jungle book|mickey|stitch|bolt|wish|princesse et la grenouille"),
        u("dreamworks", "DreamWorks", "shrek|kung fu panda|madagascar|dragons|how to train your dragon|baby boss|boss baby|trolls|les croods|the croods|megamind|gabby|chat potte|puss in boots|l.age de glace|ice age|spirit|bad guys|les bad guys")
    )

    /** Univers Enfants : héros, studios et dessins animés. */
    val KIDS = listOf(
        u("k_disney", "Disney", GENERAL.first { it.key == "disney" }.rx.pattern),
        u("k_pixar", "Pixar", GENERAL.first { it.key == "pixar" }.rx.pattern),
        u("k_dreamworks", "DreamWorks", GENERAL.first { it.key == "dreamworks" }.rx.pattern),
        u("k_frozen", "La Reine des neiges", "reine des neiges|frozen|olaf"),
        u("k_paw", "La Pat'Patrouille", "pat[\\s']*patrouille|paw patrol"),
        u("k_pokemon", "Pokémon", "pokemon"),
        u("k_mario", "Super Mario", "super mario|mario bros|(^|\\b)mario(\\b|$)|sonic"),
        u("k_minions", "Minions & Moi, moche et méchant", "minions|moi moche|despicable me"),
        u("k_miraculous", "Miraculous", "miraculous|ladybug"),
        u("k_ghibli", "Studio Ghibli", "ghibli|totoro|chihiro|princesse mononoke|ponyo|kiki la petite sorciere|chateau ambulant|porco rosso|le vent se leve|arrietty|souvenirs de marnie|le garcon et le heron"),
        u("k_peppa", "Peppa Pig, Bluey & Cie", "peppa|bluey|baby shark|cocomelon|masha|oui oui|trotro|petit ours brun|tchoupi|caillou|t choupi|sam le pompier|mickey|dora"),
        u("k_bd", "Astérix, Tintin & Schtroumpfs", "asterix|tintin|lucky luke|schtroumpf|smurf|spirou|gaston|boule (et|&) bill|les aventures de tintin|titeuf"),
        u("k_heroes", "Super-héros", "spider[\\s-]*man|spidey|batman|superman|avengers|pj masks|les pyjamasques|teen titans|teen titan|power rangers|ben 10|miles morales|spider[\\s-]*verse"),
        u("k_animals", "Animaux & Nature", "zootopie|zootopia|madagascar|kung fu panda|le roi lion|lion king|le livre de la jungle|jungle book|bambi|dumbo|rio(\\b|$)|happy feet|l.age de glace|ice age|ferdinand|sing(\\b|$)|comme des betes|secret life of pets|les animaux|chats|chiens|dino|dinosaur"),
        u("k_princess", "Princesses", "princess|princesse|raiponce|tangled|cendrillon|cinderella|belle au bois|sleeping beauty|blanche neige|snow white|la petite sirene|little mermaid|belle et la bete|vaiana|moana|mulan|encanto|rebelle|barbie|sofia|elena")
    )

    /** Univers Musique : artistes et styles qui reviennent dans les concerts et documentaires. */
    val MUSIC = listOf(
        u("m_queen", "Queen", "queen|freddie mercury|bohemian"),
        u("m_mj", "Michael Jackson", "michael jackson"),
        u("m_johnny", "Johnny Hallyday", "johnny hallyday|(^|\\b)johnny(\\b|$)"),
        u("m_pink", "Pink Floyd & Rock", "pink floyd|rolling stones|led zeppelin|acdc|ac dc|metallica|u2|the who|nirvana|guns n|iron maiden|aerosmith|scorpions|kiss(\\b|$)|deep purple"),
        u("m_pop", "Pop & Variété", "taylor swift|beyonce|madonna|rihanna|lady gaga|adele|ed sheeran|coldplay|bruno mars|celine dion|mylene farmer|indochine|stromae|angele|aya nakamura|zaz|christophe mae|florent pagny|jean[\\s-]*jacques goldman|renaud|goldman"),
        u("m_rap", "Rap & Urbain", "rap|hip[\\s-]*hop|eminem|snoop|jay[\\s-]*z|kanye|drake|booba|ninho|pnl|jul(\\b|$)|nekfeu|soprano|sch(\\b|$)|damso|mc solaar|iam(\\b|$)|ntm"),
        u("m_elec", "Électro & Dance", "daft punk|david guetta|martin garrix|tomorrowland|ultra|calvin harris|avicii|swedish house|electro|techno|house music|dj "),
        u("m_classic", "Classique & Opéra", "opera|symphon|philharmon|concerto|mozart|beethoven|bach|chopin|verdi|puccini|tchaikovsk|vivaldi|ballet|requiem|orchestre")
    )

    /** Pour une liste d'entrées, les univers qui ont au moins `min` titres, du plus fourni au moins fourni. */
    fun rows(universes: List<Universe>, entries: List<Entry>, normalizedTitles: List<String>, min: Int = 4, max: Int = 60, exclusive: Boolean = false): List<Pair<PlatformInfo, List<Entry>>> {
        if (entries.size != normalizedTitles.size) return emptyList()
        val buckets = HashMap<String, ArrayList<Entry>>()
        for (i in entries.indices) {
            val e = entries[i]
            if (e.image == null) continue
            val n = normalizedTitles[i]
            for (uv in universes) {
                if (uv.rx.containsMatchIn(n)) buckets.getOrPut(uv.key) { ArrayList() }.add(e)
            }
        }
        val all = universes.mapNotNull { uv ->
            val hits = buckets[uv.key] ?: return@mapNotNull null
            val list = hits.sortedWith(compareByDescending<Entry> { if (it.rating > Catalog.MAX_TRUSTED_RATING) 0f else it.rating }.thenByDescending { it.added })
                .distinctBy { normalize(cleanTitle(it.name)) }.take(max)
            if (list.size >= min) PlatformInfo(uv.key, uv.label) to list else null
        }
        if (!exclusive) return all.sortedByDescending { it.second.size }
        // Un titre n'apparaît que dans une seule rangée : la plus ciblée (la moins fournie) le garde, les plus larges le perdent.
        val claimed = HashSet<String>()
        val kept = HashMap<String, List<Entry>>()
        for ((pf, list) in all.sortedBy { it.second.size }) {
            val mine = list.filter { claimed.add(normalize(cleanTitle(it.name))) }
            if (mine.size >= min) kept[pf.key] = mine
        }
        return all.mapNotNull { (pf, _) -> kept[pf.key]?.let { pf to it } }.sortedByDescending { it.second.size }
    }

    private val cache = HashMap<Pair<Kind, Int>, Pair<Set<String>, List<Pair<PlatformInfo, List<Entry>>>>>()

    @Synchronized
    fun cached(kind: Kind, hidden: Set<String>): List<Pair<PlatformInfo, List<Entry>>>? {
        val cv = Catalog.version
        return cache[kind to cv]?.takeIf { it.first == hidden }?.second
    }

    /** Films ou séries de l'abonnement (hors catégories masquées) rangés par univers. */
    fun rowsFor(kind: Kind, hidden: Set<String>): List<Pair<PlatformInfo, List<Entry>>> {
        cached(kind, hidden)?.let { return it }
        val cv = Catalog.version
        val entries = Catalog.list(kind)
        val norm = ProviderIndex.normalizedTitles(kind)
        if (entries.size != norm.size) return emptyList()
        val idx = entries.indices.filter { entries[it].cat !in hidden }
        val out = rows(GENERAL, idx.map { entries[it] }, idx.map { norm[it] })
        if (entries.isNotEmpty()) synchronized(this) { cache[kind to cv] = HashSet(hidden) to out }
        return out
    }

    /** Pré-calcule les univers Films & Séries en tâche de fond pour éviter tout décalage à l'ouverture. */
    suspend fun prewarm(ctx: Context) {
        withContext(Dispatchers.Default) {
            rowsFor(Kind.VOD, HiddenStore.load(ctx, Kind.VOD))
            rowsFor(Kind.SERIES, HiddenStore.load(ctx, Kind.SERIES))
        }
    }
}
