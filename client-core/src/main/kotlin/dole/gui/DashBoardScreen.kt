package dole.gui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import dole.transaction.BurnTransaction
import dole.transaction.GenesisTransaction
import dole.transaction.MintTransaction
import dole.transaction.SendTransaction
import dole.wallet.StoredAccount
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.util.*
import kotlin.math.abs

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun DashboardScreen(
    viewModel: WalletViewModel,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    var showBurnDialog by remember { mutableStateOf(false) }
    var showMintDialog by remember { mutableStateOf(false) }
    var showSendDialog by remember { mutableStateOf(false) }

    val backgroundColor = Color(0xFFF2F2F7)

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        val isWideLayout = maxWidth > 600.dp || maxWidth > maxHeight
        val activeId = viewModel.currentId
        val accounts = viewModel.availableAccounts

        var displayedAccount by remember { mutableStateOf<StoredAccount?>(null) }
        val foundAccount = accounts.find { it.id() == activeId }

        if (foundAccount != null) displayedAccount = foundAccount

        val currentAccount = displayedAccount
        val screenHeight = maxHeight

        if (isWideLayout) {
            WideDashboardLayout(
                viewModel = viewModel,
                currentAccount = currentAccount,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                onSend = { showSendDialog = true },
                onMint = { showMintDialog = true },
                onBurn = { showBurnDialog = true }
            )
        } else {
            PortraitDashboardLayout(
                viewModel = viewModel,
                currentAccount = currentAccount,
                maxWidth = maxWidth,
                screenHeight = screenHeight,
                backgroundColor = backgroundColor,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                onSend = { showSendDialog = true },
                onMint = { showMintDialog = true },
                onBurn = { showBurnDialog = true }
            )
        }
    }

    DashboardDialogs(
        viewModel = viewModel,
        showSend = showSendDialog,
        showMint = showMintDialog,
        showBurn = showBurnDialog,
        onDismissSend = { showSendDialog = false },
        onDismissMint = { showMintDialog = false },
        onDismissBurn = { showBurnDialog = false }
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun WideDashboardLayout(
    viewModel: WalletViewModel,
    currentAccount: StoredAccount?,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onSend: () -> Unit,
    onMint: () -> Unit,
    onBurn: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val leftColumnWidth = maxWidth * 0.45f
        val availableWidth = leftColumnWidth - 64.dp
        val slotWidth = availableWidth / 5
        val buttonSize = minOf(60.dp, slotWidth * 0.85f)

        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.weight(0.45f).fillMaxHeight().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        SharedWalletCard(currentAccount, viewModel.isCardConnected, sharedTransitionScope, animatedVisibilityScope, true)
                    }
                    Spacer(Modifier.height(32.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Card Balance", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                        Text("DM ${viewModel.balance}", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                    Spacer(Modifier.height(32.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        TransactionActionButtons(
                            buttonSize = buttonSize,
                            onSend = onSend,
                            onMint = onMint,
                            onBurn = onBurn,
                            onSettings = { viewModel.goToSettings() }
                        )
                        ActionButton("Logout", Icons.Default.Close, Color.DarkGray, buttonSize, onClick = { viewModel.logout() })
                    }
                }
            }
            Box(modifier = Modifier.weight(0.55f).fillMaxHeight().background(Color.White).padding(horizontal = 16.dp)) {
                TransactionDashboardList(
                    pendingActions = viewModel.pendingActions,
                    unsyncedTxs = viewModel.getUnsyncedIncoming(),
                    viewModel = viewModel,
                    listState = rememberLazyListState(),
                    topPadding = null,
                    minHeaderHeight = 0.dp,
                    screenHeight = 0.dp,
                    isWideLayout = true
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PortraitDashboardLayout(
    viewModel: WalletViewModel,
    currentAccount: StoredAccount?,
    maxWidth: Dp,
    screenHeight: Dp,
    backgroundColor: Color,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onSend: () -> Unit,
    onMint: () -> Unit,
    onBurn: () -> Unit
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()

    val cardHeightExpanded = maxWidth / 1.586f
    val horizontalPadding = 24.dp

    val availableWidth = maxWidth - (horizontalPadding * 2)
    val slotWidth = availableWidth / 5
    val buttonSize = minOf(60.dp, slotWidth * 0.8f)
    val buttonsHeight = buttonSize + 25.dp

    val cardTopPad = 12.dp
    val balanceTopPad = 32.dp
    val buttonsTopPad = 72.dp
    val extraBottomPad = 12.dp

    val expandedHeaderHeight = cardTopPad + cardHeightExpanded + balanceTopPad + buttonsTopPad + buttonsHeight + extraBottomPad

    val minHeaderHeight = 150.dp
    val scrollLimitHeaderHeight = 104.dp
    val effectiveMaxHeaderHeight = maxOf(expandedHeaderHeight, minHeaderHeight)

    val maxHeaderPx = with(density) { effectiveMaxHeaderHeight.toPx() }
    val minScrollHeaderPx = with(density) { scrollLimitHeaderHeight.toPx() }
    val scrollRange = maxHeaderPx - minScrollHeaderPx

    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var isSnapping by remember { mutableStateOf(false) }
    val animationRange = scrollRange * 0.9f

    LaunchedEffect(listState, scrollRange) {
        snapshotFlow { listState.isScrollInProgress || isDragged || isSnapping }
            .distinctUntilChanged().filter { !it }.collect {
                val currentOffset = listState.firstVisibleItemScrollOffset
                val rangeInt = scrollRange.toInt()
                if (listState.firstVisibleItemIndex == 0 && currentOffset > 10 && currentOffset < rangeInt - 10) {
                    isSnapping = true
                    try {
                        if (currentOffset < rangeInt / 2) listState.animateScrollToItem(0, 0)
                        else listState.animateScrollToItem(0, rangeInt)
                    } finally { isSnapping = false }
                }
            }
    }

    val collapseFactor by remember {
        derivedStateOf {
            val firstIndex = listState.firstVisibleItemIndex
            if (firstIndex > 0) 1f else (listState.firstVisibleItemScrollOffset / animationRange).coerceIn(0f, 1f)
        }
    }

    val pathX = 1f - (1f - collapseFactor) * (1f - collapseFactor)
    val pathY = collapseFactor * collapseFactor
    val scrollOffsetPx = with(density) { listState.firstVisibleItemScrollOffset.toDp() }
    val dampenedParallaxY = if (listState.firstVisibleItemIndex == 0) -scrollOffsetPx * 0.3f else 0.dp
    val listSpacerHeight = effectiveMaxHeaderHeight - 8.dp

    TransactionDashboardList(
        pendingActions = viewModel.pendingActions,
        unsyncedTxs = viewModel.getUnsyncedIncoming(),
        viewModel = viewModel,
        listState = listState,
        topPadding = listSpacerHeight,
        minHeaderHeight = scrollLimitHeaderHeight,
        screenHeight = screenHeight,
        isWideLayout = false
    )

    Box(modifier = Modifier.fillMaxWidth().height(effectiveMaxHeaderHeight).zIndex(1f)) {
        val currentHeaderHeight = interpolateDp(effectiveMaxHeaderHeight, minHeaderHeight, collapseFactor)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(currentHeaderHeight)
                .align(Alignment.TopCenter)
                .background(Brush.verticalGradient(0.0f to backgroundColor, 0.65f to backgroundColor, 1.0f to backgroundColor.copy(alpha = 0f)))
                .padding(top = 24.dp)
        )

        val cardWidthCollapsed = 80.dp * 1.586f
        val currentCardWidth = interpolateDp(maxWidth, cardWidthCollapsed, collapseFactor)
        val currentCardX = interpolateDp((maxWidth - maxWidth) / 2, 16.dp, collapseFactor)
        val currentCardY = interpolateDp(cardTopPad, 8.dp, collapseFactor)
        val showFullId = collapseFactor < 0.5f

        Box(modifier = Modifier.offset(x = currentCardX, y = currentCardY).width(currentCardWidth).zIndex(2f)) {
            SharedWalletCard(currentAccount, viewModel.isCardConnected, sharedTransitionScope, animatedVisibilityScope, showFullId)
        }

        val balanceYExpanded = cardTopPad + cardHeightExpanded + balanceTopPad
        val currentBalanceY = interpolateDp(balanceYExpanded + dampenedParallaxY, 24.dp, pathY)
        val alignmentBias = androidx.compose.ui.util.lerp(0f, 1f, pathX)
        val currentPaddingRight = interpolateDp(24.dp, 64.dp, pathX)
        val currentFontSize = androidx.compose.ui.unit.lerp(32.sp, 20.sp, pathY)
        val currentLabelSize = androidx.compose.ui.unit.lerp(14.sp, 11.sp, pathY)

        Box(modifier = Modifier.fillMaxWidth().offset(y = currentBalanceY).padding(start = 24.dp, end = currentPaddingRight).zIndex(2f)) {
            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy((-2).dp),
                modifier = Modifier.align(BiasAlignment(alignmentBias, 0f))
            ) {
                Text("Card Balance", modifier = Modifier.offset(x = 2.dp), style = MaterialTheme.typography.labelMedium.copy(fontSize = currentLabelSize, lineHeight = currentLabelSize), color = Color.Gray)
                Text("DM ${viewModel.balance}", style = MaterialTheme.typography.headlineMedium.copy(fontSize = currentFontSize, fontWeight = FontWeight.Bold, lineHeight = currentFontSize), color = Color.Black)
            }
        }

        val buttonsAlpha = (1f - (collapseFactor * 4)).coerceIn(0f, 1f)
        val buttonsY = balanceYExpanded + buttonsTopPad
        val buttonsParallaxY = if (listState.firstVisibleItemIndex == 0) -scrollOffsetPx else (-1000).dp

        if (buttonsAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = buttonsY + buttonsParallaxY)
                    .alpha(buttonsAlpha)
                    .zIndex(2f)
                    .padding(horizontal = horizontalPadding)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    TransactionActionButtons(
                        buttonSize = buttonSize,
                        onSend = onSend,
                        onMint = onMint,
                        onBurn = onBurn,
                        onSettings = { viewModel.goToSettings() }
                    )
                    Spacer(modifier = Modifier.width(buttonSize))
                }
            }
        }

        val logoutSlotCenter = horizontalPadding + (slotWidth * 4) + (slotWidth / 2)
        val logoutStartX = logoutSlotCenter - (buttonSize / 2) + 16.dp
        val logoutEndX = maxWidth - 56.dp

        val animationProgress = collapseFactor
        val currentLogoutX = interpolateDp(logoutStartX, logoutEndX, animationProgress)
        val currentLogoutY = interpolateDp(buttonsY, 28.dp, animationProgress)
        val currentLogoutSize = interpolateDp(buttonSize, 40.dp, animationProgress)
        val textAlpha = (1f - (animationProgress * 4)).coerceIn(0f, 1f)

        Box(modifier = Modifier.offset(x = currentLogoutX, y = currentLogoutY).width(currentLogoutSize).zIndex(3f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    modifier = Modifier.size(currentLogoutSize).clip(CircleShape).clickable { viewModel.logout() },
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 2.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Close, "Logout", tint = Color.Black, modifier = Modifier.size(currentLogoutSize * 0.4f))
                    }
                }
                if (textAlpha > 0.1f) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Logout",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = (12 * (buttonSize.value/60)).coerceAtLeast(10.0F).sp),
                        modifier = Modifier.alpha(textAlpha),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun TransactionDashboardList(
    pendingActions: List<PendingAction>,
    unsyncedTxs: List<DisplayTransaction>,
    viewModel: WalletViewModel,
    listState: LazyListState,
    topPadding: Dp?,
    minHeaderHeight: Dp,
    screenHeight: Dp,
    isWideLayout: Boolean
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    var spacerHeight by remember { mutableStateOf(0.dp) }
    val itemSpacing = 8.dp
    val bottomPad = 16.dp

    val draggableState = rememberDraggableState { delta -> listState.dispatchRawDelta(-delta) }

    LaunchedEffect(listState, topPadding, screenHeight, minHeaderHeight, pendingActions.size, unsyncedTxs.size, viewModel.filteredHistory.size, isWideLayout) {
        if (isWideLayout || topPadding == null) {
            spacerHeight = 0.dp
            return@LaunchedEffect
        }

        snapshotFlow { listState.layoutInfo }
            .collectLatest { layoutInfo ->
                val totalItems = layoutInfo.totalItemsCount
                if (totalItems <= 2) {
                    val minHeaderPx = with(density) { minHeaderHeight.toPx() }
                    val screenHeightPx = with(density) { screenHeight.toPx() }
                    spacerHeight = with(density) { (screenHeightPx - minHeaderPx).toDp() }.coerceAtLeast(0.dp)
                } else {
                    val visibleItems = layoutInfo.visibleItemsInfo
                    val bottomSpacerIndex = totalItems - 1
                    val realItems = visibleItems.filter { it.index in 1 until bottomSpacerIndex }

                    if (realItems.isNotEmpty()) {
                        val contentHeightPx = realItems.sumOf { it.size }
                        val itemSpacingPx = with(density) { itemSpacing.toPx() }
                        val realContentHeightPx = contentHeightPx + (realItems.size * itemSpacingPx)

                        val minHeaderPx = with(density) { minHeaderHeight.toPx() }
                        val screenHeightPx = with(density) { screenHeight.toPx() }
                        val bottomPadPx = with(density) { bottomPad.toPx() }

                        val targetVisibleAreaPx = screenHeightPx - minHeaderPx
                        val missingPx = targetVisibleAreaPx - realContentHeightPx - bottomPadPx

                        val newHeight = if (missingPx > 0) with(density) { missingPx.toDp() } else 0.dp
                        if (abs(newHeight.value - spacerHeight.value) > 1f) spacerHeight = newHeight
                    }
                }
            }
    }

    val contentPad = if (topPadding != null) PaddingValues(bottom = bottomPad) else PaddingValues(top = 0.dp, bottom = 32.dp)
    val sessionList = viewModel.sessionTransactions
    val searchList = viewModel.filteredHistory
    val myId = viewModel.currentId ?: ""
    val isSearchMode = viewModel.isSearchMode
    val hasPending = pendingActions.isNotEmpty() || unsyncedTxs.isNotEmpty()

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .draggable(
                state = draggableState,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    coroutineScope.launch {
                        listState.scroll {
                            var lastValue = 0f
                            AnimationState(
                                initialValue = 0f,
                                initialVelocity = -velocity,
                            ).animateDecay(exponentialDecay()) {
                                val delta = value - lastValue
                                scrollBy(delta)
                                lastValue = value
                            }
                        }
                    }
                }
            ),
        contentPadding = contentPad,
        verticalArrangement = Arrangement.spacedBy(itemSpacing),
        userScrollEnabled = true
    ) {
        item {
            if (topPadding != null) Spacer(Modifier.height(topPadding))
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, bottom = 8.dp)
                    .background(Color.Transparent),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = if (isSearchMode) "Search History" else "Latest Transactions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                IconButton(onClick = { viewModel.isSearchMode = !viewModel.isSearchMode }) {
                    Icon(imageVector = if (isSearchMode) Icons.Default.Close else Icons.Default.Search, contentDescription = "Toggle Search", tint = Color(0xFF007AFF))
                }
            }
        }

        if (isSearchMode) {
            item {
                val bgColor = if(isWideLayout) Color.White else Color.Transparent
                Box(Modifier.padding(horizontal = 16.dp)) { FilterSection(viewModel, bgColor) }
            }
            item {
                SortHeaderRow(
                    sortField = viewModel.sortField,
                    isAscending = viewModel.sortAscending,
                    onSortType = { viewModel.cycleSort(SortField.TYPE) },
                    onSortAmount = { viewModel.cycleSort(SortField.AMOUNT) }
                )
            }
            if (searchList.isEmpty()) {
                item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("No results found.", color = Color.Gray) } }
            } else {
                items(searchList, key = { "search-${it.tx.id()}" }) { item -> Box(Modifier.padding(horizontal = 16.dp)) { TransactionRow(item, myId, isPending = false) } }
            }
        } else {
            if (hasPending) {
                item { Text("Pending", style = MaterialTheme.typography.titleMedium, color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                items(pendingActions, key = { "pending-${it.id}" }) { action -> Box(Modifier.padding(horizontal = 16.dp)) { LocalPendingRow(action) } }
                items(unsyncedTxs, key = { "unsynced-${it.tx.id()}" }) { item -> Box(Modifier.padding(horizontal = 16.dp)) { TransactionRow(item, myId, isPending = true) } }
                item { Spacer(Modifier.height(16.dp)) }
            }
            if (sessionList.isNotEmpty()) {
                items(sessionList, key = { "session-${it.tx.id()}" }) { item -> Box(Modifier.padding(horizontal = 16.dp)) { TransactionRow(item, myId, isPending = false) } }
            } else if (!hasPending) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CheckCircle, "Done", tint = Color.Green, modifier = Modifier.size(64.dp).alpha(0.5f))
                            Spacer(Modifier.height(16.dp))
                            Text("Session is empty", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
                            Text("New transactions will appear here.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                }
            }
        }

        if (spacerHeight > 0.dp) item { Spacer(Modifier.height(spacerHeight)) }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedWalletCard(
    account: StoredAccount?,
    isOnline: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    showFullId: Boolean
) {
    if (account != null) {
        with(sharedTransitionScope) {
            WalletCard(
                account = account,
                isOnline = isOnline,
                showFullId = showFullId,
                onClick = null,
                modifier = Modifier.fillMaxWidth().sharedBounds(
                    sharedContentState = rememberSharedContentState(key = "card-${account.id()}"),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = { _, _ -> tween(500) }
                )
            )
        }
    }
}

@Composable
private fun DashboardDialogs(
    viewModel: WalletViewModel,
    showSend: Boolean,
    showMint: Boolean,
    showBurn: Boolean,
    onDismissSend: () -> Unit,
    onDismissMint: () -> Unit,
    onDismissBurn: () -> Unit
) {
    val currentBalance = viewModel.balance

    if (showSend) {
        SendOverlay(
            peers = viewModel.knownNetworkPeers,
            currentBalance = currentBalance,
            onDismiss = onDismissSend,
            onConfirm = { targetId, amount -> viewModel.send(targetId, amount); onDismissSend() }
        )
    }
    if (showMint) {
        MintBurnOverlay(
            title = "Mint",
            maxBalance = null,
            onDismiss = onDismissMint,
            onConfirm = { viewModel.mint(it); onDismissMint() }
        )
    }
    if (showBurn) {
        MintBurnOverlay(
            title = "Burn",
            maxBalance = currentBalance,
            onDismiss = onDismissBurn,
            onConfirm = { viewModel.burn(it); onDismissBurn() }
        )
    }
}

@Composable
fun FilterSection(viewModel: WalletViewModel, backgroundColor: Color = Color.White) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        elevation = CardDefaults.cardElevation(0.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val selection = viewModel.filterTypes
            val hasMint = selection.contains(TxFilterType.MINT)
            val hasBurn = selection.contains(TxFilterType.BURN)
            val hasSend = selection.contains(TxFilterType.SEND)
            val hasReceive = selection.contains(TxFilterType.RECEIVE)

            val showPeerFilter = !(selection.isNotEmpty() && !hasSend && !hasReceive && (hasMint || hasBurn))

            Box(Modifier.width(IntrinsicSize.Min)) { TypeFilterDropdown(viewModel) }

            if (showPeerFilter) {
                val label = when {
                    hasSend && !hasReceive -> "Receiver"
                    !hasSend && hasReceive -> "Sender"
                    else -> "Sender / Receiver"
                }
                Box(Modifier.weight(1f)) { PeerFilterDropdown(viewModel, viewModel.knownNetworkPeers, label) }
            }
        }
    }
}

