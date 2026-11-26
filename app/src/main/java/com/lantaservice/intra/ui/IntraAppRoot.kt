package com.lantaservice.intra.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lantaservice.intra.R
import com.lantaservice.intra.data.repository.AuthState
import com.lantaservice.intra.data.repository.IntraRepository
import com.lantaservice.intra.data.update.AppUpdateManager
import com.lantaservice.intra.data.update.UpdateUiState
import com.lantaservice.intra.ui.components.IntraWaveBackground
import com.lantaservice.intra.ui.components.UpdateAvailableDialog
import com.lantaservice.intra.ui.detail.TaskDetailScreen
import com.lantaservice.intra.ui.detail.TaskDetailViewModel
import com.lantaservice.intra.ui.location.LocationGateScreen
import com.lantaservice.intra.ui.login.LoginScreen
import com.lantaservice.intra.ui.login.LoginViewModel
import com.lantaservice.intra.ui.tasks.TaskListScreen
import com.lantaservice.intra.ui.tasks.TaskListViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class RootGate {
    CHECKING,
    UNAUTHENTICATED,
    LOCATION_REQUIRED,
    READY
}

@Composable
fun IntraAppRoot(
    repository: IntraRepository,
    appUpdateManager: AppUpdateManager,
    locationGranted: Boolean,
    onRequestLocationAgain: () -> Unit,
    onExitApp: () -> Unit
) {
    val authState by repository.authState.collectAsStateWithLifecycle()
    val updateState by appUpdateManager.state.collectAsStateWithLifecycle()
    val isUpdateDialogDismissed by appUpdateManager.isDialogDismissed.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val rootContext = LocalContext.current
    val outboxEntries by repository.outboxEntries.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        repository.outboxSyncedEvents.collect { notice ->
            Toast.makeText(
                rootContext,
                "Заявка #${notice.taskId} отправлена в Интру (${notice.recordedAtText})",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    LaunchedEffect(authState, outboxEntries.size) {
        if (authState is AuthState.Authenticated && outboxEntries.isNotEmpty()) {
            while (true) {
                delay(20_000L)
                repository.syncPendingOutboxNow()
            }
        }
    }

    val gate = when (authState) {
        is AuthState.Checking -> RootGate.CHECKING
        is AuthState.Unauthenticated -> RootGate.UNAUTHENTICATED
        is AuthState.Authenticated -> {
            if (locationGranted) RootGate.READY else RootGate.LOCATION_REQUIRED
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = gate,
            transitionSpec = {
                fadeIn(tween(260)) togetherWith fadeOut(tween(220))
            },
            label = "root_gate_transition"
        ) { currentGate ->
            when (currentGate) {
                RootGate.CHECKING -> {
                    IntraWaveBackground(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_app_logo_mark),
                                contentDescription = "IntraService Mobile",
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(18.dp))
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "IntraService Mobile",
                                color = Color.White,
                                fontSize = 29.sp,
                                fontWeight = FontWeight.Light
                            )
                            Spacer(modifier = Modifier.height(22.dp))
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                }

                // Strictly show ONLY LoginScreen when unauthenticated
                RootGate.UNAUTHENTICATED -> {
                    val loginVm: LoginViewModel = viewModel(
                        factory = LoginViewModel.Factory(repository)
                    )
                    LoginScreen(
                        viewModel = loginVm,
                        locationGranted = locationGranted,
                        onRequestLocationAgain = onRequestLocationAgain
                    )
                }

                RootGate.LOCATION_REQUIRED -> {
                    val session = (authState as AuthState.Authenticated).session
                    LocationGateScreen(
                        session = session,
                        onRequestPermissionAgain = onRequestLocationAgain,
                        onLogout = onExitApp
                    )
                }

                RootGate.READY -> {
                    val session = (authState as AuthState.Authenticated).session
                    val context = LocalContext.current
                    val navController = rememberNavController()
                    val taskListViewModel: TaskListViewModel = viewModel(
                        key = "tasks_${session.userId}",
                        factory = TaskListViewModel.Factory(repository)
                    )

                    NavHost(
                        navController = navController,
                        startDestination = "tasks",
                        enterTransition = {
                            slideInHorizontally(tween(280)) { it / 4 } + fadeIn(tween(240))
                        },
                        exitTransition = {
                            slideOutHorizontally(tween(240)) { -it / 6 } + fadeOut(tween(200))
                        },
                        popEnterTransition = {
                            slideInHorizontally(tween(260)) { -it / 6 } + fadeIn(tween(240))
                        },
                        popExitTransition = {
                            slideOutHorizontally(tween(260)) { it / 4 } + fadeOut(tween(220))
                        }
                    ) {
                        composable("tasks") {
                            TaskListScreen(
                                session = session,
                                viewModel = taskListViewModel,
                                hasAvailableUpdate = updateState !is UpdateUiState.Idle,
                                onOpenUpdateDialog = appUpdateManager::reopenDialog,
                                onOpenTicket = { taskId ->
                                    navController.navigate("task/$taskId")
                                },
                                onExitApp = onExitApp
                            )
                        }

                        composable(
                            route = "task/{taskId}",
                            arguments = listOf(navArgument("taskId") { type = NavType.IntType })
                        ) { backStackEntry ->
                            val taskId = backStackEntry.arguments?.getInt("taskId") ?: return@composable
                            val detailViewModel: TaskDetailViewModel = viewModel(
                                key = "detail_${session.userId}_$taskId",
                                factory = TaskDetailViewModel.Factory(taskId, repository)
                            )
                            TaskDetailScreen(
                                viewModel = detailViewModel,
                                onBack = { navController.popBackStack() },
                                onSavedSuccess = { saveResult ->
                                    taskListViewModel.onTicketSaved(saveResult.movedToRepairedTab)
                                    val toastText = when {
                                        saveResult.deferred ->
                                            "Интра недоступна. Сохранено в очередь (${saveResult.deferredRecordedAt})"
                                        saveResult.movedToRepairedTab && saveResult.attachedCount > 0 ->
                                            "Статус «Починил» и фото (${saveResult.attachedCount}) сохранены"
                                        saveResult.movedToRepairedTab ->
                                            "Статус изменён на «Починил»"
                                        saveResult.attachedCount > 0 ->
                                            "Прикреплено фото: ${saveResult.attachedCount}"
                                        else -> "Изменения сохранены"
                                    }
                                    Toast.makeText(context, toastText, Toast.LENGTH_LONG).show()
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }

        if (updateState !is UpdateUiState.Idle && !isUpdateDialogDismissed) {
            UpdateAvailableDialog(
                state = updateState,
                onStartDownload = {
                    val info = when (val st = updateState) {
                        is UpdateUiState.Available -> st.info
                        is UpdateUiState.Error -> st.info
                        else -> null
                    }
                    if (info != null) {
                        appUpdateManager.startDownloadInBackground(info)
                    }
                },
                onInstallNow = {
                    val ready = updateState as? UpdateUiState.ReadyToInstall
                    if (ready != null) {
                        appUpdateManager.launchInstaller(ready.apkFile)
                    }
                },
                onRemindLater = appUpdateManager::dismissDialog,
                onSkipVersion = appUpdateManager::skipVersion,
                onMoveToBackground = appUpdateManager::dismissDialog
            )
        }
    }
}
