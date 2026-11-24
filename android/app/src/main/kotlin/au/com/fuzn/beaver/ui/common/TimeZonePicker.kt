package au.com.fuzn.beaver.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.ZoneId
import java.util.Locale

/** Pure filtering for the time zone list. */
object TimeZones {
    /** Every zone id the runtime knows, sorted, without the legacy "Etc/..." and "SystemV/..." entries. */
    fun all(): List<String> = ZoneId.getAvailableZoneIds()
        .filter { !it.startsWith("Etc/") && !it.startsWith("SystemV/") && it != "GMT0" }
        .sorted()

    /** [all] with [current] first (and once), so the picker opens on the zone in use. */
    fun choices(current: String, zones: List<String> = all()): List<String> = listOf(current) + zones.filter { it != current }

    /**
     * Zones matching [query]: every word must appear in the id (case-insensitive, "_" read as a space), so
     * "new york" and "america york" both find America/New_York. A blank query returns everything.
     */
    fun filter(query: String, zones: List<String> = all()): List<String> {
        val words = query.lowercase(Locale.ROOT).split(' ', '_', '/').filter { it.isNotEmpty() }
        if (words.isEmpty()) return zones
        return zones.filter { id ->
            val haystack = id.lowercase(Locale.ROOT).replace('_', ' ')
            words.all { it in haystack }
        }
    }
}

/** A searchable list of time zones, as a full screen. Tapping a row selects it and returns. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeZonePicker(
    selectedId: String,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val all = remember(selectedId) { TimeZones.choices(selectedId) }
    val shown = remember(query) { TimeZones.filter(query, all) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Time zone", Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize().imePadding()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search time zones") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (shown.isEmpty()) {
                Text(
                    "No time zone matches \"$query\".",
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it }) { id ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onSelect(id) }
                            .semantics { selected = id == selectedId }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(zoneDisplay(id), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        if (id == selectedId) Icon(Icons.Filled.Check, contentDescription = "Selected")
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** "America/New_York" reads "America/New York". */
fun zoneDisplay(id: String): String = id.replace('_', ' ')

/** A field showing the chosen time zone. Tapping opens [TimeZonePicker] full screen in a dialog. */
@Composable
fun TimeZonePickerField(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { open = true }
            .semantics(mergeDescendants = true) { contentDescription = "Time zone, ${zoneDisplay(selected)}" },
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Time zone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(zoneDisplay(selected), style = MaterialTheme.typography.bodyLarge)
            }
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (open) {
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                TimeZonePicker(selectedId = selected, onSelect = { open = false; onSelect(it) }, onBack = { open = false })
            }
        }
    }
}
