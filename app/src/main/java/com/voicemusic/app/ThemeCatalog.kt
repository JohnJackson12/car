package com.voicemusic.app

import android.graphics.Color

/**
 * Port of theme.py's palettes. Unlike the Tk app (which needs a restart to reskin, since it draws
 * on raw Canvas), Android's views restyle live, so switching themes here applies immediately.
 */
class Palette(val bg: Int, val bgRaised: Int, val card: Int, val cardRowAlt: Int, val border: Int,
              val accent: Int, val accentDim: Int, val text: Int, val subtext: Int, val faint: Int,
              val good: Int, val bad: Int)

object ThemeCatalog {
    private fun c(hex: String) = Color.parseColor(hex)

    val TABLE: LinkedHashMap<String, Palette> = linkedMapOf(
        "Mixtape" to Palette(c("#1a1614"), c("#221d19"), c("#26201b"), c("#2c2620"), c("#3a3128"),
            c("#e0a94b"), c("#8a6a35"), c("#f2e9db"), c("#a89a86"), c("#6f6355"), c("#7fb069"), c("#c96a4e")),
        "Midnight" to Palette(c("#0f1420"), c("#161d2e"), c("#1a2236"), c("#1f2a40"), c("#2c3850"),
            c("#5b9dd9"), c("#3a6690"), c("#e7edf7"), c("#93a2bd"), c("#5c6a85"), c("#6fbf8b"), c("#d9705f")),
        "Slate" to Palette(c("#17181a"), c("#1e2023"), c("#222528"), c("#282b2f"), c("#37393d"),
            c("#6fd6c4"), c("#3f8a7c"), c("#eceef0"), c("#9aa0a6"), c("#63686d"), c("#7fb069"), c("#d16a6a")),
        "Forest" to Palette(c("#121712"), c("#182018"), c("#1c261c"), c("#212d21"), c("#33422f"),
            c("#8fbf5c"), c("#5c7d3b"), c("#eaf2e4"), c("#a0b294"), c("#647459"), c("#7fb069"), c("#c96a4e")),
        "Daylight" to Palette(c("#f5f2ec"), c("#ffffff"), c("#ffffff"), c("#f0ece3"), c("#ddd5c6"),
            c("#b5772f"), c("#e0c89a"), c("#2a2420"), c("#6b6154"), c("#9c9184"), c("#3f8a4f"), c("#b8452c")),
        "Plain" to Palette(c("#ffffff"), c("#f2f2f2"), c("#ffffff"), c("#eeeeee"), c("#c8c8c8"),
            c("#0067c0"), c("#bfe0ff"), c("#000000"), c("#444444"), c("#888888"), c("#1a7f37"), c("#c0392b"))
    )
    val NAMES: List<String> get() = TABLE.keys.toList()
    const val NOWPLAYING_RED = "#ff3b30"
    const val APPROVED_BLUE = "#2f7dd6"

    private fun dim(argb: Int, factor: Double = 0.55): Int = Color.rgb(
        (Color.red(argb) * factor).toInt().coerceIn(0, 255),
        (Color.green(argb) * factor).toInt().coerceIn(0, 255),
        (Color.blue(argb) * factor).toInt().coerceIn(0, 255))

    fun resolve(name: String, accentHex: String?): Palette {
        val base = TABLE[name] ?: TABLE["Mixtape"]!!
        if (accentHex.isNullOrBlank()) return base
        return try {
            val accent = Color.parseColor(accentHex)
            Palette(base.bg, base.bgRaised, base.card, base.cardRowAlt, base.border, accent, dim(accent),
                base.text, base.subtext, base.faint, base.good, base.bad)
        } catch (e: Exception) { base }
    }
}
