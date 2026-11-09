package app.hud01

/** Everything that can be shown in a mirror-mode tile. Add a new entry here (and in HudView.tileData) to add a tile. */
enum class TileType(val label: String, val jp: String) {
    GEAR("GEAR", "\u5909\u901f\u6bb5"),
    FUEL("FUEL", "\u71c3\u6599"),
    COOLANT("COOLANT", "\u51b7\u5374\u6c34\u6e29"),
    OIL("OIL", "\u6cb9\u6e29"),
    LOAD("LOAD", "\u8ca0\u8377"),
    MAP("MAP ABS", "\u5438\u6c17\u5727"),
    VOLTS("VOLTS", "\u96fb\u5727"),
}

/**
 * Pages of tiles for the mirror view. Speed is always fixed on the left; each page lists the tiles for
 * the rest: the first is the large tile, the others stack beside it. Tap a tile to swap it, swipe to change page.
 */
class TilePages {
    val pages = ArrayList<MutableList<TileType>>()
    var index = 0

    init {
        reset()
    }

    fun reset() {
        pages.clear()
        pages.add(mutableListOf(TileType.GEAR, TileType.FUEL))
        pages.add(mutableListOf(TileType.COOLANT, TileType.OIL, TileType.VOLTS))
        pages.add(mutableListOf(TileType.LOAD, TileType.MAP))
        index = 0
    }

    fun turn(delta: Int) {
        index = (index + delta + pages.size) % pages.size
    }

    /** Replace the tile in [slot] on the current page with the next type that is not already on that page. */
    fun swap(slot: Int) {
        val page = pages[index]
        if (slot !in page.indices) return
        val all = TileType.values()
        var t = page[slot]
        for (i in all.indices) {
            t = all[(t.ordinal + 1) % all.size]
            if (t !in page) {
                page[slot] = t
                return
            }
        }
    }

    fun serialize(): String =
        pages.joinToString("|") { p -> p.joinToString(",") { it.name } } + "#" + index

    fun restore(s: String) {
        val body = s.substringBefore('#')
        val parsed = body.split('|').map { part ->
            part.split(',').mapNotNull { n -> TileType.values().firstOrNull { it.name == n } }.distinct().toMutableList()
        }.filter { it.size in 1..4 }
        if (parsed.isEmpty()) {
            reset()
            return
        }
        pages.clear()
        pages.addAll(parsed)
        index = (s.substringAfter('#', "0").toIntOrNull() ?: 0).coerceIn(0, pages.size - 1)
    }
}
