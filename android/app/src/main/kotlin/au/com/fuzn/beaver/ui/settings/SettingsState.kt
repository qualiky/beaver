package au.com.fuzn.beaver.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.WithholdingTable
import au.com.fuzn.beaver.data.AppSettings
import au.com.fuzn.beaver.data.Backup
import au.com.fuzn.beaver.data.Repository
import au.com.fuzn.beaver.ui.editors.MoneyParser
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID

/** A destructive action waiting for in-app confirmation. */
enum class PendingAction { Import, LoadDemo, Erase }

/**
 * All Settings logic that is not layout: backup, demo data, erase, preferences and withholding tables. Plain Kotlin
 * with Compose snapshot state (no lifecycle ViewModel), so tests drive it directly with an in-memory repository.
 *
 * What a rotation must keep (pending confirmation, file name, messages) is saved by [saver]. The imported file's
 * text is not saved (it can be large): only its Uri is, and [confirmPending] reads it again through [reader].
 */
@Stable
class SettingsState(
    private val repository: Repository,
    private val backup: Backup,
    private val reader: suspend (uri: String) -> String = { throw IllegalStateException("No file reader") },
) {
    var pending: PendingAction? by mutableStateOf(null)
        private set
    var importName: String? by mutableStateOf(null)
        private set
    private var importUri: String? = null
    private var importText: String? = null

    /** Shown in a dialog. Cleared by the view. */
    var errorMessage: String? by mutableStateOf(null)

    /** A short success line ("Demo data loaded."). Cleared by the view. */
    var statusMessage: String? by mutableStateOf(null)

    // region Export

    /**
     * Builds the backup (which records `lastExportAt`) and hands the JSON to [write]. If writing fails the previous
     * `lastExportAt` is put back, so "Last exported" never claims a file that was not saved.
     * Returns true when the file was written.
     */
    suspend fun export(previousExport: Instant?, now: Instant = Instant.now(), write: suspend (String) -> Unit): Boolean {
        return try {
            val json = backup.export(now)
            try {
                write(json)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (previousExport != null) repository.setLastExportAt(previousExport)
                errorMessage = "The backup was not saved: ${e.message ?: e.javaClass.simpleName}"
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = "The backup could not be created: ${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    // endregion

    // region Import, demo and erase

    /** Holds the chosen file and asks for confirmation. Nothing is changed yet. */
    fun stageImport(name: String, uri: String?, text: String) {
        importName = name
        importUri = uri
        importText = text
        pending = PendingAction.Import
    }

    fun importFailedToRead(error: Throwable) {
        errorMessage = "The file could not be opened: ${error.message ?: error.javaClass.simpleName}"
    }

    fun requestLoadDemo() {
        pending = PendingAction.LoadDemo
    }

    fun requestErase() {
        pending = PendingAction.Erase
    }

    fun cancelPending() {
        pending = null
        importText = null
        importUri = null
        importName = null
    }

    /** Runs the action the user just confirmed. Failures land in [errorMessage]; data stays untouched on import errors. */
    suspend fun confirmPending() {
        val action = pending ?: return
        val uri = importUri
        val staged = importText
        pending = null
        try {
            when (action) {
                PendingAction.Import -> {
                    val text = staged ?: reader(uri ?: throw IllegalStateException("No file was chosen."))
                    backup.import(text)
                    statusMessage = "Backup imported. Everything was replaced."
                }
                PendingAction.LoadDemo -> {
                    repository.loadDemoData()
                    statusMessage = "Demo data loaded."
                }
                PendingAction.Erase -> {
                    repository.eraseAll()
                    statusMessage = "All data erased."
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: e.javaClass.simpleName
        } finally {
            importText = null
            importUri = null
            importName = null
        }
    }

    // endregion

    // region Preferences

    suspend fun setCurrency(code: String) = guarded { repository.setCurrency(code) }

    suspend fun setTimeZone(id: String) = guarded { repository.setTimeZone(id) }

    /** Returns false (and changes nothing) when the text is not an amount. */
    suspend fun setLivingBudget(text: String): Boolean {
        val cents = MoneyParser.cents(text) ?: return false
        guarded { repository.setLivingBudget(Money(cents)) }
        return true
    }

    // endregion

    // region Withholding tables

    /** An editable copy of [table], saved with a new id. The original is untouched. */
    suspend fun duplicate(table: WithholdingTable): WithholdingTable? = saveTable(
        table.copy(
            id = "custom-" + UUID.randomUUID().toString().lowercase().take(8),
            name = table.name + " (copy)",
            isBuiltin = false,
            note = null,
        ),
    )

    suspend fun saveTable(table: WithholdingTable): WithholdingTable? = try {
        repository.save(table)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        errorMessage = e.message ?: e.javaClass.simpleName
        null
    }

    suspend fun deleteTable(table: WithholdingTable) = guarded { repository.deleteWithholdingTable(table.id) }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = e.message ?: e.javaClass.simpleName
        }
    }

    // endregion

    companion object {
        /** `beaver-backup-YYYY-MM-DD.json`, dated in the app's time zone. */
        fun exportFilename(now: Instant, zone: ZoneId): String = "beaver-backup-${now.atZone(zone).toLocalDate()}.json"

        /** Only the built-in table is read-only. */
        fun isEditable(table: WithholdingTable): Boolean = !table.isBuiltin

        /** "Last exported: 24 Nov 2026, 9:30 am" or "Never exported". */
        fun lastExportedText(settings: AppSettings, locale: Locale = Locale.getDefault()): String {
            val at = settings.lastExportAt ?: return "Never exported"
            val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale).withZone(settings.timeZone)
            return "Last exported: " + format.format(at)
        }

        /** Survives rotation: what is pending, the file's name and Uri, and the messages. */
        fun saver(repository: Repository, backup: Backup, reader: suspend (String) -> String): Saver<SettingsState, Any> =
            Saver(
                save = { s ->
                    listOf(s.pending?.name ?: "", s.importName ?: "", s.importUri ?: "", s.errorMessage ?: "", s.statusMessage ?: "")
                },
                restore = { saved ->
                    @Suppress("UNCHECKED_CAST")
                    val l = saved as List<String>
                    SettingsState(repository, backup, reader).also { s ->
                        s.pending = l[0].takeIf { it.isNotEmpty() }?.let { PendingAction.valueOf(it) }
                        s.importName = l[1].takeIf { it.isNotEmpty() }
                        s.importUri = l[2].takeIf { it.isNotEmpty() }
                        s.errorMessage = l[3].takeIf { it.isNotEmpty() }
                        s.statusMessage = l[4].takeIf { it.isNotEmpty() }
                        // An import without its file cannot be confirmed; drop it rather than offer a dead button.
                        if (s.pending == PendingAction.Import && s.importUri == null) s.pending = null
                    }
                },
            )
    }
}
