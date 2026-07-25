package dole.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.times
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.mohamedrejeb.calf.ui.ExperimentalCalfUiApi
import com.mohamedrejeb.calf.ui.button.AdaptiveIconButton
import com.mohamedrejeb.calf.ui.navigation.AdaptiveNavigationBar
import com.mohamedrejeb.calf.ui.navigation.AdaptiveScaffold
import com.mohamedrejeb.calf.ui.navigation.UIKitUITabBarItem
import com.mohamedrejeb.calf.ui.uikit.UIKitImage
import dole.data.models.BurnTransaction
import dole.data.models.GenesisTransaction
import dole.data.models.MintTransaction
import dole.data.models.SendTransaction
import dole.data.models.StoredAccount
import dole.ui.components.AppTextField
import dole.ui.components.DoleLogo
import dole.ui.components.EdgeLabelPlacement
import dole.ui.components.TransactionAmount
import dole.ui.components.TransactionPeerPairSubtitle
import dole.ui.components.TransactionPeerSubtitle
import dole.ui.components.TransactionPlainSubtitle
import dole.ui.components.EntryRow
import dole.ui.components.WalletCard
import dole.ui.components.formatTransactionTimestamp
import dole.ui.modifiers.sharedCardEffect
import dole.utils.rememberAppClipboard
import dole.ui.theme.DoleBlue
import dole.ui.theme.LocalIsDarkTheme
import dole.viewmodel.DisplayTransaction
import dole.viewmodel.NetworkAccountSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCalfUiApi::class)
@Composable
fun HomeScreen(
    accounts: List<StoredAccount>,
    onAccountClick: (StoredAccount) -> Unit,
    physicallyConnectedAccount: StoredAccount?,
    isOverlayVisible: Boolean,
    initialSelectedAccountId: String? = null,
    globalHistory: List<DisplayTransaction>,
    networkAccounts: List<NetworkAccountSummary>,
    nameResolver: (String) -> String?,
    onNotify: (String) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onLogoTap: () -> Unit = {},
    developerPane: (@Composable () -> Unit)? = null
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val hasDeveloperTab = developerPane != null

    LaunchedEffect(hasDeveloperTab) {
        if (!hasDeveloperTab && selectedTab > 1) selectedTab = 0
    }

    AdaptiveScaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            AdaptiveNavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                iosItems = buildList {
                    add(UIKitUITabBarItem("Wallet", UIKitImage.SystemName("creditcard.fill")))
                    add(UIKitUITabBarItem("History", UIKitImage.SystemName("clock.arrow.circlepath")))
                    if (hasDeveloperTab) add(UIKitUITabBarItem("Developer", UIKitImage.SystemName("hammer.fill")))
                },
                iosSelectedIndex = selectedTab,
                iosOnItemSelected = { selectedTab = it }
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.CreditCard, contentDescription = "Wallet") },
                    label = { Text("Wallet") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.History, contentDescription = "History") },
                    label = { Text("History") }
                )
                if (hasDeveloperTab) {
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.DeveloperMode, contentDescription = "Developer") },
                        label = { Text("Developer") }
                    )
                }
            }
        }
    ) { paddingValues ->
        AnimatedContent(
            targetState = selectedTab,
            label = "home_tab",
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            transitionSpec = {
                val direction = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(350)) { it / 4 * direction } + fadeIn(tween(350))) togetherWith
                        (slideOutHorizontally(tween(350)) { -it / 4 * direction } + fadeOut(tween(250)))
            }
        ) { tab ->
            when (tab) {
                0 -> WalletPane(
                    accounts = accounts,
                    onAccountClick = onAccountClick,
                    physicallyConnectedAccount = physicallyConnectedAccount,
                    isOverlayVisible = isOverlayVisible,
                    initialSelectedAccountId = initialSelectedAccountId,
                    onLogoTap = onLogoTap,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope
                )
                1 -> NetworkHistoryPane(
                    transactions = globalHistory,
                    accounts = networkAccounts,
                    nameResolver = nameResolver,
                    onNotify = onNotify
                )
                else -> developerPane?.invoke()
            }
        }
    }
}