@Composable
fun SortHeaderRow(sortField: SortField, isAscending: Boolean, onSortType: () -> Unit, onSortAmount: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SortIndicator(
            label = "Type",
            isActive = sortField == SortField.TYPE,
            isAscending = isAscending,
            onClick = onSortType
        )

        SortIndicator(
            label = "Amount",
            isActive = sortField == SortField.AMOUNT,
            isAscending = isAscending,
            onClick = onSortAmount
        )
    }
}

@Composable
fun SortIndicator(label: String, isActive: Boolean, isAscending: Boolean, onClick: () -> Unit) {
    val contentColor = if (isActive) Color.Black else Color.Gray
    val icon = when {
        !isActive -> Icons.Default.UnfoldMore
        isAscending -> Icons.Default.ArrowUpward
        else -> Icons.Default.ArrowDownward
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = contentColor)
        Spacer(Modifier.width(4.dp))
        Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun TypeFilterDropdown(viewModel: WalletViewModel) {
    var isExpanded by remember { mutableStateOf(false) }
    val selectedTypes = viewModel.filterTypes
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

    fun getIconData(type: TxFilterType): Pair<ImageVector, Color> {
        return when (type) {
            TxFilterType.MINT -> Pair(Icons.Default.Add, Color.Green)
            TxFilterType.BURN -> Pair(Icons.Default.Remove, Color.Red)
            TxFilterType.SEND -> Pair(Icons.AutoMirrored.Filled.Send, Color(0xFF007AFF))
            TxFilterType.RECEIVE -> Pair(Icons.AutoMirrored.Filled.Send, Color(0xFF007AFF))
        }
    }

    Box(modifier = Modifier.width(120.dp)) {
        OutlinedTextField(
            value = " ",
            onValueChange = {},
            readOnly = true,
            label = { Text("Type", style = MaterialTheme.typography.bodySmall) },
            prefix = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (selectedTypes.isEmpty()) {
                        Icon(Icons.Default.FilterList, "All", tint = Color.Gray, modifier = Modifier.size(20.dp))
                    } else {
                        selectedTypes.sortedBy { it.ordinal }.forEach { type ->
                            val (icon, color) = getIconData(type)
                            Icon(
                                imageVector = icon,
                                contentDescription = type.name,
                                tint = color,
                                modifier = Modifier.size(20.dp).graphicsLayer {
                                    if (type == TxFilterType.RECEIVE) rotationZ = 180f
                                }
                            )
                        }
                    }
                }
            },
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
            ),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.Transparent),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Box(
            Modifier.matchParentSize().clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { isExpanded = true }
            )
        )

        DropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }, modifier = Modifier.background(Color.White)) {
            TxFilterType.entries.forEach { type ->
                val (icon, color) = getIconData(type)
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier
                                    .size(20.dp)
                                    .graphicsLayer {
                                        if (type == TxFilterType.RECEIVE) rotationZ = 180f
                                    }
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(type.name.lowercase().capitalize(), style = MaterialTheme.typography.bodyMedium)
                        }
                    },
                    trailingIcon = {
                        Checkbox(
                            checked = viewModel.filterTypes.contains(type),
                            onCheckedChange = { viewModel.toggleFilterType(type) }
                        )
                    },
                    onClick = { viewModel.toggleFilterType(type) }
                )
            }

            if (viewModel.filterTypes.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Reset", color = Color.Red) },
                    onClick = { viewModel.filterTypes.clear(); isExpanded = false }
                )
            }
        }
    }
}

