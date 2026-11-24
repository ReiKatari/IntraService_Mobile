package com.lantaservice.intra.ui.tasks

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.lantaservice.intra.data.model.TicketItem
import com.lantaservice.intra.data.model.UserSession
import com.lantaservice.intra.data.network.IntraConnectionManager
import com.lantaservice.intra.ui.components.IntraHeaderBanner
import com.lantaservice.intra.ui.components.PriorityDotAndLabel
import com.lantaservice.intra.ui.components.ShimmerTicketCard
import com.lantaservice.intra.ui.components.StatusPill
import com.lantaservice.intra.ui.theme.IntraColors
import com.lantaservice.intra.util.DateAndTextFormatter
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    session: UserSession,
    viewModel: TaskListViewModel,
    hasAvailableUpdate: Boolean = false,
    onOpenUpdateDialog: () -> Unit = {},
    onOpenTicket: (Int) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var showLogoutConfirm by remember { mutableStateOf(false) }

    // Automatic 1-minute refresh while app is in foreground
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(60_000L)
                viewModel.silentAutoRefresh()
            }
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("Выйти из аккаунта?") },
            text = { Text("Вы будете перенаправлены на экран входа IntraService Mobile.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirm = false
                        viewModel.logout()
                    }
                ) {
                    Text("Выйти", color = IntraColors.ErrorRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IntraColors.BackgroundIce)
            .navigationBarsPadding()
    ) {
        IntraHeaderBanner(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(contentAlignment = Alignment.BottomEnd) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.24f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = DateAndTextFormatter.initialsOf(session.displayName),
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(IntraColors.EmeraldSuccess)
                                    .padding(2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = session.displayName,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = session.position?.takeIf { it.isNotBlank() } ?: session.login,
                                    color = Color.White.copy(alpha = 0.88f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = Color.White.copy(alpha = 0.18f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.LocationOn,
                                            contentDescription = null,
                                            tint = Color(0xFF9AF5C6),
                                            modifier = Modifier.size(11.dp)
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text(
                                            text = "GPS",
                                            color = Color.White,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row {
                        if (hasAvailableUpdate) {
                            IconButton(
                                onClick = onOpenUpdateDialog,
                                colors = IconButtonDefaults.iconButtonColors(contentColor = Color(0xFF9AF5C6))
                            ) {
                                Icon(Icons.Rounded.SystemUpdate, contentDescription = "Обновление")
                            }
                        }
                        IconButton(
                            onClick = { viewModel.loadTickets(isPullToRefresh = true) },
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Обновить")
                        }
                        IconButton(
                            onClick = { showLogoutConfirm = true },
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = "Выйти")
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(IntraColors.SurfaceWhite)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Поиск по номеру, названию, сервису...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = IntraColors.BrandPrimary
                    )
                },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Очистить")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = IntraColors.BrandPrimary,
                    unfocusedBorderColor = IntraColors.SubtleBorder,
                    focusedContainerColor = IntraColors.SurfaceMuted,
                    unfocusedContainerColor = IntraColors.SurfaceMuted
                )
            )

            TicketTabSwitcher(
                selectedTab = state.selectedTab,
                countWithoutRepaired = state.countWithoutRepaired,
                countHadRepaired = state.countHadRepaired,
                onSelectTab = viewModel::selectTab
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(IntraColors.EmeraldSuccess)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Автообновление: 1 мин",
                        style = MaterialTheme.typography.labelSmall,
                        color = IntraColors.TextTertiary
                    )
                }
                Text(
                    text = when {
                        state.isAutoRefreshing -> "Синхронизация..."
                        state.lastUpdatedTime.isNotBlank() -> "Обновлено в ${state.lastUpdatedTime}"
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.isAutoRefreshing) IntraColors.BrandPrimary else IntraColors.TextTertiary
                )
            }
        }

        AnimatedVisibility(visible = state.isResolvingHistory || state.isAutoRefreshing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = IntraColors.BrandCyan,
                trackColor = IntraColors.SubtleBorder
            )
        }

        AnimatedVisibility(visible = state.errorMessage != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                color = IntraColors.ErrorTint
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = IntraColors.ErrorRed,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = state.errorMessage.orEmpty(),
                                color = IntraColors.ErrorRed,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(onClick = { viewModel.loadTickets(isPullToRefresh = false) }) {
                            Text("Повторить")
                        }
                    }
                    if (state.showVpnSettingsHint) {
                        TextButton(onClick = { IntraConnectionManager.openVpnSettings(context) }) {
                            Text("Открыть настройки VPN", color = IntraColors.ErrorRed)
                        }
                    }
                }
            }
        }

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.loadTickets(isPullToRefresh = true) },
            modifier = Modifier.fillMaxSize()
        ) {
            when {
                state.isLoading -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(5) { ShimmerTicketCard() }
                    }
                }
                state.visibleTickets.isEmpty() -> {
                    EmptyTicketsState(
                        selectedTab = state.selectedTab,
                        hasSearchQuery = state.searchQuery.isNotBlank(),
                        onRefresh = { viewModel.loadTickets(isPullToRefresh = false) }
                    )
                }
                else -> {
                    AnimatedContent(
                        targetState = state.selectedTab,
                        transitionSpec = {
                            fadeIn(tweenSpring()) togetherWith fadeOut(tweenSpring())
                        },
                        label = "tab_list_transition"
                    ) { tab ->
                        val itemsForTab = if (tab == TicketTab.WITHOUT_REPAIRED) {
                            state.withoutRepairedTickets
                        } else {
                            state.hadRepairedTickets
                        }
                        if (itemsForTab.isEmpty()) {
                            EmptyTicketsState(
                                selectedTab = tab,
                                hasSearchQuery = state.searchQuery.isNotBlank(),
                                onRefresh = { viewModel.loadTickets(isPullToRefresh = false) }
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(
                                    items = itemsForTab,
                                    key = { it.id }
                                ) { ticket ->
                                    TicketCard(
                                        ticket = ticket,
                                        onClick = { onOpenTicket(ticket.id) },
                                        modifier = Modifier.animateItem()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun <T> tweenSpring() = spring<T>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium
)

@Composable
private fun TicketTabSwitcher(
    selectedTab: TicketTab,
    countWithoutRepaired: Int,
    countHadRepaired: Int,
    onSelectTab: (TicketTab) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = IntraColors.BackgroundIce,
        border = BorderStroke(1.dp, IntraColors.SubtleBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TabSegmentItem(
                title = "Без «Починил»",
                count = countWithoutRepaired,
                selected = selectedTab == TicketTab.WITHOUT_REPAIRED,
                activeColor = IntraColors.BrandPrimary,
                onClick = { onSelectTab(TicketTab.WITHOUT_REPAIRED) },
                modifier = Modifier.weight(1f)
            )
            TabSegmentItem(
                title = "Был «Починил»",
                count = countHadRepaired,
                selected = selectedTab == TicketTab.HAD_REPAIRED,
                activeColor = IntraColors.EmeraldSuccess,
                onClick = { onSelectTab(TicketTab.HAD_REPAIRED) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TabSegmentItem(
    title: String,
    count: Int,
    selected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) activeColor else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "tab_bg"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) Color.White else IntraColors.TextSecondary,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "tab_fg"
    )
    val badgeBg by animateColorAsState(
        targetValue = if (selected) Color.White.copy(alpha = 0.24f) else IntraColors.SubtleBorder,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "badge_bg"
    )

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = textColor,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = badgeBg
            ) {
                Text(
                    text = count.toString(),
                    color = if (selected) Color.White else IntraColors.TextPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun TicketCard(
    ticket: TicketItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isCurrentlyRepaired = ticket.statusName.contains("починил", ignoreCase = true)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = IntraColors.SurfaceWhite,
        border = BorderStroke(
            1.dp,
            if (ticket.isOverdue) IntraColors.ErrorRed.copy(alpha = 0.35f) else IntraColors.SubtleBorder
        ),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "#${ticket.id}",
                        style = MaterialTheme.typography.titleSmall,
                        color = IntraColors.BrandPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    PriorityDotAndLabel(ticket.priorityName)
                }

                StatusPill(
                    text = ticket.statusName,
                    isRepaired = isCurrentlyRepaired,
                    isOverdue = ticket.isOverdue && !isCurrentlyRepaired
                )
            }

            Text(
                text = ticket.name,
                style = MaterialTheme.typography.titleMedium,
                color = IntraColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (ticket.descriptionPreview.isNotBlank()) {
                Text(
                    text = ticket.descriptionPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = IntraColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (ticket.serviceName.isNotBlank()) {
                        Text(
                            text = ticket.serviceName,
                            style = MaterialTheme.typography.labelMedium,
                            color = IntraColors.BrandSteelBorder,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (ticket.creatorName.isNotBlank()) {
                        Text(
                            text = "Заявитель: ${ticket.creatorName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = IntraColors.TextTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (ticket.attachmentCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = IntraColors.BackgroundIce
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AttachFile,
                                    contentDescription = null,
                                    tint = IntraColors.TextSecondary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = ticket.attachmentCount.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = IntraColors.TextSecondary
                                )
                            }
                        }
                    }

                    if (ticket.hasHadRepairedStatus && !isCurrentlyRepaired) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = IntraColors.EmeraldTint
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = IntraColors.EmeraldDeep,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "Починил",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = IntraColors.EmeraldDeep
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Schedule,
                        contentDescription = null,
                        tint = if (ticket.isOverdue) IntraColors.ErrorRed else IntraColors.TextTertiary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (ticket.isOverdue) {
                            "Просрочено (${ticket.deadlineText})"
                        } else {
                            "Срок: ${ticket.deadlineText}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (ticket.isOverdue) IntraColors.ErrorRed else IntraColors.TextTertiary
                    )
                }
                if (ticket.changedText.isNotBlank()) {
                    Text(
                        text = ticket.changedText,
                        style = MaterialTheme.typography.labelSmall,
                        color = IntraColors.TextTertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyTicketsState(
    selectedTab: TicketTab,
    hasSearchQuery: Boolean,
    onRefresh: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(IntraColors.SkyTint),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.TaskAlt,
                    contentDescription = null,
                    tint = IntraColors.BrandPrimary,
                    modifier = Modifier.size(34.dp)
                )
            }
            Text(
                text = when {
                    hasSearchQuery -> "Ничего не найдено"
                    selectedTab == TicketTab.WITHOUT_REPAIRED -> "Нет заявок без статуса «Починил»"
                    else -> "Нет открытых заявок, где уже был статус «Починил»"
                },
                style = MaterialTheme.typography.titleMedium,
                color = IntraColors.TextPrimary
            )
            Text(
                text = if (hasSearchQuery) {
                    "Попробуйте изменить поисковый запрос."
                } else {
                    "Потяните список вниз или нажмите «Обновить» для проверки новых заявок."
                },
                style = MaterialTheme.typography.bodySmall,
                color = IntraColors.TextSecondary
            )
            Button(
                onClick = onRefresh,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = IntraColors.BrandPrimary)
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Обновить")
            }
        }
    }
}