@Composable
private fun WalletPane(
    accounts: List<StoredAccount>,
    onAccountClick: (StoredAccount) -> Unit,
    physicallyConnectedAccount: StoredAccount?,
    isOverlayVisible: Boolean,
    initialSelectedAccountId: String? = null,
    onLogoTap: () -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val onBackgroundColor = MaterialTheme.colorScheme.onBackground
    val emptyTextColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val isWideLayout = maxWidth > maxHeight
            val density = LocalDensity.current

            val availableWidth = maxWidth
            val availableHeight = maxHeight

            val maxCardHeight = if (isWideLayout) (availableHeight * 0.5f) else (availableHeight * 0.6f)
            val widthBasedMax = (availableWidth - 40.dp).coerceAtLeast(0.dp)
            val heightBasedMaxWidth = maxCardHeight * 1.586f
            val cardMaxWidth = min(1000.dp, min(widthBasedMax, heightBasedMaxWidth))
            val cardActualHeight = cardMaxWidth / 1.586f

            val verticalPadding = if (isWideLayout) 40.dp else 120.dp
            val verticalRange = (availableHeight - cardActualHeight - verticalPadding).coerceAtLeast(0.dp)
            val horizontalRange = (availableWidth - cardMaxWidth - 40.dp).coerceAtLeast(0.dp)

            val minSpacing = cardActualHeight * 0.2f
            val maxSpacing = if (isWideLayout) cardMaxWidth else cardActualHeight
            val itemCount = accounts.size
            val maxScrollIndex = (itemCount - 1).coerceAtLeast(0).toFloat()
            val totalSlack = if (isWideLayout) horizontalRange else verticalRange
            val spacing = if (itemCount > 1) (totalSlack / maxScrollIndex).coerceIn(minSpacing, maxSpacing) else minSpacing

            val isSparseLayout = if (isWideLayout) spacing >= cardMaxWidth else spacing >= cardActualHeight

            val initialScrollIndex = remember(accounts, initialSelectedAccountId) {
                val index = accounts.indexOfFirst { it.id == initialSelectedAccountId }
                if (index >= 0) index.toFloat() else 0f
            }

            val animatedScrollOffset = remember { Animatable(initialScrollIndex) }
            val coroutineScope = rememberCoroutineScope()
            var wheelSnapJob by remember { mutableStateOf<Job?>(null) }
            val focusRequester = remember { FocusRequester() }
            var isUserScrolling by remember { mutableStateOf(false) }
            var scrollIdleJob by remember { mutableStateOf<Job?>(null) }

            LaunchedEffect(Unit) { focusRequester.requestFocus() }

            LaunchedEffect(isSparseLayout) {
                if (isSparseLayout) {
                    wheelSnapJob?.cancel()
                    animatedScrollOffset.snapTo(0f)
                }
            }

            LaunchedEffect(isUserScrolling) {
                if (!isUserScrolling) {
                    val current = animatedScrollOffset.value
                    val target = current.roundToInt().toFloat().coerceIn(0f, maxScrollIndex)
                    animatedScrollOffset.animateTo(
                        targetValue = target,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                    )
                }
            }

            fun snapToRelative(delta: Int) {
                if (isSparseLayout) return
                val current = animatedScrollOffset.targetValue
                val target = (current + delta).roundToInt().toFloat().coerceIn(0f, maxScrollIndex)
                coroutineScope.launch {
                    isUserScrolling = true
                    scrollIdleJob?.cancel()
                    animatedScrollOffset.animateTo(
                        targetValue = target,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                    )
                    scrollIdleJob = coroutineScope.launch {
                        delay(150.milliseconds)
                        isUserScrolling = false
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onKeyEvent { event ->
                        event.type == KeyEventType.KeyDown && when (event.key) {
                            Key.DirectionDown, Key.DirectionRight -> { snapToRelative(1); true }
                            Key.DirectionUp, Key.DirectionLeft -> { snapToRelative(-1); true }
                            Key.Enter, Key.NumPadEnter -> {
                                val currentIndex = animatedScrollOffset.targetValue.roundToInt()
                                if (currentIndex in accounts.indices) onAccountClick(accounts[currentIndex])
                                true
                            }

                            else -> false
                        }
                    }
                    .draggable(
                        orientation = if (isWideLayout) Orientation.Horizontal else Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            if (isSparseLayout) return@rememberDraggableState
                            isUserScrolling = true
                            scrollIdleJob?.cancel()
                            scrollIdleJob = coroutineScope.launch {
                                delay(150.milliseconds)
                                isUserScrolling = false
                            }
                            val sensitivity = if(isWideLayout) 0.006f else 0.003f
                            val newOffset = animatedScrollOffset.value - (delta * sensitivity)
                            val constrainedOffset = newOffset.coerceIn(0f, maxScrollIndex)
                            coroutineScope.launch { animatedScrollOffset.snapTo(constrainedOffset) }
                        },
                        onDragStopped = { velocity ->
                            if (isSparseLayout) return@draggable
                            isUserScrolling = true
                            scrollIdleJob?.cancel()
                            val current = animatedScrollOffset.value
                            val target = (current + (velocity * -0.0005f)).roundToInt().toFloat().coerceIn(0f, maxScrollIndex)
                            coroutineScope.launch {
                                animatedScrollOffset.animateTo(
                                    targetValue = target,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                                scrollIdleJob = coroutineScope.launch {
                                    delay(150.milliseconds)
                                    isUserScrolling = false
                                }
                            }
                        }
                    )
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Scroll && event.changes.isNotEmpty()) {
                                    if (isSparseLayout) continue
                                    event.changes.forEach { it.consume() }

                                    isUserScrolling = true
                                    scrollIdleJob?.cancel()
                                    scrollIdleJob = coroutineScope.launch {
                                        delay(150.milliseconds)
                                        isUserScrolling = false
                                    }

                                    val scrollDelta = event.changes.last().scrollDelta
                                    val deltaAmount = if (isWideLayout) scrollDelta.x + scrollDelta.y else scrollDelta.y

                                    val current = animatedScrollOffset.value
                                    val wheelStep = when {
                                        deltaAmount > 0f -> 1f
                                        deltaAmount < 0f -> -1f
                                        else -> 0f
                                    }

                                    val atStart = current <= 0f
                                    val atEnd = current >= maxScrollIndex
                                    val isScrollingOutwardAtStart = atStart && wheelStep < 0f
                                    val isScrollingOutwardAtEnd = atEnd && wheelStep > 0f

                                    if (isScrollingOutwardAtStart || isScrollingOutwardAtEnd || wheelStep == 0f) continue

                                    val newOffset = (current + wheelStep).coerceIn(0f, maxScrollIndex)
                                    coroutineScope.launch { animatedScrollOffset.snapTo(newOffset) }

                                    wheelSnapJob?.cancel()
                                    wheelSnapJob = coroutineScope.launch {
                                        delay(100.milliseconds)
                                        val snapped = animatedScrollOffset.value.roundToInt().toFloat().coerceIn(0f, maxScrollIndex)
                                        animatedScrollOffset.animateTo(
                                            targetValue = snapped,
                                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                                        )
                                        scrollIdleJob?.cancel()
                                        scrollIdleJob = coroutineScope.launch {
                                            delay(150.milliseconds)
                                            isUserScrolling = false
                                        }
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val scrollValue = animatedScrollOffset.value
                val centerIndex = (itemCount - 1).coerceAtLeast(0) / 2f
                val visualScrollValue = if (isSparseLayout) centerIndex else scrollValue.coerceIn(0f, maxScrollIndex)
                val scrollProgress = if (maxScrollIndex > 0f) scrollValue / maxScrollIndex else 0.5f
                val globalYOffset = if (!isWideLayout && !isSparseLayout) (scrollProgress - 0.5f) * verticalRange else 0.dp
                val globalXOffset = if (isWideLayout && !isSparseLayout) (scrollProgress - 0.5f) * horizontalRange else 0.dp

                accounts.forEachIndexed { index, acc ->
                    key(acc.id) {
                        val isInserted = physicallyConnectedAccount?.id == acc.id
                        val delta = index - visualScrollValue

                        val cardExtent = if (isWideLayout) cardMaxWidth else cardActualHeight
                        val coverageRatio = (1f - (spacing / cardExtent)).coerceIn(0f, 1f)

                        val showEdgeLabels = if (abs(delta) <= 0.6f) {
                            false
                        } else {
                            val baseThreshold = if (isWideLayout) if (delta < 0f) 0.5f else 0.2f else 0.25f
                            val distanceFactor = (abs(delta) - 1f).coerceAtLeast(0f) * 0.014f
                            val individualThreshold = (baseThreshold + distanceFactor)
                            coverageRatio > individualThreshold
                        }

                        val edgePlacement = if (isWideLayout) {
                            if (delta < 0f) EdgeLabelPlacement.Left else EdgeLabelPlacement.Right
                        } else {
                            if (delta < 0f) EdgeLabelPlacement.Top else EdgeLabelPlacement.Bottom
                        }

                        if (delta > -20f && delta < 20f) {
                            val interactionSource = remember { MutableInteractionSource() }
                            val isHovered by interactionSource.collectIsHoveredAsState()
                            val isDarkTheme = LocalIsDarkTheme.current

                            val hoverDim by animateFloatAsState(
                                targetValue = if (isHovered) (if (isDarkTheme) 0.4f else 0.15f) else 0f,
                                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                label = "Hover Dim"
                            )

                            val zIndex = if (isHovered) 200f else 100f - abs(delta)
                            val scale = if (isSparseLayout) 1f else (1f - (abs(delta) * 0.05f)).coerceAtLeast(0.85f)
                            val alpha = 1f

                            val positioningModifier = if (isWideLayout) {
                                val localXShift = delta * spacing
                                val finalX = localXShift + globalXOffset
                                Modifier
                                    .zIndex(zIndex)
                                    .graphicsLayer {
                                        this.scaleX = scale
                                        this.scaleY = scale
                                        this.alpha = alpha
                                        this.cameraDistance = 30f * density.density
                                    }
                                    .offset { IntOffset(x = finalX.roundToPx(), y = 0) }
                            } else {
                                val localYShift = delta * spacing
                                val portraitTopOffset = 30.dp
                                val finalY = localYShift + globalYOffset + portraitTopOffset
                                Modifier
                                    .zIndex(zIndex)
                                    .graphicsLayer {
                                        this.scaleX = scale
                                        this.scaleY = scale
                                        this.alpha = alpha
                                        this.cameraDistance = 20f * density.density
                                    }
                                    .offset { IntOffset(x = 0, y = finalY.roundToPx()) }
                            }

                            Box(modifier = Modifier.widthIn(max = cardMaxWidth).fillMaxWidth().then(positioningModifier)) {
                                WalletCard(
                                    account = acc,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .sharedCardEffect(
                                            sharedTransitionScope = sharedTransitionScope,
                                            animatedVisibilityScope = animatedVisibilityScope,
                                            key = "card-${acc.id}",
                                            interactionSource = interactionSource,
                                            hoverDim = hoverDim
                                        ),
                                    isOnline = isInserted,
                                    showFullId = false,
                                    showInlineLabels = !showEdgeLabels,
                                    showEdgeLabels = showEdgeLabels,
                                    edgeLabelPlacement = edgePlacement,
                                    rotateEdgeLabels = isWideLayout,
                                    onClick = { onAccountClick(acc) }
                                )
                            }
                        }
                    }
                }
            }

            if (accounts.isEmpty() && !isOverlayVisible) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Hold your card to your device.", color = emptyTextColor)
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(130.dp)
                .background(
                    Brush.verticalGradient(0.0f to backgroundColor, 0.65f to backgroundColor, 1.0f to Color.Transparent)
                )
                .padding(top = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DoleLogo(contentDescription = "DOLE", modifier = Modifier.size(32.dp), onClick = onLogoTap)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Wallet",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = onBackgroundColor
                )
            }
        }
    }
}

