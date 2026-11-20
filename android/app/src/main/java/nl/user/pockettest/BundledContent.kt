package nl.user.pockettest

import android.content.Context

/**
 * First-start setup from files bundled in the APK (github flavour).
 * The build copies `pocket-runtime.zip` and the supplied `.pttsvoice` files into `assets/bundle`.
 * A source build without these files keeps the manual import from Menu > Setup.
 */
object BundledContent {
    private const val FOLDER = "bundle"
    private const val RUNTIME = "pocket-runtime.zip"
    private const val VOICES = "bundle/voices"
    private const val PREFERENCES = "bundled_content"
    private const val VOICES_DONE = "voices_installed"

    fun install(context: Context): String? {
        val assets = context.assets
        if (RUNTIME !in assets.list(FOLDER).orEmpty()) return null
        val store = LocalData(context)
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val needRuntime = !store.runtime.exists()
        // Voices are installed once. A voice the user removes later is not added again.
        val needVoices = !preferences.getBoolean(VOICES_DONE, false)
        if (!needRuntime && !needVoices) return null
        TestState.status = "Setting up the built-in model and voices. This takes a moment on first start."
        if (needRuntime) assets.open("$FOLDER/$RUNTIME").use { store.importRuntime(it) }
        var count = 0
        if (needVoices) {
            for (name in assets.list(VOICES).orEmpty().filter { it.endsWith(".pttsvoice") }.sorted()) {
                assets.open("$VOICES/$name").use { store.importVoice(it) }
                count++
            }
            preferences.edit().putBoolean(VOICES_DONE, true).apply()
        }
        return "Built-in setup done: ${if (needRuntime) "model, " else ""}$count voices. Select a document and press Play."
    }
}
