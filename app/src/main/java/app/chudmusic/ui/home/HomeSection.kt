package app.chudmusic.ui.home

/**
 * The shelves of Home that the user can hide (Settings › Home). The scan progress and the music
 * permission card are not in the list: they are needed for the app to work.
 */
enum class HomeSection {
    RECENT_PLAYED, RANDOM, PLAYLISTS, RECENT_ADDED, RADIO_FAVORITES, GETTING_STARTED;

    companion object {
        /**
         * The sections to show, given the names stored as hidden. A stored name that no longer
         * exists (a section renamed or removed in a later version) is ignored, never a crash, and
         * a section added later is shown until the user hides it.
         */
        fun shown(hidden: Set<String>): Set<HomeSection> = entries.filterTo(LinkedHashSet()) { it.name !in hidden }

        /**
         * The sections Home actually draws: shown by the user and with something in them
         * ([filled]). None: Home shows the Chud Music logo instead of an empty page.
         */
        fun visible(shown: Set<HomeSection>, filled: Set<HomeSection>): Set<HomeSection> = shown.intersect(filled)
    }
}