@Composable
private fun NetworkHistoryPane(
    transactions: List<DisplayTransaction>,
    accounts: List<NetworkAccountSummary>,
    nameResolver: (String) -> String?,
    onNotify: (String) -> Unit
) {
    val visibleTxs = remember(transactions) { transactions.filter { it.tx !is GenesisTransaction } }
    val clipboard = rememberAppClipboard()

    fun copyId(id: String) {
        clipboard.copy(id)
        onNotify("Copied to clipboard")
    }

    fun displayName(id: String): String = nameResolver(id) ?: "...${id.takeLast(6)}"

    var query by remember { mutableStateOf("") }
    var selectedAccountId by remember { mutableStateOf<String?>(null) }

    val selectedAccount = selectedAccountId?.let { id -> accounts.find { it.id == id } }

    val shownTxs = remember(visibleTxs, selectedAccount) {
        val sel = selectedAccount ?: return@remember visibleTxs
        visibleTxs.filter { it.tx.author == sel.id || (it.tx as? SendTransaction)?.target == sel.id }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item(key = "header") {
            Text(
                text = "History",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 48.dp, bottom = 16.dp)
            )
        }

        item(key = "search") {
            Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                AccountSearchField(
                    query = query,
                    onQueryChange = {
                        query = it
                        selectedAccountId = null
                    },
                    accounts = accounts,
                    displayName = ::displayName,
                    onAccountSelected = { account ->
                        selectedAccountId = account.id
                        query = displayName(account.id)
                    }
                )
            }
        }

        if (selectedAccount != null) {
            item(key = "account-detail") {
                Box(Modifier.animateItem().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    AccountDetailCard(account = selectedAccount, onCopy = ::copyId)
                }
            }
        }

        item(key = "tx-title") {
            Text(
                text = if (selectedAccount != null) "Transactions" else "All Transactions",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.animateItem().padding(horizontal = 24.dp, vertical = 4.dp)
            )
        }

        if (shownTxs.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.animateItem().fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No transactions on the network yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(shownTxs, key = { it.tx.id }) { item ->
                Box(Modifier.animateItem().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    GlobalTransactionRow(item = item, onCopy = ::copyId)
                }
            }
        }
    }
}