@Composable
fun PeerFilterDropdown(viewModel: WalletViewModel, peers: List<PeerOption>, labelText: String) {
    var isExpanded by remember { mutableStateOf(false) }
    var textFieldSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    val filteredPeers = peers.filter { it.label.contains(viewModel.filterPeerQuery, ignoreCase = true) || it.id.contains(viewModel.filterPeerQuery) }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = viewModel.filterPeerQuery,
            onValueChange = { viewModel.filterPeerQuery = it; isExpanded = true },
            label = { Text(labelText, style = MaterialTheme.typography.bodySmall) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, "Select", Modifier.clickable { isExpanded = !isExpanded }) },
            modifier = Modifier.fillMaxWidth().onGloballyPositioned { textFieldSize = it.size.toSize() },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium
        )
        DropdownMenu(
            expanded = isExpanded,
            onDismissRequest = { isExpanded = false },
            modifier = Modifier.width(with(LocalDensity.current) { textFieldSize.width.toDp() }).background(Color.White),
            properties = PopupProperties(focusable = false)
        ) {
            filteredPeers.take(5).forEach { peer ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(peer.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            if (!peer.label.startsWith("User ...")) {
                                Text(peer.id.take(8) + "...", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                    },
                    onClick = { viewModel.filterPeerQuery = peer.label; isExpanded = false }
                )
            }
            if (viewModel.filterPeerQuery.isNotEmpty()) {
                DropdownMenuItem(text = { Text("Reset", color = Color.Red) }, onClick = { viewModel.filterPeerQuery = ""; isExpanded = false })
            }
        }
    }
}

