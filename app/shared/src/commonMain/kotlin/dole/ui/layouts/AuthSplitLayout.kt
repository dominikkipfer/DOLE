package dole.ui.layouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AuthSplitLayout(
    modifier: Modifier = Modifier,
    cardContent: @Composable () -> Unit,
    inputContent: @Composable () -> Unit,
    bottomContent: @Composable () -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val isLandscape = maxWidth > maxHeight

        if (isLandscape) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Box(Modifier.padding(end = 48.dp, top = 24.dp, bottom = 24.dp).fillMaxSize()) {
                        cardContent()
                    }
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    inputContent()
                }
            }
        } else {
            val topPadding = maxHeight * 0.05f

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.height(topPadding))

                Box(
                    modifier = Modifier.weight(0.4f).fillMaxWidth().padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    cardContent()
                }

                Box(
                    modifier = Modifier.weight(0.6f).fillMaxWidth().padding(horizontal = 16.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    inputContent()
                }
            }
        }
    }
}