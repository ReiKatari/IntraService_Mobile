package com.lantaservice.intra.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.lantaservice.intra.BuildConfig
import com.lantaservice.intra.data.update.UpdateUiState
import com.lantaservice.intra.ui.theme.IntraColors
import com.lantaservice.intra.util.DateAndTextFormatter

@Composable
fun UpdateAvailableDialog(
    state: UpdateUiState,
    onStartDownload: () -> Unit,
    onInstallNow: () -> Unit,
    onDismiss: () -> Unit
) {
    val info = when (state) {
        is UpdateUiState.Available -> state.info
        is UpdateUiState.Downloading -> state.info
        is UpdateUiState.ReadyToInstall -> state.info
        is UpdateUiState.Error -> state.info
        UpdateUiState.Idle -> return
    }

    Dialog(onDismissRequest = {
        if (state !is UpdateUiState.Downloading) onDismiss()
    }) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = IntraColors.SurfaceWhite,
            border = BorderStroke(1.dp, IntraColors.SubtleBorder),
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(IntraColors.SkyTint),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (state is UpdateUiState.ReadyToInstall) {
                                Icons.Rounded.CheckCircle
                            } else {
                                Icons.Rounded.SystemUpdate
                            },
                            contentDescription = null,
                            tint = if (state is UpdateUiState.ReadyToInstall) {
                                IntraColors.EmeraldSuccess
                            } else {
                                IntraColors.BrandPrimary
                            }
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Доступно обновление",
                            style = MaterialTheme.typography.titleMedium,
                            color = IntraColors.TextPrimary
                        )
                        Text(
                            text = "v${BuildConfig.VERSION_NAME} -> v${info.versionName}",
                            style = MaterialTheme.typography.labelMedium,
                            color = IntraColors.EmeraldDeep,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (info.changelog.isNotBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = IntraColors.BackgroundIce
                    ) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Text(
                                text = info.changelog,
                                style = MaterialTheme.typography.bodySmall,
                                color = IntraColors.TextSecondary
                            )
                        }
                    }
                }

                when (state) {
                    is UpdateUiState.Downloading -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (state.progress >= 0f) {
                                LinearProgressIndicator(
                                    progress = { state.progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(50)),
                                    color = IntraColors.BrandPrimary,
                                    trackColor = IntraColors.SubtleBorder
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(50)),
                                    color = IntraColors.BrandPrimary,
                                    trackColor = IntraColors.SubtleBorder
                                )
                            }
                            val sizeInfo = if (state.totalBytes > 0L) {
                                "${DateAndTextFormatter.formatBytes(state.downloadedBytes)} / ${DateAndTextFormatter.formatBytes(state.totalBytes)}"
                            } else {
                                DateAndTextFormatter.formatBytes(state.downloadedBytes)
                            }
                            Text(
                                text = "Загрузка APK: $sizeInfo",
                                style = MaterialTheme.typography.labelSmall,
                                color = IntraColors.TextSecondary
                            )
                        }
                    }

                    is UpdateUiState.Error -> {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = IntraColors.ErrorTint,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = IntraColors.ErrorRed,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    is UpdateUiState.ReadyToInstall -> {
                        Text(
                            text = "Пакет обновления загружен и готов к установке.",
                            style = MaterialTheme.typography.bodySmall,
                            color = IntraColors.EmeraldDeep,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    else -> {
                        if (info.sizeBytes > 0L) {
                            Text(
                                text = "Размер файла: ${DateAndTextFormatter.formatBytes(info.sizeBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = IntraColors.TextTertiary
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state !is UpdateUiState.Downloading) {
                        TextButton(onClick = onDismiss) {
                            Text("Позже", color = IntraColors.TextSecondary)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    when (state) {
                        is UpdateUiState.Available, is UpdateUiState.Error -> {
                            Button(
                                onClick = onStartDownload,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = IntraColors.BrandPrimary,
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (state is UpdateUiState.Error) "Повторить" else "Обновить")
                            }
                        }

                        is UpdateUiState.ReadyToInstall -> {
                            Button(
                                onClick = onInstallNow,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = IntraColors.EmeraldSuccess,
                                    contentColor = Color.White
                                )
                            ) {
                                Text("Установить")
                            }
                        }

                        is UpdateUiState.Downloading -> {
                            Button(
                                onClick = {},
                                enabled = false,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Скачивание...")
                            }
                        }

                        UpdateUiState.Idle -> Unit
                    }
                }
            }
        }
    }
}