@Composable
fun SendOverlay(peers: List<PeerOption>, currentBalance: Int, onDismiss: () -> Unit, onConfirm: (String, Int) -> Unit) {
    var amountText by remember { mutableStateOf("") }
    var receiverInput by remember { mutableStateOf("") }
    var isDropdownExpanded by remember { mutableStateOf(false) }
    var textFieldSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

    val amountInt = amountText.toIntOrNull()

    val resolvedTargetId = remember(receiverInput, peers) {
        val match = peers.find { it.label.equals(receiverInput, ignoreCase = true) || it.id == receiverInput }
        match?.id ?: if (receiverInput.length > 5) receiverInput else null
    }

    val isAmountValid = amountInt != null && amountInt > 0 && amountInt <= currentBalance
    val isReceiverValid = resolvedTargetId != null

    val filteredPeers = peers.filter { it.label.contains(receiverInput, ignoreCase = true) || it.id.contains(receiverInput) }

    DashboardActionDialog(onDismissRequest = onDismiss) {
        Text(text = "Send Money", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = receiverInput,
                onValueChange = { receiverInput = it; isDropdownExpanded = true },
                label = { Text("Receiver (Name or ID)") },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, "History", Modifier.clickable { isDropdownExpanded = !isDropdownExpanded }) },
                modifier = Modifier.fillMaxWidth().onGloballyPositioned { textFieldSize = it.size.toSize() },
                singleLine = true
            )
            if (isDropdownExpanded && (filteredPeers.isNotEmpty() || receiverInput.isNotEmpty())) {
                DropdownMenu(
                    expanded = true, onDismissRequest = { isDropdownExpanded = false },
                    modifier = Modifier.width(with(LocalDensity.current) { textFieldSize.width.toDp() }).background(Color.White),
                    properties = PopupProperties(focusable = false)
                ) {
                    filteredPeers.forEach { peer ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(peer.label, fontWeight = FontWeight.Bold)
                                    if (!peer.label.startsWith("User ...")) {
                                        FrontEllipsizedText(text = peer.id, style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            },
                            onClick = { receiverInput = peer.label; isDropdownExpanded = false }
                        )
                    }
                    if (receiverInput.isNotEmpty()) {
                        DropdownMenuItem(text = { Text("Reset", color = Color.Red) }, onClick = { receiverInput = ""; isDropdownExpanded = false })
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = amountText,
            onValueChange = { input ->
                if (input.all { it.isDigit() }) amountText = input
            },
            label = { Text("Amount") },
            isError = amountInt != null && amountInt > currentBalance,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (amountInt != null && amountInt > currentBalance) {
            Text(
                text = "Insufficient funds (Balance: $currentBalance)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (resolvedTargetId != null && amountInt != null) {
                        onConfirm(resolvedTargetId, amountInt)
                    }
                },
                enabled = isAmountValid && isReceiverValid
            ) { Text("Send") }
        }
    }
}

