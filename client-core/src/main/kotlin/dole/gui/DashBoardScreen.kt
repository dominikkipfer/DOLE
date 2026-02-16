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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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

    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    fun copyToClipboard(text: String) {
        copyToClipboard(
            clipboardManager = clipboardManager,
            text = text,
            onSuccess = { viewModel.showUserMessage("ID copied to clipboard") }
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        val isWideLayout = maxWidth > maxHeight
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
                screenHeight = screenHeight,
                onSend = { showSendDialog = true },
                onMint = { showMintDialog = true },
                onBurn = { showBurnDialog = true },
                onCopyId = { viewModel.currentId?.let { copyToClipboard(it) } },
                onCopyAny = { copyToClipboard(it) }
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
                onBurn = { showBurnDialog = true },
                onCopyId = { viewModel.currentId?.let { copyToClipboard(it) } },
                onCopyAny = { copyToClipboard(it) }
            )
        }

        DashboardDialogs(
            viewModel = viewModel,
            showSend = showSendDialog,
            showMint = showMintDialog,
            showBurn = showBurnDialog,
            screenHeight = screenHeight,
            onDismissSend = { showSendDialog = false },
            onDismissMint = { showMintDialog = false },
            onDismissBurn = { showBurnDialog = false }
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun WideDashboardLayout(
    viewModel: WalletViewModel,
    currentAccount: StoredAccount?,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    screenHeight: Dp,
    onSend: () -> Unit,
    onMint: () -> Unit,
    onBurn: () -> Unit,
    onCopyId: () -> Unit,
    onCopyAny: (String) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompactHeight = maxHeight < 500.dp
        val spacerHeight = if (isCompactHeight) 12.dp else 32.dp
        val maxCardHeight = maxHeight * 0.45f
        val maxCardWidthFromHeight = maxCardHeight * 1.586f

        val leftColumnAvailableWidth = (maxWidth * 0.45f) - 64.dp
        val finalCardWidth = minOf(leftColumnAvailableWidth, maxCardWidthFromHeight)
        val slotWidth = leftColumnAvailableWidth / 5
        val maxButtonHeight = maxHeight * 0.15f
        val buttonSize = minOf(60.dp, slotWidth * 0.85f, maxButtonHeight)

        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.weight(0.45f).fillMaxHeight().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.width(finalCardWidth).verticalScroll(rememberScrollState())
                ) {
                    Box(modifier = Modifier.width(finalCardWidth)) {
                        SharedWalletCard(
                            currentAccount,
                            viewModel.isCardConnected,
                            sharedTransitionScope,
                            animatedVisibilityScope,
                            true,
                            onIdClick = onCopyId
                        )
                    }
                    Spacer(Modifier.height(spacerHeight))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Card Balance", style = MaterialTheme.typography.labelMedium, color = Color.Gray)

                        Text(
                            "DM ${viewModel.balance}",
                            style = if (isCompactHeight) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black.copy(alpha = if (viewModel.isBalancePending) 0.5f else 1.0f)
                        )
                    }
                    Spacer(Modifier.height(spacerHeight))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        TransactionActionButtons(
                            buttonSize = buttonSize,
                            isMinter = viewModel.isMinter,
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
                    screenHeight = screenHeight,
                    isWideLayout = true,
                    onCopy = onCopyAny
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
    onBurn: () -> Unit,
    onCopyId: () -> Unit,
    onCopyAny: (String) -> Unit
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val cardHeightExpanded = maxWidth / 1.586f
    val horizontalPadding = 24.dp
    val slotWidth = (maxWidth - (horizontalPadding * 2)) / 5
    val buttonSize = minOf(60.dp, slotWidth * 0.8f)

    val cardTopPad = 12.dp
    val balanceTopPad = 32.dp
    val buttonsTopPad = 72.dp
    val extraBottomPad = 12.dp

    val expandedHeaderHeight = cardTopPad + cardHeightExpanded + balanceTopPad + buttonsTopPad + buttonSize + 25.dp + extraBottomPad
    val minHeaderHeight = 150.dp
    val scrollLimitHeaderHeight = 104.dp
    val effectiveMaxHeaderHeight = maxOf(expandedHeaderHeight, minHeaderHeight)

    val maxHeaderPx = density.run { effectiveMaxHeaderHeight.toPx() }
    val minScrollHeaderPx = density.run { scrollLimitHeaderHeight.toPx() }
    val scrollRange = maxHeaderPx - minScrollHeaderPx

    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var isSnapping by remember { mutableStateOf(false) }
    val animationRange = scrollRange * 0.9f

    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

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
        isWideLayout = false,
        onCopy = onCopyAny
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
            SharedWalletCard(
                account = currentAccount,
                isOnline = viewModel.isCardConnected,
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
                showFullId = showFullId,
                onClick = if (isAtTop) null else {
                    { coroutineScope.launch { listState.animateScrollToItem(0) } }
                },
                onIdClick = onCopyId
            )
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

                Text(
                    "DM ${viewModel.balance}",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = currentFontSize,
                        fontWeight = FontWeight.Bold,
                        lineHeight = currentFontSize
                    ),
                    color = Color.Black.copy(alpha = if (viewModel.isBalancePending) 0.5f else 1.0f)
                )
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
                        isMinter = viewModel.isMinter,
                        onSend = onSend,
                        onMint = onMint,
                        onBurn = onBurn,
                        onSettings = { viewModel.goToSettings() }
                    )
                    Spacer(modifier = Modifier.width(buttonSize))
                }
            }
        }

        val logoutStartX = maxWidth - horizontalPadding - buttonSize
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
    isWideLayout: Boolean,
    onCopy: (String) -> Unit
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

    val sessionList = viewModel.sessionTransactions.distinctBy { it.tx.id() }
    val searchList = remember(viewModel.filteredHistory) {
        viewModel.filteredHistory.distinctBy { it.tx.id() }
    }
    val safeUnsyncedTxs = remember(unsyncedTxs) {
        unsyncedTxs.distinctBy { it.tx.id() }
    }

    val myId = viewModel.currentId ?: ""
    val isSearchMode = viewModel.isSearchMode
    val hasPending = pendingActions.isNotEmpty() || safeUnsyncedTxs.isNotEmpty()

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
                IconButton(onClick = { viewModel.toggleSearchMode() }) {
                    Icon(imageVector = if (isSearchMode) Icons.Default.Close else Icons.Default.Search, contentDescription = "Toggle Search", tint = Color(0xFF007AFF))
                }
            }
        }

        if (isSearchMode) {
            item {
                val cardColor = if (isWideLayout) Color.White else Color.Transparent
                val inputColor = if (isWideLayout) Color(0xFFF2F2F7) else Color.White

                Box(Modifier.padding(horizontal = 16.dp)) {
                    FilterSection(
                        viewModel = viewModel,
                        screenHeight = screenHeight,
                        backgroundColor = cardColor,
                        inputBackgroundColor = inputColor
                    )
                }
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
                items(searchList, key = { "search-${it.tx.id()}" }) { item ->
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        TransactionRow(item, myId, isPending = item.isUnsynced, onCopy = onCopy, nameResolver = viewModel::getPeerName)
                    }
                }
            }
        } else {
            if (hasPending) {
                item { Text("Pending", style = MaterialTheme.typography.titleMedium, color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                items(pendingActions, key = { "pending-${it.id}" }) { action ->
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        LocalPendingRow(action, onCopy = onCopy, nameResolver = viewModel::getPeerName)
                    }
                }
                items(safeUnsyncedTxs, key = { "unsynced-${it.tx.id()}" }) { item ->
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        TransactionRow(item, myId, isPending = true, onCopy = onCopy, nameResolver = viewModel::getPeerName)
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
            if (sessionList.isNotEmpty()) {
                items(sessionList, key = { "session-${it.tx.id()}" }) { item ->
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        TransactionRow(item, myId, isPending = false, onCopy = onCopy, nameResolver = viewModel::getPeerName)
                    }
                }
            } else if (!hasPending) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.CheckCircle, "Done", tint = Color.Green, modifier = Modifier.size(64.dp).alpha(0.5f))
                            Spacer(Modifier.height(16.dp))
                            Text("Up To Date", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
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
    showFullId: Boolean,
    onClick: (() -> Unit)? = null,
    onIdClick: (() -> Unit)? = null
) {
    if (account != null) {
        with(sharedTransitionScope) {
            WalletCard(
                account = account,
                isOnline = isOnline,
                showFullId = showFullId,
                onClick = onClick,
                onIdClick = onIdClick,
                modifier = Modifier.fillMaxWidth().sharedBounds(
                    sharedContentState = rememberSharedContentState(key = "card-${account.id()}"),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = { _, _ -> tween(500) },
                    renderInOverlayDuringTransition = false
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
    screenHeight: Dp,
    onDismissSend: () -> Unit,
    onDismissMint: () -> Unit,
    onDismissBurn: () -> Unit
) {
    val currentBalance = viewModel.balance
    val ownId = viewModel.currentId ?: ""

    if (showSend) {
        SendOverlay(
            peers = viewModel.knownNetworkPeers,
            currentBalance = currentBalance,
            ownId = ownId,
            screenHeight = screenHeight,
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
fun CleanAmountInput(
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    maxInputLimit: Long = Int.MAX_VALUE.toLong()
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val fontSize = 56.sp
        val commonTextStyle = MaterialTheme.typography.displayMedium.copy(
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            lineHeight = fontSize
        )

        Text(
            text = "DM",
            style = commonTextStyle.copy(fontSize = 32.sp, lineHeight = 32.sp),
            color = if (value.isEmpty()) Color.LightGray else Color.Black,
            modifier = Modifier.padding(end = 8.dp).offset(y = 4.dp)
        )

        Box(contentAlignment = Alignment.Center, modifier = Modifier.width(IntrinsicSize.Min)) {
            val textStyle = commonTextStyle.copy(
                color = if (isError) MaterialTheme.colorScheme.error else Color.Black,
                textAlign = TextAlign.Start
            )

            BasicTextField(
                value = value,
                onValueChange = { input ->
                    val numericInput = input.filter { it.isDigit() }
                    val cleanInput = if (numericInput.startsWith("0") && numericInput.length > 1) {
                        numericInput.dropWhile { it == '0' }
                    } else numericInput

                    if (cleanInput.isEmpty()) {
                        onValueChange("")
                    } else {
                        val num = cleanInput.toLongOrNull()
                        if (num != null && num <= maxInputLimit) {
                            onValueChange(cleanInput)
                        }
                    }
                },
                textStyle = textStyle,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                cursorBrush = SolidColor(Color.Black),
                singleLine = true,
                modifier = Modifier
                    .width(IntrinsicSize.Min)
                    .defaultMinSize(minWidth = 40.dp),
                decorationBox = { innerTextField ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(
                            text = "0",
                            style = textStyle,
                            color = Color.Transparent
                        )
                        if (value.isEmpty()) {
                            Text(
                                text = "0",
                                style = textStyle,
                                color = Color.LightGray
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
    }
}

@Composable
fun UnifiedPeerInput(
    value: String,
    onValueChange: (String) -> Unit,
    peers: List<PeerOption>,
    placeholder: String,
    screenHeight: Dp,
    containerColor: Color = Color(0xFFF2F2F7),
    onPeerSelected: (String) -> Unit
) {
    var isDropdownExpanded by remember { mutableStateOf(false) }
    var textFieldSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

    val density = LocalDensity.current
    var inputBottomY by remember { mutableFloatStateOf(0f) }

    val filteredPeers = peers.filter { it.label.contains(value, ignoreCase = true) || it.id.contains(value) }

    Box(modifier = Modifier.fillMaxWidth()) {
        TextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                isDropdownExpanded = true
            },
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = Color.Gray) },
            trailingIcon = {
                Row {
                    if (value.isNotEmpty()) {
                        IconButton(onClick = { onValueChange(""); isDropdownExpanded = false }) {
                            Icon(Icons.Default.Close, "Clear", tint = Color.Gray)
                        }
                    }
                    IconButton(onClick = { isDropdownExpanded = !isDropdownExpanded }) {
                        Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = "Dropdown", tint = Color.Gray)
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = containerColor,
                unfocusedContainerColor = containerColor,
                disabledContainerColor = containerColor,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    textFieldSize = coordinates.size.toSize()
                    inputBottomY = coordinates.positionInRoot().y + coordinates.size.height
                },
            singleLine = true
        )

        if (isDropdownExpanded && (filteredPeers.isNotEmpty() || value.isNotEmpty())) {
            val screenHeightPx = with(density) { screenHeight.toPx() }
            val spaceBelowPx = screenHeightPx - inputBottomY
            val spaceBelowDp = density.run { spaceBelowPx.toDp() }

            val maxMenuHeight = (spaceBelowDp - 48.dp).coerceAtLeast(0.dp).coerceAtMost(200.dp)

            DropdownMenu(
                expanded = true,
                onDismissRequest = { isDropdownExpanded = false },
                modifier = Modifier
                    .width(with(LocalDensity.current) { textFieldSize.width.toDp() })
                    .heightIn(max = maxMenuHeight)
                    .background(Color.White),
                properties = PopupProperties(focusable = false)
            ) {
                filteredPeers.forEach { peer ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                val displayName = if (peer.label.startsWith("User", ignoreCase = true)) peer.id else peer.label
                                FrontEllipsizedText(text = displayName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.fillMaxWidth())
                            }
                        },
                        onClick = {
                            onPeerSelected(if (peer.label.startsWith("User", ignoreCase = true)) peer.id else peer.label)
                            isDropdownExpanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ModernActionSheet(
    title: String,
    onDismissRequest: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    isConfirmEnabled: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    SafeOverlay(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 32.sp),
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    modifier = Modifier.align(Alignment.CenterStart)
                )
                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.align(Alignment.CenterEnd).size(32.dp)
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color.Black)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Column(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.Center
            ) {
                content()
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onConfirm,
                enabled = isConfirmEnabled,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White, disabledContainerColor = Color.LightGray),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(text = confirmText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SendOverlay(peers: List<PeerOption>, currentBalance: Int, ownId: String, screenHeight: Dp, onDismiss: () -> Unit, onConfirm: (String, Int) -> Unit) {
    var amountText by remember { mutableStateOf("") }
    var receiverInput by remember { mutableStateOf("") }

    val amountInt = amountText.toIntOrNull()

    val resolvedTargetId = remember(receiverInput, peers) {
        val match = peers.find { it.label.equals(receiverInput, ignoreCase = true) || it.id == receiverInput }
        match?.id ?: if (receiverInput.length > 5) receiverInput else null
    }

    val isAmountValid = amountInt != null && amountInt > 0 && amountInt <= currentBalance
    val isSelf = resolvedTargetId == ownId
    val isReceiverValid = resolvedTargetId != null

    ModernActionSheet(
        title = "Send",
        onDismissRequest = onDismiss,
        confirmText = "Send",
        onConfirm = {
            if (resolvedTargetId != null && amountInt != null && !isSelf) onConfirm(resolvedTargetId, amountInt)
                    },
        isConfirmEnabled = isAmountValid && isReceiverValid
    ) {
        CleanAmountInput(
            value = amountText,
            onValueChange = { amountText = it },
            isError = amountInt != null && amountInt > currentBalance,
            maxInputLimit = currentBalance.toLong()
        )

        Spacer(modifier = Modifier.height(32.dp))

        UnifiedPeerInput(
            value = receiverInput,
            onValueChange = { receiverInput = it },
            peers = peers,
            placeholder = "Receiver (Name or ID)",
            screenHeight = screenHeight,
            onPeerSelected = { receiverInput = it }
        )
    }
}

@Composable
fun MintBurnOverlay(title: String, maxBalance: Int? = null, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var amountText by remember { mutableStateOf("") }
    val amountInt = amountText.toIntOrNull()

    val isValid = if (maxBalance == null) {
        (amountInt != null && amountInt > 0)
    } else {
        (amountInt != null && amountInt > 0 && amountInt <= maxBalance)
    }

    val actionText = if (title == "Mint") "Mint" else "Burn"

    val limit = if (title == "Mint") {
        Int.MAX_VALUE.toLong()
    } else {
        maxBalance?.toLong() ?: Int.MAX_VALUE.toLong()
    }

    ModernActionSheet(
        title = title,
        onDismissRequest = onDismiss,
        confirmText = actionText,
        onConfirm = { amountInt?.let(onConfirm) },
        isConfirmEnabled = isValid
    ) {
        CleanAmountInput(
            value = amountText,
            onValueChange = { amountText = it },
            isError = maxBalance != null && amountInt != null && amountInt > maxBalance,
            maxInputLimit = limit
        )
    }
}

@Composable
fun FilterSection(viewModel: WalletViewModel, screenHeight: Dp, backgroundColor: Color = Color.White, inputBackgroundColor: Color) {
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

            Box(Modifier.width(IntrinsicSize.Min)) {
                TypeFilterDropdown(viewModel, containerColor = inputBackgroundColor)
            }

            if (showPeerFilter) {
                val label = when {
                    hasSend && !hasReceive -> "Receiver"
                    !hasSend && hasReceive -> "Sender"
                    else -> "Sender / Receiver"
                }

                val relevantIds = remember(viewModel.filteredHistory, viewModel.filterTypes, viewModel.currentId) {
                    val types = viewModel.filterTypes
                    val onlySend = types.contains(TxFilterType.SEND) && !types.contains(TxFilterType.RECEIVE)
                    val onlyReceive = types.contains(TxFilterType.RECEIVE) && !types.contains(TxFilterType.SEND)

                    viewModel.filteredHistory.mapNotNull { item ->
                        val tx = item.tx
                        val myId = viewModel.currentId
                        val isMe = tx.author() == myId

                        if (onlySend) {
                            if (isMe && tx is SendTransaction) tx.target else null
                        } else if (onlyReceive) {
                            if (!isMe && tx is SendTransaction) tx.author() else null
                        } else {
                            if (isMe && tx is SendTransaction) tx.target
                            else if (!isMe) tx.author()
                            else null
                        }
                    }.toSet()
                }

                val relevantPeers = viewModel.knownNetworkPeers.filter { relevantIds.contains(it.id) }

                Box(Modifier.weight(1f)) {
                    UnifiedPeerInput(
                        value = viewModel.filterPeerQuery,
                        onValueChange = { viewModel.filterPeerQuery = it },
                        peers = relevantPeers,
                        placeholder = label,
                        screenHeight = screenHeight,
                        containerColor = inputBackgroundColor,
                        onPeerSelected = { viewModel.filterPeerQuery = it }
                    )
                }
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
fun TypeFilterDropdown(viewModel: WalletViewModel, containerColor: Color = Color(0xFFF2F2F7)) {
    var isExpanded by remember { mutableStateOf(false) }
    val selectedTypes = viewModel.filterTypes
    val interactionSource = remember { MutableInteractionSource() }

    fun getIconData(type: TxFilterType): Pair<ImageVector, Color> {
        return when (type) {
            TxFilterType.MINT -> Pair(Icons.Default.Add, Color.Green)
            TxFilterType.BURN -> Pair(Icons.Default.Remove, Color.Red)
            TxFilterType.SEND -> Pair(Icons.AutoMirrored.Filled.Send, Color(0xFF007AFF))
            TxFilterType.RECEIVE -> Pair(Icons.AutoMirrored.Filled.Send, Color(0xFF007AFF))
        }
    }

    val availableTypes = if (viewModel.isMinter) {
        TxFilterType.entries
    } else {
        TxFilterType.entries.filter { it != TxFilterType.MINT }
    }

    Box(modifier = Modifier.width(110.dp)) {
        TextField(
            value = " ",
            onValueChange = {},
            readOnly = true,
            placeholder = { Text("Type") },
            leadingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 8.dp)) {
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
            colors = TextFieldDefaults.colors(
                focusedContainerColor = containerColor,
                unfocusedContainerColor = containerColor,
                disabledContainerColor = containerColor,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            shape = RoundedCornerShape(12.dp),
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
            availableTypes.forEach { type ->
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
    isMinter: Boolean,
    onSend: () -> Unit,
    onMint: () -> Unit,
    onBurn: () -> Unit,
    onSettings: () -> Unit
) {
    ActionButton("Send", Icons.AutoMirrored.Filled.Send, Color(0xFF007AFF), buttonSize, onClick = onSend)
    if (isMinter) ActionButton("Mint", Icons.Default.Add, Color.Green, buttonSize, onClick = onMint)
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
fun LocalPendingRow(action: PendingAction, onCopy: (String) -> Unit, nameResolver: (String) -> String?) {
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
                    val savedName = nameResolver(action.targetId)
                    val textToShow = savedName ?: action.targetId
                    val interactionSource = remember { MutableInteractionSource() }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) { onCopy(action.targetId) }
                    ) {
                        Text("To ", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        FrontEllipsizedText(
                            text = textToShow,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            Text("$prefix DM ${action.amount}", fontWeight = FontWeight.Bold, color = color.copy(alpha = 0.6f))
        }
    }
}

@Composable
fun TransactionRow(item: DisplayTransaction, myId: String, isPending: Boolean, onCopy: (String) -> Unit, nameResolver: (String) -> String?) {
    val tx = item.tx
    val isMe = tx.author() == myId
    val isGenesis = tx is GenesisTransaction
    val isIncoming = !isMe && tx !is BurnTransaction && !isGenesis
    if (isGenesis) return

    Card(
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
                    val rawId = if (tx is SendTransaction && !isIncoming) (tx.target ?: "?") else tx.author()
                    val savedName = nameResolver(rawId)
                    val textToShow = savedName ?: rawId
                    val interactionSource = remember { MutableInteractionSource() }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) { onCopy(rawId) }
                    ) {
                        Text(prefixLabel, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        FrontEllipsizedText(
                            text = textToShow,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            modifier = Modifier.fillMaxWidth()
                        )
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