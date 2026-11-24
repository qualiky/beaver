package au.com.fuzn.beaver.ui.settings

import androidx.compose.runtime.saveable.SaverScope
import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.Forecast
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.WithholdingRow
import au.com.fuzn.beaver.core.WithholdingTable
import au.com.fuzn.beaver.data.TestDb
import au.com.fuzn.beaver.data.backupFixtureFile
import au.com.fuzn.beaver.data.saveRichData
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class SettingsStateTests {
    private lateinit var db: TestDb
    private lateinit var state: SettingsState

    @Before fun setUp() {
        db = TestDb()
        state = SettingsState(db.repository, db.backup, reader = { error("no reader in this test") })
    }

    @After fun tearDown() = db.close()

    private suspend fun gap() = Forecast.compute(db.repository.forecastInput(DemoData.referenceToday)).gap

    // region Export

    @Test fun exportFilenameUsesTheSettingsTimeZoneDate() {
        val at = Instant.parse("2026-11-24T14:00:00Z") // 25 Nov 01:00 in Sydney
        assertEquals("beaver-backup-2026-11-25.json", SettingsState.exportFilename(at, ZoneId.of("Australia/Sydney")))
        assertEquals("beaver-backup-2026-11-24.json", SettingsState.exportFilename(at, ZoneId.of("UTC")))
        assertEquals("beaver-backup-2026-11-24.json", SettingsState.exportFilename(at, ZoneId.of("America/Los_Angeles")))
    }

    @Test fun exportUpdatesLastExportAndHandsOverTheJson() = runBlocking {
        db.repository.loadDemoData()
        assertNull(db.repository.settings().lastExportAt)
        val now = Instant.parse("2026-11-24T01:02:03Z")
        var written: String? = null
        val ok = state.export(null, now) { written = it }
        assertTrue(ok)
        assertEquals(now, db.repository.settings().lastExportAt)
        assertTrue(written!!.contains("\"schemaVersion\""))
        assertNull(state.errorMessage)
        assertTrue(SettingsState.lastExportedText(db.repository.settings(), Locale.US).startsWith("Last exported: "))
    }

    @Test fun neverExportedText() = runBlocking {
        assertEquals("Never exported", SettingsState.lastExportedText(db.repository.settings()))
    }

    @Test fun aFailedWriteShowsAnErrorAndKeepsThePreviousExportTime() = runBlocking {
        val first = Instant.parse("2026-11-01T00:00:00Z")
        state.export(null, first) { }
        val ok = state.export(first, Instant.parse("2026-11-24T00:00:00Z")) { throw java.io.IOException("disk full") }
        assertFalse(ok)
        assertTrue(state.errorMessage!!.contains("disk full"))
        assertEquals(first, db.repository.settings().lastExportAt)
    }

    // endregion

    // region Import

    @Test fun importNeedsConfirmationAndThenReplacesEverything() = runBlocking {
        state.stageImport("backup.json", "content://x/1", backupFixtureFile().readText())
        assertEquals(PendingAction.Import, state.pending)
        assertEquals("backup.json", state.importName)
        assertEquals(0, db.repository.userRowCount()) // nothing changed yet

        state.confirmPending()
        assertNull(state.pending)
        assertNull(state.errorMessage)
        assertNotNull(state.statusMessage)
        assertEquals(Money(257_899), gap())
    }

    @Test fun cancellingAnImportChangesNothing() = runBlocking {
        state.stageImport("backup.json", null, backupFixtureFile().readText())
        state.cancelPending()
        assertNull(state.pending)
        state.confirmPending() // nothing pending: a no-op
        assertEquals(0, db.repository.userRowCount())
        assertNull(state.statusMessage)
    }

    @Test fun importErrorsAreShownAndLeaveDataUntouched() = runBlocking {
        db.repository.loadDemoData()
        val before = db.repository.backupTables()

        state.stageImport("notes.json", null, "this is not json")
        state.confirmPending()
        assertTrue(state.errorMessage!!.isNotBlank())
        assertNull(state.statusMessage)
        assertEquals(before, db.repository.backupTables())

        state.errorMessage = null
        state.stageImport("future.json", null, """{"schemaVersion": 99, "appVersion": "9", "exportedAt": "2030-01-01T00:00:00Z", "tables": {}}""")
        state.confirmPending()
        assertTrue(state.errorMessage!!.contains("99"))
        assertEquals(before, db.repository.backupTables())
    }

    @Test fun anUnreadableFileIsReportedWithoutStaging() {
        state.importFailedToRead(java.io.IOException("permission denied"))
        assertTrue(state.errorMessage!!.contains("permission denied"))
        assertNull(state.pending)
    }

    @Test fun anImportRestoredAfterRotationReadsTheFileAgain() = runBlocking {
        val text = backupFixtureFile().readText()
        val reader: suspend (String) -> String = { uri -> assertEquals("content://x/1", uri); text }
        val saver = SettingsState.saver(db.repository, db.backup, reader)
        state.stageImport("backup.json", "content://x/1", text)

        val saved = with(saver) { SaverScope { true }.save(state) }!!
        val restored = saver.restore(saved)!!
        assertEquals(PendingAction.Import, restored.pending)
        assertEquals("backup.json", restored.importName)
        restored.confirmPending()
        assertEquals(Money(257_899), gap())
    }

    @Test fun anImportWithoutAUriCannotBeRestored() {
        val saver = SettingsState.saver(db.repository, db.backup) { "" }
        state.stageImport("backup.json", null, "{}")
        val restored = saver.restore(with(saver) { SaverScope { true }.save(state) }!!)!!
        assertNull(restored.pending)
    }

    // endregion

    // region Demo and erase

    @Test fun loadingDemoDataGivesTheGoldenGap() = runBlocking {
        state.requestLoadDemo()
        assertEquals(PendingAction.LoadDemo, state.pending)
        assertEquals(0, db.repository.userRowCount())
        state.confirmPending()
        assertNull(state.errorMessage)
        assertEquals(Money(257_899), gap())
    }

    @Test fun eraseResetsOnboardingAndKeepsTheBuiltInTable() = runBlocking {
        db.repository.saveRichData()
        assertTrue(db.repository.settings().onboardingDone)
        state.requestErase()
        assertEquals(PendingAction.Erase, state.pending)
        state.confirmPending()
        assertEquals(0, db.repository.userRowCount())
        assertFalse(db.repository.settings().onboardingDone)
        assertEquals(listOf(WithholdingTable.AU_SCALE2_WEEKLY_ID), db.repository.withholdingTables().map { it.id })
        assertTrue(db.repository.withholdingTables().single().isBuiltin)
    }

    // endregion

    // region Preferences

    @Test fun preferencesAreSaved() = runBlocking {
        state.setCurrency("NZD")
        state.setTimeZone("Pacific/Auckland")
        assertTrue(state.setLivingBudget("175.50"))
        assertFalse(state.setLivingBudget("lots"))
        val settings = db.repository.settings()
        assertEquals("NZD", settings.currency)
        assertEquals("Pacific/Auckland", settings.timeZoneId)
        assertEquals(Money(17_550), settings.livingBudget)
    }

    // endregion

    // region Withholding tables

    @Test fun theBuiltInTableIsReadOnlyAndTheDuplicateIsEditable() = runBlocking {
        val builtIn = db.repository.withholdingTables().single()
        assertFalse(SettingsState.isEditable(builtIn))

        val copy = state.duplicate(builtIn)!!
        assertTrue(SettingsState.isEditable(copy))
        assertTrue(copy.id.matches(Regex("custom-[0-9a-f]{8}")))
        assertEquals(builtIn.name + " (copy)", copy.name)
        assertFalse(copy.isBuiltin)
        assertNull(copy.note)
        assertEquals(builtIn.rows, copy.rows)

        // Edit the copy through the row editor model and save it.
        val model = WithholdingEditorModel(copy)
        model.name = "My table"
        model.drafts.first().a = "0.05"
        assertTrue(model.isValid)
        val saved = state.saveTable(model.build()!!)!!
        val stored = db.repository.withholdingTables().first { it.id == copy.id }
        assertEquals("My table", stored.name)
        assertEquals(500, stored.rows.first().aTenThousandths)
        assertEquals(saved, stored)

        // The built-in table is untouched.
        assertEquals(builtIn, db.repository.withholdingTables().first { it.isBuiltin })
        assertEquals(2, db.repository.withholdingTables().size)
    }

    @Test fun rowDraftsParseDecimalsExactly() {
        assertEquals(1600, WithholdingRowDraft.tenThousandths("0.16"))
        assertEquals(578_462, WithholdingRowDraft.tenThousandths("57.8462"))
        assertEquals(1_000_000, WithholdingRowDraft.tenThousandths("100"))
        assertEquals(5_000, WithholdingRowDraft.tenThousandths(".5"))
        assertNull(WithholdingRowDraft.tenThousandths("0.12345"))
        assertNull(WithholdingRowDraft.tenThousandths("-1"))
        assertNull(WithholdingRowDraft.tenThousandths(""))
        assertNull(WithholdingRowDraft.tenThousandths("1,5"))
        assertEquals("0.16", WithholdingRowDraft.text(1600))
        assertEquals("57.8462", WithholdingRowDraft.text(578_462))
        assertEquals("0", WithholdingRowDraft.text(0))
        assertEquals("3", WithholdingRowDraft.text(30_000))
    }

    @Test fun editorModelValidatesNamesAndLimits() {
        val table = WithholdingTable("custom-1", "Mine", listOf(WithholdingRow(500, 100, 0), WithholdingRow(null, 200, 10)))
        val model = WithholdingEditorModel(table)
        assertTrue(model.isValid)
        assertEquals(table, model.build())

        model.name = "  "
        assertFalse(model.isValid)
        assertNotNull(model.nameIssue)
        model.name = "Mine"

        model.drafts[0].belowX = "12.5"
        assertNotNull(model.issue(model.drafts[0]))
        assertNull(model.build())
        model.drafts[0].belowX = "700"
        assertTrue(model.isValid) // no limit on the last row is fine, 700 then none

        model.drafts[1].belowX = "600"
        assertEquals("Limits must increase from one row to the next.", model.orderIssue)
        model.drafts[1].belowX = ""
        model.drafts[0].belowX = ""
        assertEquals("Only the last row can have no limit.", model.orderIssue)

        model.drafts.toList().forEach(model::removeRow)
        assertEquals("Add at least one row.", model.orderIssue)
        assertFalse(model.isValid)
        model.addRow()
        assertNotEquals(null, model.drafts.single().row)
    }

    // endregion

    @Test fun routesSurviveEncoding() {
        listOf(
            SettingsRoute.List, SettingsRoute.SourceNew, SettingsRoute.Source(3), SettingsRoute.Templates(2),
            SettingsRoute.CardNew, SettingsRoute.Card(1), SettingsRoute.Table("custom-ab12cd34"), SettingsRoute.BillNew,
            SettingsRoute.Bill(9), SettingsRoute.GoalNew, SettingsRoute.Goal(4), SettingsRoute.Limits,
            SettingsRoute.TimeZone, SettingsRoute.Setup,
        ).forEach { assertEquals(it, SettingsRoute.parse(it.encode())) }
        assertEquals(SettingsRoute.List, SettingsRoute.parse("nonsense"))
        assertEquals(SettingsRoute.List, SettingsRoute.parse("bill:abc"))
    }

    @Test fun timeZoneSearchFindsZonesByWords() {
        val all = TimeZones.all()
        assertTrue("Australia/Sydney" in all)
        assertTrue("Etc/GMT+5" !in all)
        assertEquals(all, TimeZones.filter("  ", all))
        assertTrue("America/New_York" in TimeZones.filter("new york", all))
        assertTrue("America/New_York" in TimeZones.filter("america york", all))
        assertTrue("Australia/Sydney" in TimeZones.filter("SYD", all))
        assertTrue(TimeZones.filter("zzzz-nowhere", all).isEmpty())
    }
}