@Composable
fun DashboardActionDialog(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    SafeOverlay(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.padding(24.dp)) {
            content()
        }
    }
}

@Composable
fun MintBurnOverlay(title: String, maxBalance: Int? = null, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val amountInt = text.toIntOrNull()

    val isValid = if (maxBalance == null) {
        (amountInt != null && amountInt > 0)
    } else {
        (amountInt != null && amountInt > 0 && amountInt <= maxBalance)
    }

    DashboardActionDialog(onDismissRequest = onDismiss) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { input ->
                if (input.all { it.isDigit() }) text = input
            },
            label = { Text("Amount") },
            isError = maxBalance != null && amountInt != null && amountInt > maxBalance,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (maxBalance != null && amountInt != null && amountInt > maxBalance) {
            Text(
                text = "Insufficient funds (Balance: $maxBalance)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { amountInt?.let(onConfirm) }, enabled = isValid) { Text("OK") }
        }
    }
}

fun interpolateDp(start: Dp, stop: Dp, fraction: Float): Dp = Dp(start.value + (stop.value - start.value) * fraction)
fun String.capitalize(): String = this.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

@Composable
fun FrontEllipsizedText(text: String, style: androidx.compose.ui.text.TextStyle, color: Color = Color.Unspecified, modifier: Modifier = Modifier, maxLines: Int = 1) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    var availableWidthPx by remember { mutableStateOf<Int?>(null) }
    val displayText = remember(text, availableWidthPx) {
        val width = availableWidthPx ?: return@remember text
        var low = 0
        var high = text.length
        var best = text
        while (low <= high) {
            val mid = (low + high) / 2
            val candidate = if (mid == 0) text else "…" + text.takeLast(text.length - mid)
            val result = measurer.measure(text = candidate, style = style, maxLines = maxLines)
            if (!result.hasVisualOverflow && result.size.width <= width) {
                best = candidate
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        best
    }
    Text(text = displayText, style = style, color = color, maxLines = maxLines, modifier = modifier.onSizeChanged { availableWidthPx = it.width })
}

@Composable
private fun TransactionActionButtons(
    buttonSize: Dp,
    onSend: () -> Unit,
    onMint: () -> Unit,
    onBurn: () -> Unit,
    onSettings: () -> Unit
) {
    ActionButton("Send", Icons.AutoMirrored.Filled.Send, Color(0xFF007AFF), buttonSize, onClick = onSend)
    ActionButton("Mint", Icons.Default.Add, Color.Green, buttonSize, onClick = onMint)
    ActionButton("Burn", Icons.Default.Remove, Color.Red, buttonSize, onClick = onBurn)
    ActionButton("Settings", Icons.Default.Settings, Color.Gray, buttonSize, onClick = onSettings)
}

@Composable
fun ActionButton(
    text: String,
    icon: ImageVector,
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.width(IntrinsicSize.Min)) {
        Surface(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .clickable(onClick = onClick),
            shape = CircleShape,
            color = Color.White,
            shadowElevation = 2.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = text, tint = color, modifier = Modifier.size(size * 0.4f))
            }
        }
        Spacer(Modifier.height(4.dp))
        val fontSize = (12 * (size.value / 60)).coerceAtLeast(10.0F).sp
        Text(text, style = MaterialTheme.typography.labelMedium.copy(fontSize = fontSize), maxLines = 1)
    }
}

