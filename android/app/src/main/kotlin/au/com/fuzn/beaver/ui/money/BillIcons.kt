package au.com.fuzn.beaver.ui.money

import androidx.compose.ui.graphics.vector.ImageVector
import au.com.fuzn.beaver.core.BillCategory
import au.com.fuzn.beaver.ui.editors.BillPreset
import au.com.fuzn.beaver.ui.editors.EditorLabels

/**
 * The icon for a bill. It comes from the bill's own preset (matched by name) and only falls back to the category
 * icon when no preset matches. A Phone bill in the "utilities" category therefore shows the phone, not the bolt
 * that Electricity uses (the iOS Money tab once did this by mapping the category alone).
 */
object BillIcons {
    /** The preset whose name equals [name] (ignoring case), else the longest preset name found as whole words in [name]. */
    fun presetFor(name: String, presets: List<BillPreset> = BillPreset.common): BillPreset? {
        val words = words(name)
        if (words.isEmpty()) return null
        presets.firstOrNull { words(it.name) == words }?.let { return it }
        return presets
            .filter { p -> containsRun(words, words(p.name)) }
            .maxByOrNull { it.name.length }
    }

    fun icon(name: String, category: BillCategory): ImageVector =
        presetFor(name)?.icon ?: EditorLabels.categoryIcon(category)

    private fun words(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    private fun containsRun(words: List<String>, run: List<String>): Boolean {
        if (run.isEmpty() || run.size > words.size) return false
        return (0..words.size - run.size).any { start -> words.subList(start, start + run.size) == run }
    }
}
