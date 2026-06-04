package dole.ui.layouts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun BoardLayout(
    modifier: Modifier = Modifier,
    leftPaneWeight: Float = 0.45f,
    portraitOverlap: Boolean = false,
    primaryContent: @Composable BoxScope.() -> Unit,
    secondaryContent: @Composable BoxScope.() -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWideLayout = maxWidth > maxHeight

        if (isWideLayout) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier.weight(leftPaneWeight).fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    primaryContent()
                }
                Box(
                    modifier = Modifier.weight(1f - leftPaneWeight).fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    secondaryContent()
                }
            }
        } else {
            if (portraitOverlap) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) { secondaryContent() }
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { primaryContent() }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(modifier = Modifier.fillMaxWidth().wrapContentHeight(), contentAlignment = Alignment.Center) {
                        primaryContent()
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        secondaryContent()
                    }
                }
            }
        }
    }
}