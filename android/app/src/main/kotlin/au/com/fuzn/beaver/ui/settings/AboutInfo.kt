package au.com.fuzn.beaver.ui.settings

/** The static text of the About section, kept apart from layout so it is easy to review and test. */
object AboutInfo {
    const val LICENSE_LINE = "Free and open source (MIT)."
    const val PRIVACY =
        "Beaver works entirely on your device. It makes no network requests and has no analytics or tracking. " +
            "Reminders are local notifications."

    data class Acknowledgement(val name: String, val author: String, val license: String)

    /** Only libraries that ship in the app. Test-only libraries (JUnit, Robolectric) are not listed. */
    val acknowledgements = listOf(
        Acknowledgement("AndroidX (Activity, Compose, Material 3, Room, WorkManager)", "The Android Open Source Project", "Apache License 2.0"),
        Acknowledgement("Kotlin and the Kotlin standard library", "JetBrains", "Apache License 2.0"),
        Acknowledgement("kotlinx.serialization", "JetBrains", "Apache License 2.0"),
        Acknowledgement("kotlinx.coroutines", "JetBrains", "Apache License 2.0"),
    )
}
