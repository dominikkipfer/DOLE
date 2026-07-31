package dole.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.calf.ui.dialog.AdaptiveAlertDialog
import com.mohamedrejeb.calf.ui.dialog.uikit.AlertDialogIosActionStyle
import dole.viewmodel.PeerConnection
import dole.ui.components.AppSwitchButton
import dole.ui.components.LocalBottomBarInset
import dole.ui.components.SegmentedTabs
import dole.ui.components.EntryRow
import dole.ui.theme.ThemeMode
import dole.ui.theme.DoleBlue
import dole.core.CoreWrapper
import dole.viewmodel.BenchmarkKind
import dole.viewmodel.WalletViewModel

@Composable
fun DeveloperScreen(viewModel: WalletViewModel) {
    var showDeleteAccountsConfirm by remember { mutableStateOf(false) }
    var showDeleteLedgerConfirm by remember { mutableStateOf(false) }
    var showDisableConfirm by remember { mutableStateOf(false) }
    val localSessionId = CoreWrapper.localSessionId()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 32.dp + LocalBottomBarInset.current)
    ) {
        item(key = "header") {
            Text(
                text = "Developer",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 48.dp, bottom = 8.dp)
            )
        }

        item(key = "network-title") { SectionTitle("Network") }

        item(key = "ble") {
            Box(Modifier.padding(horizontal = 24.dp)) {
                AppSwitchButton(
                    title = "BLE",
                    icon = Icons.Default.Bluetooth,
                    iconActive = viewModel.developer.status.ble,
                    checked = viewModel.developer.isBleEnabled,
                    onCheckedChange = { viewModel.developer.enableBle(it) }
                )
            }
        }

        item(key = "local") {
            Box(Modifier.padding(horizontal = 24.dp)) {
                AppSwitchButton(
                    title = "Local",
                    icon = Icons.Default.Wifi,
                    iconActive = viewModel.developer.status.local,
                    checked = viewModel.developer.isLocalEnabled,
                    onCheckedChange = { viewModel.developer.enableLocal(it) }
                )
            }
        }

        item(key = "internet") {
            Box(Modifier.padding(horizontal = 24.dp)) {
                AppSwitchButton(
                    title = "Online",
                    icon = Icons.Default.Public,
                    iconActive = viewModel.developer.status.internet,
                    checked = viewModel.developer.isInternetEnabled,
                    onCheckedChange = { viewModel.developer.enableInternet(it) }
                )
            }
        }

        item(key = "peers-title") { SectionTitle(localSessionId) }

        if (viewModel.developer.peers.isEmpty()) {
            item(key = "peers-empty") {
                Text(
                    text = "No peers",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }
        } else {
            items(viewModel.developer.peers, key = { it.sessionId }) { peer ->
                Box(Modifier.animateItem().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    PeerRow(peer)
                }
            }
        }

        item(key = "bench-mode") {
            Box(Modifier.padding(horizontal = 24.dp)) {
                AppSwitchButton(
                    title = "Benchmark mode",
                    icon = Icons.Default.Science,
                    iconActive = viewModel.isBenchmarkMode,
                    checked = viewModel.isBenchmarkMode,
                    onCheckedChange = { viewModel.enableBenchmarkMode(it) }
                )
            }
        }

        if (viewModel.isBenchmarkMode) {
            item(key = "bench-workload") {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SettingsMenuButton("Workload generator", color = DoleBlue) {
                        viewModel.runBenchmark(BenchmarkKind.WORKLOAD)
                    }
                }
            }

            item(key = "bench-scale") {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SettingsMenuButton("Sync scale", color = DoleBlue) {
                        viewModel.runBenchmark(BenchmarkKind.SCALE)
                    }
                }
            }

            item(key = "bench-storage") {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SettingsMenuButton("Local storage", color = DoleBlue) {
                        viewModel.runBenchmark(BenchmarkKind.STORE)
                    }
                }
            }

            item(key = "bench-latency") {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SettingsMenuButton("Latency", color = DoleBlue) {
                        viewModel.runBenchmark(BenchmarkKind.LATENCY)
                    }
                }
            }

        }

        item(key = "diagnostics-title") { SectionTitle("Diagnostics") }

        item(key = "diagnostics") {
            Box(Modifier.padding(horizontal = 16.dp)) {
                InfoCard(
                    rows = listOf(
                        "Accounts on device" to viewModel.availableAccounts.size.toString(),
                        "Commits in ledger" to viewModel.globalHistory.size.toString(),
                        "Accounts on network" to viewModel.networkAccounts.size.toString()
                    )
                )
            }
        }

        item(key = "appearance-title") { SectionTitle("Appearance") }

        item(key = "theme") {
            SegmentedTabs(
                options = ThemeMode.entries.map { it.label() },
                selectedIndex = viewModel.developer.themeMode.ordinal,
                onSelected = { viewModel.developer.selectThemeMode(ThemeMode.entries[it]) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(44.dp)
            )
        }

        item(key = "delete-accounts") {
            Box(Modifier.padding(horizontal = 16.dp).padding(top = 24.dp, bottom = 4.dp)) {
                SettingsMenuButton("Delete all accounts", color = MaterialTheme.colorScheme.error) {
                    showDeleteAccountsConfirm = true
                }
            }
        }

        item(key = "delete-transactions") {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SettingsMenuButton("Delete all transactions", color = MaterialTheme.colorScheme.error) {
                    showDeleteLedgerConfirm = true
                }
            }
        }

        item(key = "reset") {
            Box(Modifier.padding(horizontal = 16.dp).padding(top = 24.dp, bottom = 4.dp)) {
                SettingsMenuButton("Reset to defaults") {
                    viewModel.developer.resetToDefaults()
                    viewModel.showUserMessage("Developer options reset to defaults.")
                }
            }
        }

        item(key = "disable") {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SettingsMenuButton("Turn off developer options", color = MaterialTheme.colorScheme.error) {
                    showDisableConfirm = true
                }
            }
        }
    }

    if (showDeleteAccountsConfirm) {
        AdaptiveAlertDialog(
            onConfirm = {
                showDeleteAccountsConfirm = false
                viewModel.developer.deleteAllAccounts()
            },
            onDismiss = { showDeleteAccountsConfirm = false },
            confirmText = "Delete",
            dismissText = "Cancel",
            title = "Delete all accounts",
            text = "Removes every account from this device. The cards themselves are not affected.",
            iosConfirmButtonStyle = AlertDialogIosActionStyle.Destructive,
            iosDismissButtonStyle = AlertDialogIosActionStyle.Cancel
        )
    }

    if (showDeleteLedgerConfirm) {
        AdaptiveAlertDialog(
            onConfirm = {
                showDeleteLedgerConfirm = false
                viewModel.developer.deleteAllCommits()
            },
            onDismiss = { showDeleteLedgerConfirm = false },
            confirmText = "Delete",
            dismissText = "Cancel",
            title = "Delete all transactions",
            text = "Wipes the local ledger. Peers still have their history and may sync it back.",
            iosConfirmButtonStyle = AlertDialogIosActionStyle.Destructive,
            iosDismissButtonStyle = AlertDialogIosActionStyle.Cancel
        )
    }

    if (showDisableConfirm) {
        AdaptiveAlertDialog(
            onConfirm = {
                showDisableConfirm = false
                viewModel.developer.enable(false)
            },
            onDismiss = { showDisableConfirm = false },
            confirmText = "Turn off",
            dismissText = "Cancel",
            title = "Turn off developer options",
            text = "Hides this tab and restores the defaults. Tap the wallet logo three times to get it back.",
            iosConfirmButtonStyle = AlertDialogIosActionStyle.Destructive,
            iosDismissButtonStyle = AlertDialogIosActionStyle.Cancel
        )
    }
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun InfoCard(rows: List<Pair<String, String>>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun PeerRow(peer: PeerConnection) {
    EntryRow(
        title = peer.sessionId,
        titleEllipsized = true,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TransportIcon(Icons.Default.Bluetooth, "BLE", peer.ble)
                Spacer(Modifier.width(12.dp))
                TransportIcon(Icons.Default.Wifi, "Local network", peer.local)
                Spacer(Modifier.width(12.dp))
                TransportIcon(Icons.Default.Public, "Internet", peer.internet)
            }
        }
    )
}

@Composable
private fun TransportIcon(icon: ImageVector, description: String, active: Boolean) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = if (active) DoleBlue else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.size(20.dp)
    )
}
