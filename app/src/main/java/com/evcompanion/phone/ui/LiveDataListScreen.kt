package com.evcompanion.phone.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.evcompanion.phone.viewmodel.MainViewModel

/**
 * Plain grouped list mirroring [MainViewModel.values] - the phone UI is intentionally minimal
 * (per the project scope, Android Auto is the priority surface). Subscribes to every category
 * in the active preset while this screen is visible, unsubscribing on dispose.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LiveDataListScreen(viewModel: MainViewModel) {
    val presets by viewModel.presets.collectAsState()
    val activeId by viewModel.activePresetId.collectAsState()
    val values by viewModel.values.collectAsState()
    val activePreset = presets.firstOrNull { it.id == activeId }

    DisposableEffect(activePreset?.id) {
        val categories = activePreset?.categories.orEmpty()
        categories.forEach { viewModel.subscribe(it) }
        onDispose { categories.forEach { viewModel.unsubscribe(it) } }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Live data") }) }) { padding ->
        if (activePreset == null) {
            Text("Select a preset first.", modifier = Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        val grouped = values.values.sortedBy { it.displayName }.groupBy { it.category }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            grouped.forEach { (category, categoryValues) ->
                item { Text(category.label, style = androidx.compose.material3.MaterialTheme.typography.titleMedium) }
                items(categoryValues) { v ->
                    ListItem(
                        headlineContent = { Text(v.displayName) },
                        trailingContent = { Text("${"%.2f".format(v.value)} ${v.unit}") },
                    )
                }
            }
        }
    }
}
