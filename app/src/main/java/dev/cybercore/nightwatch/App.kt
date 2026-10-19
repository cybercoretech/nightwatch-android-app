package dev.cybercore.nightwatch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NightwatchApp(model: NightwatchModel, state: UiState, authenticate: (String, () -> Unit) -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); model.clearMessage() }
    }
    BackHandler(enabled = state.stack.isNotEmpty() && !(state.hosts.isEmpty() && state.stack.size == 1)) { model.back() }

    Box(Modifier.fillMaxSize()) {
        GridBackdrop()
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = {
                SnackbarHost(snackbar) { Snackbar(it, containerColor = Grid.panel, contentColor = Grid.fg, actionColor = Grid.cyan) }
            },
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Grid.bg.copy(alpha = .85f)),
                    navigationIcon = {
                        if (state.stack.isNotEmpty() && state.hosts.isNotEmpty()) {
                            IconButton(onClick = { model.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                        }
                    },
                    title = { HostSwitcher(state, model) },
                    actions = {
                        if (state.hosts.isNotEmpty()) {
                            IconButton(onClick = { model.refresh() }) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh), tint = Grid.cyan) }
                        }
                    },
                )
            },
            bottomBar = {
                if (state.hosts.isNotEmpty()) BottomTabs(state, model)
            },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Grid.pink)
                Box(Modifier.fillMaxSize()) { Content(model, state) }
            }
        }
    }

    state.confirm?.let { c ->
        AlertDialog(
            onDismissRequest = model::cancelConfirm,
            containerColor = Grid.panel,
            title = { Text(c.title, style = MaterialTheme.typography.titleMedium) },
            text = { Text(c.message, color = Grid.muted) },
            confirmButton = {
                TextButton(onClick = {
                    if (c.strong) authenticate(c.title) { model.runConfirmed() } else model.runConfirmed()
                }) { Text(stringResource(R.string.confirm), color = Grid.pink) }
            },
            dismissButton = { TextButton(onClick = model::cancelConfirm) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun HostSwitcher(state: UiState, model: NightwatchModel) {
    var open by remember { mutableStateOf(false) }
    val title = state.host?.label ?: stringResource(R.string.app_name)
    Box {
        TextButton(onClick = { if (state.hosts.size > 1) open = true }) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(title.uppercase(), style = MaterialTheme.typography.titleMedium, color = Grid.cyan, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when {
                    state.hosts.isEmpty() -> stringResource(R.string.tagline)
                    state.sessionError != null -> stringResource(R.string.unreachable)
                    state.session != null -> if (state.session.operate) stringResource(R.string.mode_operate) else stringResource(R.string.mode_read)
                    else -> "…"
                }
                Text(sub, style = MaterialTheme.typography.labelSmall, color = if (state.sessionError != null) Grid.red else Grid.muted)
            }
            if (state.hosts.size > 1) Icon(Icons.Default.ArrowDropDown, null, tint = Grid.muted)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            state.hosts.forEach { h ->
                DropdownMenuItem(text = { Text(h.label) }, onClick = { open = false; model.selectHost(h.id) })
            }
        }
    }
}

@Composable
private fun BottomTabs(state: UiState, model: NightwatchModel) {
    NavigationBar(containerColor = Grid.bg.copy(alpha = .92f)) {
        TabItem(state, model, Tab.Overview, Icons.Default.Dashboard, R.string.tab_overview)
        TabItem(state, model, Tab.Units, Icons.Default.Memory, R.string.tab_units)
        TabItem(state, model, Tab.Stacks, Icons.Default.Inventory2, R.string.tab_stacks)
        TabItem(state, model, Tab.Alerts, Icons.Default.NotificationsActive, R.string.tab_alerts, state.alerts?.open ?: state.overview?.alertsOpen ?: 0)
        TabItem(state, model, Tab.More, Icons.Default.MoreHoriz, R.string.tab_more)
    }
}

@Composable
private fun RowScope.TabItem(state: UiState, model: NightwatchModel, tab: Tab, icon: ImageVector, label: Int, badge: Int = 0) {
    NavigationBarItem(
        selected = state.tab == tab && state.stack.isEmpty(),
        onClick = { model.selectTab(tab) },
        icon = {
            if (badge > 0) BadgedBox(badge = { Badge(containerColor = Grid.red) { Text("$badge") } }) { Icon(icon, null) } else Icon(icon, null)
        },
        label = { Text(stringResource(label), style = MaterialTheme.typography.labelSmall) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Grid.cyan, selectedTextColor = Grid.cyan,
            indicatorColor = Grid.cyan.copy(alpha = .12f),
            unselectedIconColor = Grid.muted, unselectedTextColor = Grid.muted,
        ),
    )
}

@Composable
private fun Content(model: NightwatchModel, state: UiState) {
    when (val d = state.detail) {
        is Detail.UnitPage -> UnitScreen(model, state, d.id)
        is Detail.StackPage -> StackScreen(model, state, d.id)
        is Detail.ContainerLogs -> TextScreen(state, d.name)
        is Detail.ReportPage -> ReportScreen(state, d.title)
        Detail.Deck -> DeckScreen(model, state)
        Detail.Mission -> MissionScreen(model, state)
        is Detail.MissionAgent -> HeartbeatsScreen(state, d.name)
        Detail.Activity -> ActivityScreen(state)
        Detail.Hosts -> HostsScreen(model, state)
        Detail.AddHost -> AddHostScreen(model, state)
        Detail.PushSettings -> PushScreen(model, state)
        null -> when (state.tab) {
            Tab.Overview -> OverviewScreen(model, state)
            Tab.Units -> UnitsScreen(model, state)
            Tab.Stacks -> StacksScreen(model, state)
            Tab.Alerts -> AlertsScreen(model, state)
            Tab.More -> MoreScreen(model, state)
        }
    }
}