@Composable
fun LocalPendingRow(action: PendingAction) {
    val title = when (action.type) {
        "SEND" -> "Send"
        "MINT" -> "Mint"
        "BURN" -> "Burn"
        else -> "Processing"
    }
    val isIncoming = action.type == "MINT"
    val color = if (isIncoming) Color.Green else Color.Red
    val prefix = if (isIncoming) "+" else "-"
    val iconVector = when (action.type) {
        "MINT" -> Icons.Default.Add
        "BURN" -> Icons.Default.Remove
        else -> Icons.AutoMirrored.Filled.Send
    }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = iconVector, contentDescription = "Pending", tint = Color.Gray, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                if (action.targetId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("To ", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        FrontEllipsizedText(text = action.targetId, style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Text("$prefix DM ${action.amount}", fontWeight = FontWeight.Bold, color = color.copy(alpha = 0.6f))
        }
    }
}

@Composable
fun TransactionRow(item: DisplayTransaction, myId: String, isPending: Boolean) {
    val tx = item.tx
    val isMe = tx.author() == myId
    val isGenesis = tx is GenesisTransaction
    val isIncoming = !isMe && tx !is BurnTransaction && !isGenesis
    if (isGenesis) return

    Card(
        modifier = Modifier.alpha(if (isPending) 0.8f else 1f),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val iconVector = when {
                isIncoming -> Icons.AutoMirrored.Filled.Send
                tx is BurnTransaction -> Icons.Default.Remove
                tx is MintTransaction -> Icons.Default.Add
                else -> Icons.AutoMirrored.Filled.Send
            }
            val iconTint = when {
                isPending -> Color.Gray
                tx is BurnTransaction -> Color.Red
                tx is MintTransaction -> Color.Green
                else -> Color(0xFF007AFF)
            }
            Icon(
                imageVector = iconVector,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(28.dp).graphicsLayer { if (isIncoming) rotationZ = 180f }
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                val title = if (isPending && isIncoming) "Receive" else when (tx) {
                    is MintTransaction -> if (isPending) "Mint" else "Minted"
                    is BurnTransaction -> if (isPending) "Burn" else "Burned"
                    is SendTransaction -> if (isIncoming) "Received" else (if (isPending) "Send" else "Sent")
                    else -> "Transaction"
                }
                Text(title, fontWeight = FontWeight.Bold)
                if (tx !is MintTransaction && tx !is BurnTransaction) {
                    val prefixLabel = if (tx is SendTransaction && !isIncoming) "To " else "From "
                    val idString = if (tx is SendTransaction && !isIncoming) (tx.target ?: "?") else tx.author()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(prefixLabel, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        FrontEllipsizedText(text = idString, style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            val isExpense = tx is BurnTransaction || (tx is SendTransaction && !isIncoming)
            val prefix = if (isExpense) "-" else "+"
            val color = if (isExpense) Color.Red else Color.Green
            val amountText = if (item.delta > 0) "$prefix DM ${item.delta}" else "..."
            Text(amountText, fontWeight = FontWeight.Bold, color = if (isPending) color.copy(alpha = 0.6f) else color)
        }
    }
}