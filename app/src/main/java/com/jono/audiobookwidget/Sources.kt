package com.jono.audiobookwidget

data class Source(val pkg: String, val label: String)

/** Every app the widgets follow. */
object Sources {
    val LIBBY = Source("com.overdrive.mobile.android.libby", "Libby")
    val AUDIBLE = Source("com.audible.application", "Audible")
    val YTMUSIC = Source("com.google.android.apps.youtube.music", "YouTube Music")
    val all = listOf(LIBBY, AUDIBLE, YTMUSIC)

    fun byPkg(pkg: String?): Source? = all.firstOrNull { it.pkg == pkg }
    fun isKnown(pkg: String?): Boolean = all.any { it.pkg == pkg }
}

/**
 * One media widget, two kinds on the home screen. They look and behave the same at every size;
 * what differs is the content (books vs songs, albums and mixes) and the full panel's controls.
 */
enum class Profile(val label: String, val sources: List<Source>) {
    BOOKS("Audiobooks", listOf(Sources.LIBBY, Sources.AUDIBLE)),
    MUSIC("Music", listOf(Sources.YTMUSIC));

    companion object {
        fun of(pkg: String?): Profile? = values().firstOrNull { p -> p.sources.any { it.pkg == pkg } }
    }
}