@Composable
private fun AccountSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    accounts: List<NetworkAccountSummary>,
    displayName: (String) -> String,
    onAccountSelected: (NetworkAccountSummary) -> Unit
) {
    var isDropdownExpanded by remember { mutableStateOf(false) }
    var fieldSize by remember { mutableStateOf(Size.Zero) }

    val matches = remember(query, accounts) {
        if (query.isBlank()) {
            accounts
        } else {
            accounts.filter { account ->
                displayName(account.id).contains(query, ignoreCase = true) || account.id.contains(query, ignoreCase = true)
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        AppTextField(
            value = query,
            onValueChange = {
                onQueryChange(it)
                isDropdownExpanded = true
            },
            placeholder = "Search account (name or ID)",
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    AdaptiveIconButton(
                        onClick = { onQueryChange(""); isDropdownExpanded = false },
                        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Icon(Icons.Default.Close, "Clear")
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().onGloballyPositioned { fieldSize = it.size.toSize() },
            singleLine = true
        )

        if (isDropdownExpanded && query.isNotBlank() && matches.isNotEmpty()) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = { isDropdownExpanded = false },
                modifier = Modifier
                    .width(with(LocalDensity.current) { fieldSize.width.toDp() })
                    .heightIn(max = 240.dp)
                    .background(MaterialTheme.colorScheme.surface),
                properties = PopupProperties(focusable = false)
            ) {
                matches.forEach { account ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                FrontEllipsizedText(
                                    text = account.id,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                account.name?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        onClick = {
                            onAccountSelected(account)
                            isDropdownExpanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountDetailCard(account: NetworkAccountSummary, onCopy: (String) -> Unit) {
    EntryRow(
        title = account.id,
        titleEllipsized = true,
        onTitleClick = { onCopy(account.id) },
        subtitle = account.name?.let { name -> { TransactionPlainSubtitle(name) } },
        trailing = { TransactionAmount("${account.balance}", MaterialTheme.colorScheme.onSurface) }
    )
}

@Composable
private fun GlobalTransactionRow(item: DisplayTransaction, onCopy: (String) -> Unit) {
    val tx = item.tx
    val (icon, iconTint, title) = when (tx) {
        is MintTransaction -> Triple(Icons.Default.Add, Color.Green, "Mint")
        is BurnTransaction -> Triple(Icons.Default.Remove, Color.Red, "Burn")
        is SendTransaction -> Triple(Icons.AutoMirrored.Filled.Send, DoleBlue, "Send")
        else -> return
    }

    EntryRow(
        title = title,
        icon = icon,
        iconTint = iconTint,
        timestamp = formatTransactionTimestamp(tx.timestamp),
        subtitle = when (tx) {
            is SendTransaction -> ({
                TransactionPeerPairSubtitle(
                    fromId = tx.author,
                    toId = tx.target,
                    onFromClick = { onCopy(tx.author) },
                    onToClick = { onCopy(tx.target) }
                )
            })
            else -> ({ TransactionPeerSubtitle(id = tx.author, onClick = { onCopy(tx.author) }) })
        },
        trailing = { TransactionAmount("${item.delta}", iconTint) }
    )
}
