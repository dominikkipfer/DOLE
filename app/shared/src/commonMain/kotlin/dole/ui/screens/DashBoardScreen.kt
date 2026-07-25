package dole.ui.screens

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.mohamedrejeb.calf.ui.button.AdaptiveIconButton
import com.mohamedrejeb.calf.ui.gesture.adaptiveClickable
import com.mohamedrejeb.calf.ui.sheet.AdaptiveBottomSheet
import com.mohamedrejeb.calf.ui.sheet.rememberAdaptiveSheetState
import dole.data.models.BurnTransaction
import dole.data.models.GenesisTransaction
import dole.data.models.MintTransaction
import dole.data.models.SendTransaction
import dole.data.models.StoredAccount
import dole.ui.components.AppBackHandler
import dole.ui.components.AppButton
import dole.ui.components.SegmentedTabs
import dole.ui.components.WalletCard
import dole.ui.components.AppTextField
import dole.ui.components.TransactionAmount
import dole.ui.components.TransactionPeerSubtitle
import dole.ui.components.EntryRow
import dole.ui.components.formatTransactionTimestamp
import dole.ui.layouts.BoardLayout
import dole.ui.modifiers.sharedCardEffect
import dole.ui.theme.DoleBlue
import dole.ui.theme.LocalIsDarkTheme
import dole.utils.rememberAppClipboard
import dole.viewmodel.DisplayTransaction
import dole.viewmodel.PeerOption
import dole.viewmodel.PendingAction
import dole.viewmodel.SortField
import dole.viewmodel.TxFilterType
import dole.viewmodel.WalletViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun DashboardScreen(
	viewModel: WalletViewModel,
	sharedTransitionScope: SharedTransitionScope,
	animatedVisibilityScope: AnimatedVisibilityScope
) {
	var showSendDialog by remember { mutableStateOf(false) }
	var showMintDialog by remember { mutableStateOf(false) }
	var showBurnDialog by remember { mutableStateOf(false) }

	fun handleBack() {
		when {
			showSendDialog -> showSendDialog = false
			showMintDialog -> showMintDialog = false
			showBurnDialog -> showBurnDialog = false
			else -> viewModel.logout()
		}
	}
	AppBackHandler { handleBack() }

	val clipboard = rememberAppClipboard()

	fun copyToClipboard(text: String) {
		clipboard.copy(text)
		viewModel.showUserMessage("Copied to clipboard")
	}

	val backgroundColor = MaterialTheme.colorScheme.background
	val isDarkTheme = LocalIsDarkTheme.current

	BoxWithConstraints(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
		val screenMaxWidth = maxWidth
		val screenMaxHeight = maxHeight
		val isWideLayout = screenMaxWidth > screenMaxHeight

		val activeId = viewModel.currentId
		var displayedAccount by remember { mutableStateOf<StoredAccount?>(null) }
		val foundAccount = viewModel.availableAccounts.find { it.id == activeId }
		if (foundAccount != null) displayedAccount = foundAccount
		val currentAccount = displayedAccount

		val density = LocalDensity.current
		val listState = rememberLazyListState()
		val coroutineScope = rememberCoroutineScope()

		val cardHeightExpanded = screenMaxWidth / 1.586f
		val horizontalPadding = 24.dp
		val slotWidth = (screenMaxWidth - (horizontalPadding * 2)) / 5
		val buttonSize = minOf(60.dp, slotWidth * 0.8f)

		val cardTopPad = 12.dp
		val balanceTopPad = 32.dp
		val buttonsTopPad = 72.dp
		val extraBottomPad = 12.dp

		val expandedHeaderHeight = cardTopPad + cardHeightExpanded + balanceTopPad + buttonsTopPad + buttonSize + 25.dp + extraBottomPad
		val minHeaderHeight = 150.dp
		val scrollLimitHeaderHeight = 124.dp
		val effectiveMaxHeaderHeight = maxOf(expandedHeaderHeight, minHeaderHeight)

		val maxHeaderPx = density.run { effectiveMaxHeaderHeight.toPx() }
		val minScrollHeaderPx = density.run { scrollLimitHeaderHeight.toPx() }
		val scrollRange = maxHeaderPx - minScrollHeaderPx

		var isUserDragging by remember { mutableStateOf(false) }

		val isAtTop by remember {
			derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
		}

		if (!isWideLayout) {
			LaunchedEffect(listState, scrollRange) {
				snapshotFlow { listState.isScrollInProgress || isUserDragging }
					.collect { active ->
						if (active) return@collect
						delay(140.milliseconds)
						if (listState.isScrollInProgress || isUserDragging) return@collect
						if (listState.firstVisibleItemIndex != 0) return@collect
						val range = scrollRange.toInt()
						if (range <= 0) return@collect
						val offset = listState.firstVisibleItemScrollOffset
						if (offset in 1 until range) {
							listState.animateScrollToItem(0, if (offset < range / 2) 0 else range)
						}
					}
			}
		}

		val collapseFactor by remember(listState, scrollRange) {
			derivedStateOf {
				val firstIndex = listState.firstVisibleItemIndex
				if (firstIndex > 0) 1f else (listState.firstVisibleItemScrollOffset / scrollRange).coerceIn(0f, 1f)
			}
		}

		BoardLayout(
			leftPaneWeight = 0.45f,
			portraitOverlap = true,
			primaryContent = {
				if (isWideLayout) {
					val isCompactHeight = screenMaxHeight < 500.dp
					val spacerHeight = if (isCompactHeight) 12.dp else 32.dp
					val maxCardHeight = screenMaxHeight * 0.45f
					val maxCardWidthFromHeight = maxCardHeight * 1.586f

					val leftColumnAvailableWidth = (screenMaxWidth * 0.45f) - 64.dp
					val finalCardWidth = minOf(leftColumnAvailableWidth, maxCardWidthFromHeight)
					val wideSlotWidth = leftColumnAvailableWidth / 5
					val maxButtonHeight = screenMaxHeight * 0.15f
					val wideButtonSize = minOf(60.dp, wideSlotWidth * 0.85f, maxButtonHeight)

					Column(
						horizontalAlignment = Alignment.CenterHorizontally,
						verticalArrangement = Arrangement.Center,
						modifier = Modifier.padding(32.dp).width(finalCardWidth).verticalScroll(rememberScrollState())
					) {
						val interactionSource = remember { MutableInteractionSource() }
						val isHovered by interactionSource.collectIsHoveredAsState()
						val hoverDim by animateFloatAsState(
							targetValue = if (isHovered) (if (isDarkTheme) 0.4f else 0.15f) else 0f,
							label = "hoverDim"
						)

						Box(
							modifier = Modifier
								.width(finalCardWidth)
								.hoverable(interactionSource)
								.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
								.drawWithContent {
									drawContent()
									if (hoverDim > 0f) {
										drawRect(Color.Black, alpha = hoverDim, blendMode = BlendMode.SrcAtop)
									}
								}
						) {
							SharedWalletCard(
								currentAccount,
								viewModel.isCardConnected,
								sharedTransitionScope,
								animatedVisibilityScope,
								true,
								onIdClick = { viewModel.currentId?.let { copyToClipboard(it) } }
							)
						}
						Spacer(Modifier.height(spacerHeight))
						Column(horizontalAlignment = Alignment.CenterHorizontally) {
							Text(
								"Card Balance",
								style = MaterialTheme.typography.labelMedium,
								color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
							)
							Text(
								"DM ${viewModel.displayBalance}",
								style = if (isCompactHeight) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
								fontWeight = FontWeight.Bold,
								color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (viewModel.isBalancePending) 0.5f else 1.0f)
							)
						}
						Spacer(Modifier.height(spacerHeight))
						Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
							TransactionActionButtons(
								buttonSize = wideButtonSize,
								isMinter = viewModel.isMinter,
								onSend = { showSendDialog = true },
								onMint = { showMintDialog = true },
								onBurn = { showBurnDialog = true },
								onSettings = { viewModel.goToSettings() }
							)
							ActionButton(
								"Logout",
								Icons.Default.Close,
								MaterialTheme.colorScheme.onSurfaceVariant,
								wideButtonSize,
								onClick = { viewModel.logout() }
							)
						}
					}
				} else {
					val pathX = 1f - (1f - collapseFactor) * (1f - collapseFactor)
					val pathY = collapseFactor * collapseFactor
					val scrollOffsetPx = with(density) { listState.firstVisibleItemScrollOffset.toDp() }
					val dampenedParallaxY = if (listState.firstVisibleItemIndex == 0) -scrollOffsetPx * 0.3f else 0.dp

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
						val currentCardWidth = interpolateDp(screenMaxWidth, cardWidthCollapsed, collapseFactor)
						val currentCardX = interpolateDp((screenMaxWidth - screenMaxWidth) / 2, 16.dp, collapseFactor)
						val currentCardY = interpolateDp(cardTopPad, 8.dp, collapseFactor)
						val showFullId = collapseFactor < 0.5f

						val interactionSource = remember { MutableInteractionSource() }
						val isHovered by interactionSource.collectIsHoveredAsState()
						val hoverDim by animateFloatAsState(
							targetValue = if (isHovered) (if (isDarkTheme) 0.4f else 0.15f) else 0f,
							label = "hoverDim"
						)

						Box(
							modifier = Modifier
								.offset(x = currentCardX, y = currentCardY)
								.width(currentCardWidth)
								.zIndex(2f)
								.hoverable(interactionSource)
								.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
								.drawWithContent {
									drawContent()
									if (hoverDim > 0f) {
										drawRect(Color.Black, alpha = hoverDim, blendMode = BlendMode.SrcAtop)
									}
								}
						) {
							SharedWalletCard(
								account = currentAccount,
								isOnline = viewModel.isCardConnected,
								sharedTransitionScope = sharedTransitionScope,
								animatedVisibilityScope = animatedVisibilityScope,
								showFullId = showFullId,
								onClick = if (isAtTop) null else { { coroutineScope.launch { listState.animateScrollToItem(0) } } },
								onIdClick = { viewModel.currentId?.let { copyToClipboard(it) } }
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
								Text(
									"Card Balance",
									modifier = Modifier.offset(x = 2.dp),
									style = MaterialTheme.typography.labelMedium.copy(fontSize = currentLabelSize, lineHeight = currentLabelSize),
									color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
								)
								Text(
									"DM ${viewModel.displayBalance}",
									style = MaterialTheme.typography.headlineMedium.copy(fontSize = currentFontSize, fontWeight = FontWeight.Bold, lineHeight = currentFontSize),
									color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (viewModel.isBalancePending) 0.5f else 1.0f)
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
										onSend = { showSendDialog = true },
										onMint = { showMintDialog = true },
										onBurn = { showBurnDialog = true },
										onSettings = { viewModel.goToSettings() }
									)
									Spacer(modifier = Modifier.width(buttonSize))
								}
							}
						}

						val logoutStartX = screenMaxWidth - horizontalPadding - buttonSize
						val logoutEndX = screenMaxWidth - 56.dp
						val animationProgress = collapseFactor

						val currentLogoutX = interpolateDp(logoutStartX, logoutEndX, animationProgress)
						val currentLogoutY = interpolateDp(buttonsY, 28.dp, animationProgress)
						val currentLogoutSize = interpolateDp(buttonSize, 40.dp, animationProgress)
						val textAlpha = (1f - (animationProgress * 4)).coerceIn(0f, 1f)

						Box(modifier = Modifier.offset(x = currentLogoutX, y = currentLogoutY).width(currentLogoutSize).zIndex(3f)) {
							Column(horizontalAlignment = Alignment.CenterHorizontally) {
								Box(
									modifier = Modifier
										.size(currentLogoutSize)
										.shadow(2.dp, CircleShape)
										.clip(CircleShape)
										.background(MaterialTheme.colorScheme.surface)
										.adaptiveClickable(shape = CircleShape) { viewModel.logout() },
									contentAlignment = Alignment.Center
								) {
									Icon(
										Icons.Default.Close, "Logout",
										tint = MaterialTheme.colorScheme.onSurfaceVariant,
										modifier = Modifier.size(currentLogoutSize * 0.4f)
									)
								}
								if (textAlpha > 0.1f) {
									Spacer(Modifier.height(4.dp))
									Text(
										"Logout",
										style = MaterialTheme.typography.labelMedium.copy(fontSize = (12 * (buttonSize.value/60)).coerceAtLeast(10.0F).sp),
										color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.alpha(textAlpha),
										maxLines = 1
									)
								}
							}
						}
					}
				}
			},
			secondaryContent = {
				Box(
					modifier = Modifier.fillMaxSize().padding(horizontal = if (isWideLayout) 16.dp else 0.dp)
				) {
					val listSpacerHeight = if (isWideLayout) null else (effectiveMaxHeaderHeight - 8.dp)

					TransactionDashboardList(
						pendingActions = viewModel.pendingActions,
						unsyncedTxs = viewModel.getUnsyncedIncoming(),
						viewModel = viewModel,
						listState = listState,
						topPadding = listSpacerHeight,
						minHeaderHeight = scrollLimitHeaderHeight,
						headerScrollRange = effectiveMaxHeaderHeight - scrollLimitHeaderHeight,
						screenHeight = screenMaxHeight,
						isWideLayout = isWideLayout,
						onCopy = { copyToClipboard(it) },
						onDragActiveChange = { isUserDragging = it }
					)
				}
			}
		)

		DashboardDialogs(
			viewModel = viewModel,
			showSend = showSendDialog,
			showMint = showMintDialog,
			showBurn = showBurnDialog,
			screenHeight = screenMaxHeight,
			onDismissSend = { showSendDialog = false },
			onDismissMint = { showMintDialog = false },
			onDismissBurn = { showBurnDialog = false }
		)
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
	headerScrollRange: Dp,
	screenHeight: Dp,
	isWideLayout: Boolean,
	onCopy: (String) -> Unit,
	onDragActiveChange: (Boolean) -> Unit
) {
	val density = LocalDensity.current
	val coroutineScope = rememberCoroutineScope()
	var spacerHeight by remember { mutableStateOf(0.dp) }
	val itemSpacing = 8.dp
	val bottomPad = 16.dp

	var editingAction by remember { mutableStateOf<PendingAction?>(null) }

	val draggableState = rememberDraggableState { delta -> listState.dispatchRawDelta(-delta) }

	LaunchedEffect(listState, topPadding, screenHeight, minHeaderHeight, headerScrollRange, pendingActions.size, unsyncedTxs.size, viewModel.filteredHistory.size, isWideLayout) {
		if (isWideLayout || topPadding == null) {
			spacerHeight = 0.dp
			return@LaunchedEffect
		}

		snapshotFlow { listState.isScrollInProgress to listState.layoutInfo.totalItemsCount }
			.collect { (scrolling, totalItems) ->
				if (scrolling) return@collect
				if (listState.firstVisibleItemIndex != 0) return@collect

				val visibleItems = listState.layoutInfo.visibleItemsInfo
				if (visibleItems.isEmpty() || totalItems == 0) return@collect

				if (visibleItems.last().index < totalItems - 1) {
					if (spacerHeight != 0.dp) spacerHeight = 0.dp
					return@collect
				}

				val realItems = visibleItems.filter { it.key != "top-spacer" && it.key != "bottom-spacer" }
				if (realItems.isEmpty()) return@collect

				val itemSpacingPx = with(density) { itemSpacing.toPx() }
				val realContentPx = realItems.sumOf { it.size }.toFloat() + realItems.size * itemSpacingPx

				val screenHeightPx = with(density) { screenHeight.toPx() }
				val minHeaderPx = with(density) { minHeaderHeight.toPx() }
				val topSpacerPx = with(density) { topPadding.toPx() }
				val bottomPadPx = with(density) { bottomPad.toPx() }
				val scrollRangePx = with(density) { headerScrollRange.toPx() }

				val forCollapsePx = scrollRangePx + screenHeightPx - topSpacerPx - realContentPx
				val forFillPx = screenHeightPx - minHeaderPx - realContentPx - bottomPadPx
				val targetPx = maxOf(forCollapsePx, forFillPx, 0f)

				val newHeight = with(density) { targetPx.toDp() }
				if (abs(newHeight.value - spacerHeight.value) > 1f) spacerHeight = newHeight
			}
	}

	val contentPad = if (topPadding != null) PaddingValues(bottom = bottomPad) else PaddingValues(top = 0.dp, bottom = 32.dp)

	val sessionList = viewModel.sessionTransactions.distinctBy { it.tx.id }
	val searchList = remember(viewModel.filteredHistory) {
		viewModel.filteredHistory.distinctBy { it.tx.id }
	}
	val safeUnsyncedTxs = remember(unsyncedTxs) {
		unsyncedTxs.distinctBy { it.tx.id }
	}

	val myId = viewModel.currentId ?: ""
	val isSearchMode = viewModel.history.isSearchMode
	val hasPending = pendingActions.isNotEmpty() || safeUnsyncedTxs.isNotEmpty()

	LazyColumn(
		state = listState,
		modifier = Modifier
			.fillMaxSize()
			.draggable(
				state = draggableState,
				orientation = Orientation.Vertical,
				onDragStarted = { onDragActiveChange(true) },
				onDragStopped = { velocity ->
					onDragActiveChange(false)
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
		item(key = "top-spacer") {
			if (topPadding != null) Spacer(Modifier.height(topPadding))
		}

		item {
			SegmentedTabs(
				options = listOf("Recent", "History"),
				selectedIndex = if (isSearchMode) 1 else 0,
				onSelected = { viewModel.history.showAllTransactions(it == 1) },
				modifier = Modifier
					.fillMaxWidth()
					.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
					.height(44.dp)
			)
		}

		if (isSearchMode) {
			item(key = "filter-section") {
				Box(Modifier.animateItem().padding(horizontal = 16.dp)) {
					FilterSection(
						viewModel = viewModel,
						screenHeight = screenHeight,
						backgroundColor = Color.Transparent,
						inputBackgroundColor = MaterialTheme.colorScheme.surface
					)
				}
			}
			item(key = "sort-header") {
				Box(Modifier.animateItem()) {
					SortHeaderRow(
						sortField = viewModel.history.sortField,
						isAscending = viewModel.history.sortAscending,
						onSortType = { viewModel.history.cycleSort(
							SortField.TYPE) },
						onSortAmount = { viewModel.history.cycleSort(
							SortField.AMOUNT) }
					)
				}
			}
			if (searchList.isEmpty()) {
				item(key = "empty-history") { Box(Modifier.animateItem().fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text("No results found.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
			} else {
				items(searchList, key = { "search-${it.tx.id}" }) { item ->
					Box(Modifier.animateItem().padding(horizontal = 16.dp)) {
						TransactionRow(item, myId, isPending = item.isUnsynced, onCopy = onCopy)
					}
				}
			}
		} else {
			if (hasPending) {
				item(key = "pending-header") { Text("Pending", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 4.dp)) }
				items(pendingActions, key = { "pending-${it.id}" }) { action ->
					Box(Modifier.animateItem().padding(horizontal = 16.dp)) {
						LocalPendingRow(
							action = action,
							onCopy = onCopy,
							nameResolver = viewModel::getAccountName,
							isLocked = viewModel.processingActionId == action.id,
							onEdit = { editingAction = action },
							onDelete = {
								if (!viewModel.cancelPendingAction(action.id)) {
									viewModel.showUserMessage("Already being written to the card")
								}
							}
						)
					}
				}
				items(safeUnsyncedTxs, key = { "unsynced-${it.tx.id}" }) { item ->
					Box(Modifier.animateItem().padding(horizontal = 16.dp)) {
						TransactionRow(item, myId, isPending = true, onCopy = onCopy)
					}
				}
				item(key = "pending-spacer") { Spacer(Modifier.height(16.dp)) }
			}
			if (sessionList.isNotEmpty()) {
				items(sessionList, key = { "session-${it.tx.id}" }) { item ->
					Box(Modifier.animateItem().padding(horizontal = 16.dp)) {
						TransactionRow(item, myId, isPending = false, onCopy = onCopy)
					}
				}
			} else if (!hasPending) {
				item(key = "empty-recent") {
					Box(modifier = Modifier.animateItem().fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
						Column(horizontalAlignment = Alignment.CenterHorizontally) {
							Icon(Icons.Default.CheckCircle, "Done", tint = Color.Green, modifier = Modifier.size(64.dp).alpha(0.5f))
							Spacer(Modifier.height(16.dp))
							Text("Up To Date", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
						}
					}
				}
			}
		}

		if (spacerHeight > 0.dp) item(key = "bottom-spacer") { Spacer(Modifier.height(spacerHeight)) }
	}

	editingAction?.let { action ->
		val budget = viewModel.displayBalance + action.amount

		fun save(amount: Long, targetId: String?) {
			if (!viewModel.updatePendingAction(action.id, amount, targetId)) {
				viewModel.showUserMessage("Already being written to the card")
			}
			editingAction = null
		}

		if (action.type == "SEND") {
			SendOverlay(
				peers = viewModel.knownNetworkPeers,
				currentBalance = budget,
				ownId = myId,
				screenHeight = screenHeight,
				initialAmount = action.amount,
				initialReceiver = action.targetId,
				onDismiss = { editingAction = null },
				onConfirm = { targetId, amount -> save(amount, targetId) }
			)
		} else {
			MintBurnOverlay(
				title = action.type.capitalize(),
				maxBalance = if (action.type == "MINT") null else budget,
				initialAmount = action.amount,
				onDismiss = { editingAction = null },
				onConfirm = { amount -> save(amount, null) }
			)
		}
	}
}

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
		val interactionSource = remember { MutableInteractionSource() }
		val isHovered by interactionSource.collectIsHoveredAsState()
		val isDarkTheme = LocalIsDarkTheme.current
		val hoverDim by animateFloatAsState(
			targetValue = if (isHovered) (if (isDarkTheme) 0.4f else 0.15f) else 0f,
			animationSpec = tween(150),
			label = "Hover Dim"
		)

		WalletCard(
			account = account,
			isOnline = isOnline,
			showFullId = showFullId,
			onClick = onClick,
			onIdClick = onIdClick,
			modifier = Modifier
				.fillMaxWidth()
				.sharedCardEffect(
					sharedTransitionScope = sharedTransitionScope,
					animatedVisibilityScope = animatedVisibilityScope,
					key = "card-${account.id}",
					interactionSource = interactionSource,
					hoverDim = hoverDim
				)
		)
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
	val currentBalance = viewModel.displayBalance
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
	maxInputLimit: Long = Long.MAX_VALUE
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
			color = if (value.isEmpty()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
			modifier = Modifier.padding(end = 8.dp).offset(y = 4.dp)
		)

		Box(contentAlignment = Alignment.Center, modifier = Modifier.width(IntrinsicSize.Min)) {
			val textStyle = commonTextStyle.copy(
				color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
				textAlign = TextAlign.Start
			)

			val amountFocusRequester = remember { FocusRequester() }
			LaunchedEffect(Unit) { amountFocusRequester.requestFocus() }

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
				cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
				singleLine = true,
				modifier = Modifier
					.focusRequester(amountFocusRequester)
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
								color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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
	containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
	onPeerSelected: (String) -> Unit
) {
	var isDropdownExpanded by remember { mutableStateOf(false) }
	var textFieldSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

	val density = LocalDensity.current
	var inputBottomY by remember { mutableFloatStateOf(0f) }

	val filteredPeers = peers.filter { it.label.contains(value, ignoreCase = true) || it.id.contains(value) }

	Box(modifier = Modifier.fillMaxWidth()) {
		AppTextField(
			value = value,
			onValueChange = {
				onValueChange(it)
				isDropdownExpanded = true
			},
			placeholder = placeholder,
			leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
			trailingIcon = {
				Row {
					if (value.isNotEmpty()) {
						AdaptiveIconButton(
							onClick = { onValueChange(""); isDropdownExpanded = false },
							colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
						) {
							Icon(Icons.Default.Close, "Clear")
						}
					}
					AdaptiveIconButton(
						onClick = { isDropdownExpanded = !isDropdownExpanded },
						colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
					) {
						Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = "Dropdown")
					}
				}
			},
			containerColor = containerColor,
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
					.background(MaterialTheme.colorScheme.surface),
				properties = PopupProperties(focusable = false)
			) {
				filteredPeers.forEach { peer ->
					DropdownMenuItem(
						text = {
							Column {
								val displayName = if (peer.label.startsWith("User", ignoreCase = true)) peer.id else peer.label
								FrontEllipsizedText(text = displayName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernActionSheet(
	title: String,
	onDismissRequest: () -> Unit,
	confirmText: String,
	onConfirm: () -> Unit,
	isConfirmEnabled: Boolean,
	content: @Composable ColumnScope.() -> Unit
) {
	AppBackHandler { onDismissRequest() }

	val sheetState = rememberAdaptiveSheetState(skipPartiallyExpanded = false)
	LaunchedEffect(Unit) { sheetState.show() }

	AdaptiveBottomSheet(
		onDismissRequest = onDismissRequest,
		adaptiveSheetState = sheetState,
		containerColor = MaterialTheme.colorScheme.surface
	) {
		val focusManager = LocalFocusManager.current
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 24.dp)
				.padding(top = 8.dp, bottom = 24.dp)
				.pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
				.onPreviewKeyEvent { event ->
					if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
					when (event.key) {
						Key.Enter, Key.NumPadEnter -> {
							if (isConfirmEnabled) onConfirm()
							true
						}
						else -> false
					}
				}
		) {
			Box(modifier = Modifier.fillMaxWidth()) {
				Text(
					text = title,
					style = MaterialTheme.typography.titleLarge.copy(fontSize = 32.sp),
					fontWeight = FontWeight.Bold,
					color = MaterialTheme.colorScheme.onSurface,
					modifier = Modifier.align(Alignment.CenterStart)
				)

				AdaptiveIconButton(
					onClick = onDismissRequest,
					modifier = Modifier.align(Alignment.CenterEnd).size(36.dp),
					colors = IconButtonDefaults.iconButtonColors(
						containerColor = MaterialTheme.colorScheme.surfaceVariant,
						contentColor = MaterialTheme.colorScheme.onSurfaceVariant
					)
				) {
					Icon(
						Icons.Default.Close,
						"Close",
						modifier = Modifier.size(20.dp)
					)
				}
			}

			Spacer(Modifier.height(32.dp))

			content()

			Spacer(Modifier.height(32.dp))

			AppButton(
				text = confirmText,
				onClick = { if (isConfirmEnabled) onConfirm() },
				modifier = Modifier.fillMaxWidth().height(56.dp),
				backgroundColor = if (isConfirmEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
				textColor = if (isConfirmEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
			)
		}
	}
}

@Composable
fun SendOverlay(
	peers: List<PeerOption>,
	currentBalance: Long,
	ownId: String,
	screenHeight: Dp,
	initialAmount: Long? = null,
	initialReceiver: String? = null,
	onDismiss: () -> Unit,
	onConfirm: (String, Long) -> Unit
) {
	var amountText by remember { mutableStateOf(initialAmount?.toString() ?: "") }
	var receiverInput by remember { mutableStateOf(initialReceiver ?: "") }

	val amountLong = amountText.toLongOrNull()

	val resolvedTargetId = remember(receiverInput, peers) {
		val match = peers.find { it.label.equals(receiverInput, ignoreCase = true) || it.id == receiverInput }
		match?.id ?: receiverInput.takeIf { it.isNotBlank() }
	}

	val isAmountValid = amountLong != null && amountLong > 0 && amountLong <= currentBalance
	val isSelf = resolvedTargetId == ownId
	val isReceiverValid = resolvedTargetId != null

	ModernActionSheet(
		title = "Send",
		onDismissRequest = onDismiss,
		confirmText = "Send",
		onConfirm = {
			if (resolvedTargetId != null && amountLong != null && !isSelf) onConfirm(resolvedTargetId, amountLong)
		},
		isConfirmEnabled = isAmountValid && isReceiverValid && !isSelf
	) {
		CleanAmountInput(
			value = amountText,
			onValueChange = { amountText = it },
			isError = amountLong != null && amountLong > currentBalance,
			maxInputLimit = currentBalance
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
fun MintBurnOverlay(
	title: String,
	maxBalance: Long? = null,
	initialAmount: Long? = null,
	onDismiss: () -> Unit,
	onConfirm: (Long) -> Unit
) {
	var amountText by remember { mutableStateOf(initialAmount?.toString() ?: "") }
	val amountLong = amountText.toLongOrNull()

	val isValid = if (maxBalance == null) {
		(amountLong != null && amountLong > 0)
	} else {
		(amountLong != null && amountLong > 0 && amountLong <= maxBalance)
	}

	val actionText = if (title == "Mint") "Mint" else "Burn"

	val limit = if (title == "Mint") {
		Long.MAX_VALUE
	} else {
		maxBalance?: Long.MAX_VALUE
	}

	ModernActionSheet(
		title = title,
		onDismissRequest = onDismiss,
		confirmText = actionText,
		onConfirm = { amountLong?.let(onConfirm) },
		isConfirmEnabled = isValid
	) {
		CleanAmountInput(
			value = amountText,
			onValueChange = { amountText = it },
			isError = maxBalance != null && amountLong != null && amountLong > maxBalance,
			maxInputLimit = limit
		)
	}
}

@Composable
fun FilterSection(viewModel: WalletViewModel, screenHeight: Dp, backgroundColor: Color = MaterialTheme.colorScheme.surface, inputBackgroundColor: Color) {
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
			val selection = viewModel.history.types
			val hasMint = selection.contains(
				TxFilterType.MINT)
			val hasBurn = selection.contains(
				TxFilterType.BURN)
			val hasSend = selection.contains(
				TxFilterType.SEND)
			val hasReceive = selection.contains(
				TxFilterType.RECEIVE)

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

				val relevantIds = remember(viewModel.filteredHistory, viewModel.history.types, viewModel.currentId) {
					val types = viewModel.history.types
					val onlySend = types.contains(
						TxFilterType.SEND) && !types.contains(
						TxFilterType.RECEIVE)
					val onlyReceive = types.contains(
						TxFilterType.RECEIVE) && !types.contains(
						TxFilterType.SEND)

					viewModel.filteredHistory.mapNotNull { item ->
						val tx = item.tx
						val myId = viewModel.currentId
						val isMe = tx.author == myId

						if (onlySend) {
							if (isMe && tx is SendTransaction) tx.target else null
						} else if (onlyReceive) {
							if (!isMe && tx is SendTransaction) tx.author else null
						} else {
							if (isMe && tx is SendTransaction) tx.target
							else if (!isMe) tx.author
							else null
						}
					}.toSet()
				}

				val relevantPeers = viewModel.knownNetworkPeers.filter { relevantIds.contains(it.id) }

				Box(Modifier.weight(1f)) {
					UnifiedPeerInput(
						value = viewModel.history.peerQuery,
						onValueChange = { viewModel.history.peerQuery = it },
						peers = relevantPeers,
						placeholder = label,
						screenHeight = screenHeight,
						containerColor = inputBackgroundColor,
						onPeerSelected = { viewModel.history.peerQuery = it }
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
	val contentColor = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
	val icon = when {
		!isActive -> Icons.Default.UnfoldMore
		isAscending -> Icons.Default.ArrowUpward
		else -> Icons.Default.ArrowDownward
	}

	Row(
		modifier = Modifier
			.clip(RoundedCornerShape(4.dp))
			.adaptiveClickable(shape = RoundedCornerShape(4.dp), onClick = onClick)
			.padding(vertical = 4.dp, horizontal = 4.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Text(label, style = MaterialTheme.typography.labelMedium, color = contentColor)
		Spacer(Modifier.width(4.dp))
		Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
	}
}

@Composable
fun TypeFilterDropdown(viewModel: WalletViewModel, containerColor: Color = MaterialTheme.colorScheme.surfaceVariant) {
	var isExpanded by remember { mutableStateOf(false) }
	val selectedTypes = viewModel.history.types
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
		AppTextField(
			value = " ",
			onValueChange = {},
			readOnly = true,
			placeholder = "Type",
			leadingIcon = {
				Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 8.dp)) {
					if (selectedTypes.isEmpty()) {
						Icon(Icons.Default.FilterList, "All", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
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
			containerColor = containerColor,
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

		DropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }, modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
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
							Text(type.name.lowercase().capitalize(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
						}
					},
					trailingIcon = {
						Checkbox(
							checked = viewModel.history.types.contains(type),
							onCheckedChange = { viewModel.history.toggleType(type) }
						)
					},
					onClick = { viewModel.history.toggleType(type) }
				)
			}
		}
	}
}

fun interpolateDp(start: Dp, stop: Dp, fraction: Float): Dp = Dp(start.value + (stop.value - start.value) * fraction)
fun String.capitalize(): String = this.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

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
	ActionButton("Send", Icons.AutoMirrored.Filled.Send, Color(0xFF007AFF), buttonSize, onSend)
	if (isMinter) ActionButton("Mint", Icons.Default.Add, Color.Green, buttonSize, onMint)
	ActionButton("Burn", Icons.Default.Remove, Color.Red, buttonSize, onBurn)
	ActionButton("Settings", Icons.Default.Settings, MaterialTheme.colorScheme.onSurfaceVariant, buttonSize, onSettings)
}

@Composable
private fun ActionButton(
	text: String,
	icon: ImageVector,
	color: Color,
	size: Dp,
	onClick: () -> Unit
) {
	Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(IntrinsicSize.Min)) {
		Box(
			modifier = Modifier
				.size(size)
				.shadow(2.dp, CircleShape)
				.clip(CircleShape)
				.background(MaterialTheme.colorScheme.surface)
				.adaptiveClickable(shape = CircleShape, onClick = onClick),
			contentAlignment = Alignment.Center
		) {
			Icon(icon, contentDescription = text, tint = color, modifier = Modifier.size(size * 0.4f))
		}
		Spacer(Modifier.height(4.dp))
		val fontSize = (12 * (size.value / 60)).coerceAtLeast(10f).sp
		Text(text, style = MaterialTheme.typography.labelMedium.copy(fontSize = fontSize), color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
	}
}

@Composable
fun LocalPendingRow(
	action: PendingAction,
	onCopy: (String) -> Unit,
	nameResolver: (String) -> String?,
	isLocked: Boolean = false,
	onEdit: (() -> Unit)? = null,
	onDelete: (() -> Unit)? = null
) {
	val title = when (action.type) {
		"SEND" -> "Send"
		"MINT" -> "Mint"
		"BURN" -> "Burn"
		else -> "Processing"
	}
	val isIncoming = action.type == "MINT"
    val prefix = if (isIncoming) "+" else "-"
	val icon = when (action.type) {
		"MINT" -> Icons.Default.Add
		"BURN" -> Icons.Default.Remove
		else -> Icons.AutoMirrored.Filled.Send
	}

	val row: @Composable () -> Unit = {
		EntryRow(
			title = title,
			icon = icon,
			iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
			subtitle = action.targetId?.takeIf { action.type == "SEND" }?.let { target ->
				{
					TransactionPeerSubtitle(
						id = nameResolver(target) ?: target,
						prefix = "To ",
						onClick = { onCopy(target) }
					)
				}
			},
			trailing = { TransactionAmount("$prefix ${action.amount}", MaterialTheme.colorScheme.onSurfaceVariant) }
		)
	}

	if (isLocked || onEdit == null || onDelete == null) {
		row()
	} else {
		SwipeablePendingRow(onEdit = onEdit, onDelete = onDelete, content = row)
	}
}

@Composable
private fun SwipeablePendingRow(
	onEdit: () -> Unit,
	onDelete: () -> Unit,
	content: @Composable () -> Unit
) {
	val density = LocalDensity.current
	val triggerPx = with(density) { 96.dp.toPx() }
	val offsetX = remember { Animatable(0f) }
	val scope = rememberCoroutineScope()

	fun settle() {
		scope.launch {
			val released = offsetX.value
			offsetX.animateTo(0f, tween(200))
			if (released <= -triggerPx) onDelete() else if (released >= triggerPx) onEdit()
		}
	}

	val offset = offsetX.value
	val progress = (abs(offset) / triggerPx).coerceIn(0f, 1f)
	val isDelete = offset < 0f
	val actionColor = if (isDelete) MaterialTheme.colorScheme.error else DoleBlue
	val surface = MaterialTheme.colorScheme.surface

	Box(Modifier.fillMaxWidth()) {
		if (progress > 0f) {
			Box(
				modifier = Modifier
					.matchParentSize()
					.clip(RoundedCornerShape(12.dp))
					.background(actionColor.copy(alpha = progress))
			) {
				Icon(
					imageVector = if (isDelete) Icons.Default.Delete else Icons.Default.Edit,
					contentDescription = if (isDelete) "Delete" else "Edit",
					tint = lerp(actionColor, surface, progress),
					modifier = Modifier
						.align(if (isDelete) Alignment.CenterEnd else Alignment.CenterStart)
						.padding(horizontal = 28.dp)
						.size(22.dp)
				)
			}
		}

		Box(
			modifier = Modifier
				.offset { IntOffset(offset.roundToInt(), 0) }
				.draggable(
					orientation = Orientation.Horizontal,
					state = rememberDraggableState { delta ->
						scope.launch {
							val limit = triggerPx * 1.4f
							offsetX.snapTo((offsetX.value + delta).coerceIn(-limit, limit))
						}
					},
					onDragStopped = { settle() }
				)
		) {
			content()
		}
	}
}

@Composable
fun TransactionRow(item: DisplayTransaction, myId: String, isPending: Boolean, onCopy: (String) -> Unit) {
	val tx = item.tx
	if (tx is GenesisTransaction) return

	val isMe = tx.author == myId
	val isIncoming = !isMe && tx !is BurnTransaction

	val icon = when {
		isIncoming -> Icons.AutoMirrored.Filled.Send
		tx is BurnTransaction -> Icons.Default.Remove
		tx is MintTransaction -> Icons.Default.Add
		else -> Icons.AutoMirrored.Filled.Send
	}
	val iconTint = when {
		isPending -> MaterialTheme.colorScheme.onSurfaceVariant
		tx is BurnTransaction -> Color.Red
		tx is MintTransaction -> Color.Green
		else -> DoleBlue
	}
	val title = if (isPending && isIncoming) "Receive" else when (tx) {
		is MintTransaction -> if (isPending) "Mint" else "Minted"
		is BurnTransaction -> if (isPending) "Burn" else "Burned"
		is SendTransaction -> if (isIncoming) "Received" else (if (isPending) "Send" else "Sent")
		else -> "Transaction"
	}

	val hasPeer = tx !is MintTransaction && tx !is BurnTransaction
	val rawId = if (tx is SendTransaction && !isIncoming) tx.target else tx.author

	val isExpense = tx is BurnTransaction || (tx is SendTransaction && !isIncoming)
	val amountColor = if (isPending) MaterialTheme.colorScheme.onSurfaceVariant
		else if (isExpense) Color.Red else Color.Green
	val amountText = if (item.delta > 0) "${if (isExpense) "-" else "+"} ${item.delta}" else "…"

    EntryRow(
        title = title,
        icon = icon,
        iconTint = iconTint,
        iconRotated = isIncoming,
        timestamp = formatTransactionTimestamp(
            tx.timestamp
        ),
        subtitle = if (hasPeer) ({
            TransactionPeerSubtitle(
                id = rawId,
                prefix = if (tx is SendTransaction && !isIncoming) "To " else "From ",
                onClick = {
                    onCopy(
                        rawId
                    )
                }
            )
        }) else null,
        trailing = {
            TransactionAmount(
                amountText,
                amountColor
            )
        }
    )
}
