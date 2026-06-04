package dole.ui.layouts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SplitLayout(
    modifier: Modifier = Modifier,
    cardContent: @Composable BoxScope.() -> Unit,
    inputContent: @Composable BoxScope.() -> Unit,
    bottomContent: @Composable BoxScope.() -> Unit
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        val isWideLayout = maxWidth > maxHeight
        val topPadding = if (isWideLayout) 16.dp else 40.dp
        val cancelButtonHeight = 48.dp

        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (isWideLayout) {
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

            Box(
                modifier = Modifier.fillMaxWidth().height(cancelButtonHeight + 12.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                bottomContent()
            }
        }
    }
}