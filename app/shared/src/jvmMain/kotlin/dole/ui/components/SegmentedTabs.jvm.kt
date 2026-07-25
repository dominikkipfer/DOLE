package dole.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun SegmentedTabs(options: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit, modifier: Modifier) {
    PillSegmentedTabs(options, selectedIndex, onSelected, modifier)
}
