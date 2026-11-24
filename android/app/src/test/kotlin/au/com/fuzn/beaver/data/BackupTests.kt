package au.com.fuzn.beaver.data

import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.Forecast
import au.com.fuzn.beaver.core.Money
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.math.BigDecimal
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class BackupTests {
    private lateinit var db: TestDb
    private val repo get() = db.repository
    private val backup get() = db.backup

    @Before fun setUp() { db = TestDb() }
    @After fun tearDown() { db.close() }

    private fun fixtureText(): String = backupFixtureFile().readText()

    /** Normalises a JSON value so only meaning is compared: key order and number formatting do not matter. */
    private fun normalise(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> e.mapValues { normalise(it.value) }
        is JsonArray -> e.map { normalise(it) }
        is JsonPrimitive ->
            if (e.isString) e.content
            else e.content.toBooleanStrictOrNull() ?: BigDecimal(e.content).stripTrailingZeros().toPlainString()
    }

    private fun withoutVolatile(text: String): Map<String, Any?> {
        val root = Json.parseToJsonElement(text).jsonObject
        return (normalise(root) as Map<*, *>).entries
            .filter { it.key != "exportedAt" && it.key != "appVersion" }
            .associate { it.key as String to it.value }
    }

    // region Format

    @Test fun exportHasTheIosTopLevelKeysAndTableNames() = runBlocking {
        repo.loadDemoData()
        val root = Json.parseToJsonElement(backup.export()).jsonObject
        assertEquals(setOf("schemaVersion", "appVersion", "exportedAt", "tables"), root.keys)
        assertEquals(1, root["schemaVersion"]!!.jsonPrimitive.content.toInt())
        assertEquals("test", root["appVersion"]!!.jsonPrimitive.content)
        assertEquals(
            setOf("setting", "rateCard", "withholdingTable", "incomeSource", "template", "templateShift", "shift",
                "payReceived", "holiday", "unavailable", "hoursLimit", "bill", "billPayment", "expense",
                "weekSpendTotal", "goal", "deposit"),
            root["tables"]!!.jsonObject.keys,
        )
    }

    @Test fun exportRecordsLastExportAt() = runBlocking {
        assertNull(repo.settings().lastExportAt)
        val now = Instant.parse("2026-11-23T10:15:30Z")
        val text = backup.export(now)
        assertEquals(now, repo.settings().lastExportAt)
        assertEquals("2026-11-23T10:15:30Z", Json.parseToJsonElement(text).jsonObject["exportedAt"]!!.jsonPrimitive.content)
    }

    @Test fun emptyDatabaseRoundTrips() = runBlocking {
        val text = backup.export()
        TestDb().use { other ->
            other.backup.import(text)
            assertFalse(other.repository.settings().onboardingDone)
            assertEquals(1, other.repository.withholdingTables().size)
        }
    }

    @Test fun richDataExportsAndImportsToAnIdenticalDatabase() = runBlocking {
        repo.saveRichData()
        val text = backup.export()
        TestDb().use { other ->
            other.backup.import(text)
            assertEquals(repo.backupTables(), other.repository.backupTables())
            // And the re-export is semantically the same document.
            assertEquals(withoutVolatile(text), withoutVolatile(other.backup.export(repo.settings().lastExportAt!!)))
        }
    }

    // endregion

    // region Cross-platform (iOS file)

    @Test fun importingTheIosExportGivesTheGoldenGap() = runBlocking {
        backup.import(fixtureText())
        assertTrue(repo.settings().onboardingDone)
        val input = repo.forecastInput(DemoData.referenceToday)
        val forecast = Forecast.compute(input)
        assertEquals(Money(257_899), forecast.gap)
        assertEquals(Money(2_017_795), forecast.totalIncome)
        assertEquals(Money(874_896), forecast.totalBills)
        assertEquals(Forecast.compute(DemoData.forecastInput()), forecast)
    }

    @Test fun reExportOfTheIosImportIsSemanticallyEqualToTheIosFile() = runBlocking {
        val ios = fixtureText()
        backup.import(ios)
        // Use the iOS export time so the lastExportAt setting inside the tables matches too.
        val iosExportedAt = Instant.parse(Json.parseToJsonElement(ios).jsonObject["exportedAt"]!!.jsonPrimitive.content)
        val android = backup.export(iosExportedAt)
        assertEquals(withoutVolatile(ios), withoutVolatile(android))
        assertEquals(iosExportedAt.toString(), Json.parseToJsonElement(android).jsonObject["exportedAt"]!!.jsonPrimitive.content)
    }

    // endregion

    // region Validation

    @Test fun rejectsSchemaVersion99() = runBlocking {
        repo.loadDemoData()
        val before = repo.backupTables()
        val bad = Json.parseToJsonElement(fixtureText()).jsonObject.toMutableMap().also { it["schemaVersion"] = JsonPrimitive(99) }
        try {
            backup.import(JsonObject(bad).toString())
            fail("expected UnsupportedVersion")
        } catch (e: BackupError.UnsupportedVersion) {
            assertEquals(99, e.found)
            assertEquals(1, e.supported)
            assertTrue(e.message!!.contains("newer version of Beaver"))
            assertTrue(e.message!!.contains("Nothing was changed"))
        }
        assertEquals(before, repo.backupTables())
    }

    @Test fun rejectsNonBackups() = runBlocking {
        for (text in listOf("not json", "[]", "{}", """{"schemaVersion":"1"}""", """{"schemaVersion":1.5}""")) {
            try {
                backup.import(text)
                fail("expected NotABackup for $text")
            } catch (_: BackupError.NotABackup) {
            }
        }
        assertEquals("This file is not a Beaver backup.", BackupError.NotABackup().message)
    }

    @Test fun rejectsAnUnknownOlderVersion() = runBlocking {
        try {
            backup.import("""{"schemaVersion":0}""")
            fail("expected UnsupportedVersion")
        } catch (e: BackupError.UnsupportedVersion) {
            assertTrue(e.message!!.contains("unknown format version (0)"))
        }
    }

    @Test fun failedImportsLeaveTheDataUntouched() = runBlocking {
        repo.loadDemoData()
        val before = repo.backupTables()
        val root = Json.parseToJsonElement(fixtureText()).jsonObject

        fun withTables(edit: (MutableMap<String, JsonElement>) -> Unit): String {
            val tables = root["tables"]!!.jsonObject.toMutableMap().also(edit)
            return JsonObject(root.toMutableMap().also { it["tables"] = JsonObject(tables) }).toString()
        }

        fun row(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
        val cases = mapOf(
            // A table that is not a list.
            "wrong shape" to withTables { it["bill"] = JsonPrimitive("nope") },
            // A shift that points at a source that does not exist (foreign key violation, caught on insert).
            "broken reference" to withTables {
                it["shift"] = JsonArray(listOf(row(
                    "id" to JsonPrimitive(1), "sourceId" to JsonPrimitive(999), "date" to JsonPrimitive("2026-11-26"),
                    "start" to JsonPrimitive("09:00"), "end" to JsonPrimitive("10:00"), "breakMin" to JsonPrimitive(0),
                    "status" to JsonPrimitive("worked"), "note" to JsonPrimitive(""))))
            },
            // A duplicate primary key (fails mid-way, after other tables were already replaced).
            "duplicate key" to withTables { it["goal"] = JsonArray(root["tables"]!!.jsonObject["goal"]!!.let { g -> (g as JsonArray) + g }) },
            // A value iOS's CHECK constraint would reject.
            "bad enum" to withTables {
                it["shift"] = JsonArray(listOf(row(
                    "id" to JsonPrimitive(1), "sourceId" to JsonPrimitive(1), "date" to JsonPrimitive("2026-11-26"),
                    "start" to JsonPrimitive("09:00"), "end" to JsonPrimitive("10:00"), "breakMin" to JsonPrimitive(0),
                    "status" to JsonPrimitive("sleeping"), "note" to JsonPrimitive(""))))
            },
            "bad date" to withTables {
                it["holiday"] = JsonArray(listOf(row("date" to JsonPrimitive("26/12/2026"), "name" to JsonPrimitive("x"))))
            },
        )
        for ((name, text) in cases) {
            try {
                backup.import(text)
                fail("expected an error for: $name")
            } catch (e: BackupError.Corrupt) {
                assertTrue(name, e.message!!.endsWith("Nothing was changed."))
            }
            assertEquals("data changed after failed import: $name", before, repo.backupTables())
        }
        assertEquals(Money(257_899), Forecast.compute(repo.forecastInput(DemoData.referenceToday)).gap)
    }

    @Test fun importReplacesEverythingAndKeepsTheBuiltInTable() = runBlocking {
        repo.saveRichData()
        backup.import(fixtureText())
        assertTrue(repo.bills().size == 6)
        assertTrue(repo.hoursLimits().isEmpty())
        assertTrue(repo.expenses().isEmpty())
        assertEquals(listOf("au-scale2-weekly"), repo.withholdingTables().map { it.id })
        // A backup without the built-in table gets it back.
        val root = Json.parseToJsonElement(fixtureText()).jsonObject
        val tables = root["tables"]!!.jsonObject.toMutableMap().also { it["withholdingTable"] = JsonArray(emptyList()) }
        backup.import(JsonObject(root.toMutableMap().also { it["tables"] = JsonObject(tables) }).toString())
        val builtin = repo.withholdingTables().single()
        assertTrue(builtin.isBuiltin)
        assertEquals("au-scale2-weekly", builtin.id)
        assertNotNull(buildJsonObject { put("ok", true) })
    }

    // endregion

    // region Optional private check

    /**
     * Runs only when `-Pbeaver.private.backup=/path/to/backup.json` is passed to Gradle. It imports your own backup
     * into a throwaway in-memory database and prints the forecast gap and payday count. Nothing is stored or committed.
     */
    @Test fun privateBackupCheck() = runBlocking {
        val path = System.getProperty("beaver.private.backup")
        assumeNotNull(path)
        backup.import(File(path!!).readText())
        val settings = repo.settings()
        val today = java.time.LocalDate.now(settings.timeZone)
        val forecast = Forecast.compute(repo.forecastInput(today))
        println("PRIVATE BACKUP CHECK: gap=${forecast.gap.cents} cents, paydays=${forecast.paydayCount}, today=$today")
    }

    // endregion
}
