package com.jono.audiobookwidget

data class Source(val pkg: String, val label: String)

/** The audiobook apps the widget follows. Add more here. */
object Sources {
    val LIBBY = Source("com.overdrive.mobile.android.libby", "Libby")
    val AUDIBLE = Source("com.audible.application", "Audible")
    val all = listOf(LIBBY, AUDIBLE)

    fun byPkg(pkg: String?): Source? = all.firstOrNull { it.pkg == pkg }
    fun isKnown(pkg: String?): Boolean = all.any { it.pkg == pkg }
    fun other(pkg: String): Source = if (pkg == LIBBY.pkg) AUDIBLE else LIBBY
}
