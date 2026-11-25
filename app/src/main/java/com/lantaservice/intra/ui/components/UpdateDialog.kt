package com.lantaservice.intra.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lantaservice.intra.BuildConfig
import com.lantaservice.intra.data.update.UpdateUiState
import com.lantaservice.intra.ui.theme.IntraColors
import java.util.Locale

internal sealed interface ReleaseNoteBlock {
    data class Heading(val text: String, val level: Int) : ReleaseNoteBlock
    data class Bullet(val text: String) : ReleaseNoteBlock
    data class TableRow(val cells: List<String>) : ReleaseNoteBlock
    data class Paragraph(val text: String) : ReleaseNoteBlock
    data object Divider : ReleaseNoteBlock
}

private val HTML_TAG_REGEX = Regex("</?(?:details|summary|b|i|br\\s*/?)>", RegexOption.IGNORE_CASE)

internal fun parseReleaseNotes(raw: String): List<ReleaseNoteBlock> {
    val lines = raw.replace("\r\n", "\n").lines()
    val result = mutableListOf<ReleaseNoteBlock>()
    for (line in lines) {
        var cleaned = line.replace(HTML_TAG_REGEX, "").trim()
        if (cleaned.startsWith(">")) {
            cleaned = cleaned.removePrefix(">").trim()
        }
        when {
            cleaned.isEmpty() -> continue
            cleaned == "---" || cleaned == "***" || cleaned == "___" -> {
                result += ReleaseNoteBlock.Divider
            }
            cleaned.startsWith("### ") -> {
                result += ReleaseNoteBlock.Heading(cleaned.removePrefix("### ").trim(), 3)
            }
            cleaned.startsWith("## ") -> {
                result += ReleaseNoteBlock.Heading(cleaned.removePrefix("## ").trim(), 2)
            }
            cleaned.startsWith("# ") -> {
                result += ReleaseNoteBlock.Heading(cleaned.removePrefix("# ").trim(), 1)
            }
            cleaned.startsWith("- ") || cleaned.startsWith("* ") -> {
                result += ReleaseNoteBlock.Bullet(cleaned.drop(2).trim())
            }
            cleaned.startsWith("|") && cleaned.endsWith("|") -> {
                val cells = cleaned.trim('|').split('|').map { it.trim() }
                val isAlignRow = cells.isNotEmpty() && cells.all { cell ->
                    cell.isNotEmpty() && cell.all { ch -> ch == '-' || ch == ':' || ch == ' ' }
                }
                if (!isAlignRow && cells.any { it.isNotEmpty() }) {
                    result += ReleaseNoteBlock.TableRow(cells)
                }
            }
            else -> {
                result += ReleaseNoteBlock.Paragraph(cleaned)
            }
        }
    }
    return result
}

private fun buildInlineStyledText(
    text: String,
    baseColor: Color,
    boldColor: Color,
    codeColor: Color
): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(color = boldColor, fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    withStyle(SpanStyle(color = baseColor)) { append("**") }
                    i += 2
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(
                        SpanStyle(
                            color = codeColor,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    ) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    withStyle(SpanStyle(color = baseColor)) { append('`') }
                    i += 1
                }
            }
            else -> {
                val nextBold = text.indexOf("**", i)
                val nextCode = text.indexOf('`', i)
                val nextSpecial = listOf(nextBold, nextCode).filter { it >= 0 }.minOrNull() ?: text.length
                val end = if (nextSpecial == i) i + 1 else nextSpecial
                withStyle(SpanStyle(color = baseColor)) {
                    append(text.substring(i, end))
                }
                i = end
            }
        }
    }
}

@Composable
fun UpdateAvailableDialog(
    state: UpdateUiState,
    onStartDownload: () -> Unit,
    onInstallNow: () -> Unit,
    onRemindLater: () -> Unit,
    onSkipVersion: (String) -> Unit,
    onMoveToBackground: () -> Unit
) {
    val info = when (state) {
        is UpdateUiState.Available -> state.info
        is UpdateUiState.Downloading -> state.info
        is UpdateUiState.ReadyToInstall -> state.info
        is UpdateUiState.Error -> state.info
        UpdateUiState.Idle -> return
    }

    val cleanCur = BuildConfig.VERSION_NAME.trim().removePrefix("v").removePrefix("V")
    val cleanNew = info.versionName.trim().removePrefix("v").removePrefix("V")

    Dialog(
        onDismissRequest = {
            if (state is UpdateUiState.Downloading) onMoveToBackground() else onRemindLater()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        when (state) {
            is UpdateUiState.Available -> {
                AvailableUpdateCard(
                    cleanCur = cleanCur,
                    cleanNew = cleanNew,
                    changelog = info.changelog,
                    sizeBytes = info.sizeBytes,
                    publishedDateText = info.publishedDateText,
                    releasePageUrl = info.releasePageUrl,
                    onUpdateNow = onStartDownload,
                    onRemindLater = onRemindLater,
                    onSkipVersion = { onSkipVersion(cleanNew) }
                )
            }

            is UpdateUiState.Downloading -> {
                DownloadingUpdateCard(
                    cleanNew = cleanNew,
                    progress = state.progress,
                    downloadedBytes = state.downloadedBytes,
                    totalBytes = state.totalBytes,
                    onMoveToBackground = onMoveToBackground
                )
            }

            is UpdateUiState.ReadyToInstall -> {
                ReadyToInstallCard(
                    cleanNew = cleanNew,
                    onInstallNow = onInstallNow,
                    onRemindLater = onRemindLater
                )
            }

            is UpdateUiState.Error -> {
                ErrorUpdateCard(
                    cleanNew = cleanNew,
                    errorMessage = state.message,
                    onRetry = onStartDownload,
                    onDismiss = onRemindLater
                )
            }

            UpdateUiState.Idle -> Unit
        }
    }
}

@Composable
private fun AvailableUpdateCard(
    cleanCur: String,
    cleanNew: String,
    changelog: String,
    sizeBytes: Long,
    publishedDateText: String,
    releasePageUrl: String,
    onUpdateNow: () -> Unit,
    onRemindLater: () -> Unit,
    onSkipVersion: () -> Unit
) {
    val ctx = LocalContext.current
    val parsedBlocks = remember(changelog, cleanNew) {
        parseReleaseNotes(changelog.ifBlank { "IntraService Mobile $cleanNew" })
    }
    val sizeMb = if (sizeBytes > 0L) sizeBytes.toDouble() / (1024.0 * 1024.0) else 0.0
    val sizeLabel = if (sizeMb > 0.0) String.format(Locale.US, "%.1f МБ", sizeMb) else ""

    UpdateDialogCardShell(onDismissBackdrop = onRemindLater) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(IntraColors.EmeraldSuccess.copy(alpha = 0.14f))
                    .border(1.dp, IntraColors.EmeraldSuccess.copy(alpha = 0.45f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.SystemUpdate,
                    contentDescription = null,
                    tint = IntraColors.EmeraldSuccess,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Доступно обновление",
                    color = IntraColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "IntraService Mobile",
                    color = IntraColors.TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(IntraColors.SurfaceMuted)
                    .border(1.dp, IntraColors.SubtleBorder, RoundedCornerShape(9.dp))
                    .clickable(onClick = onRemindLater),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Закрыть",
                    tint = IntraColors.TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(IntraColors.SubtleBorder)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Version Transition Card: Current -> New
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(IntraColors.SurfaceMuted)
                    .border(1.dp, IntraColors.SubtleBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                VersionBadgeColumn(
                    label = "Текущая",
                    version = cleanCur,
                    accent = false,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(IntraColors.EmeraldSuccess.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        tint = IntraColors.EmeraldDeep,
                        modifier = Modifier.size(16.dp)
                    )
                }
                VersionBadgeColumn(
                    label = "Новая",
                    version = cleanNew,
                    accent = true,
                    modifier = Modifier.weight(1f)
                )
            }

            // Metadata Chips: Source, Size, Release Date
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetadataChip(Icons.Rounded.Public, "GitHub", highlighted = true)
                if (sizeLabel.isNotBlank()) {
                    MetadataChip(Icons.Rounded.Storage, sizeLabel)
                }
                if (publishedDateText.isNotBlank()) {
                    MetadataChip(Icons.Rounded.CalendarToday, publishedDateText)
                }
            }

            // Changelog Section Label
            Text(
                text = "ЧТО НОВОГО:",
                color = IntraColors.TextSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.7.sp
            )

            // Scrollable Structured Changelog Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 110.dp, max = 240.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(IntraColors.BackgroundIce)
                    .border(1.dp, IntraColors.SubtleBorder, RoundedCornerShape(14.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                parsedBlocks.forEach { block ->
                    when (block) {
                        is ReleaseNoteBlock.Divider -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .height(1.dp)
                                    .background(IntraColors.SubtleBorder)
                            )
                        }
                        is ReleaseNoteBlock.Heading -> {
                            val topPad = if (block.level <= 2) 4.dp else 2.dp
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = topPad)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(14.dp)
                                        .clip(RoundedCornerShape(1.5.dp))
                                        .background(IntraColors.EmeraldSuccess)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = buildInlineStyledText(
                                        block.text,
                                        IntraColors.TextPrimary,
                                        IntraColors.TextPrimary,
                                        IntraColors.EmeraldDeep
                                    ),
                                    color = IntraColors.TextPrimary,
                                    fontSize = if (block.level <= 2) 13.5.sp else 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        is ReleaseNoteBlock.Bullet -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 4.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "-",
                                    color = IntraColors.EmeraldDeep,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(end = 7.dp)
                                )
                                Text(
                                    text = buildInlineStyledText(
                                        block.text,
                                        IntraColors.TextSecondary,
                                        IntraColors.TextPrimary,
                                        IntraColors.EmeraldDeep
                                    ),
                                    fontSize = 12.5.sp,
                                    lineHeight = 17.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        is ReleaseNoteBlock.TableRow -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(IntraColors.SurfaceWhite.copy(alpha = 0.85f))
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = block.cells.joinToString(" | "),
                                    color = IntraColors.TextSecondary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        is ReleaseNoteBlock.Paragraph -> {
                            Text(
                                text = buildInlineStyledText(
                                    block.text,
                                    IntraColors.TextSecondary,
                                    IntraColors.TextPrimary,
                                    IntraColors.EmeraldDeep
                                ),
                                fontSize = 12.5.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }
            }

            // Primary Update CTA
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(IntraColors.EmeraldSuccess)
                    .clickable(onClick = onUpdateNow)
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudDownload,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Обновить сейчас",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Secondary Actions: Remind Later + Skip Version
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryActionButton(
                    text = "Напомнить позже",
                    modifier = Modifier.weight(1f),
                    onClick = onRemindLater
                )
                SecondaryActionButton(
                    text = "Пропустить версию",
                    modifier = Modifier.weight(1f),
                    onClick = onSkipVersion
                )
            }

            // Footer Link to Open Release Page
            if (releasePageUrl.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(releasePageUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        tint = IntraColors.BrandPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "Открыть страницу релиза",
                        color = IntraColors.BrandPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadingUpdateCard(
    cleanNew: String,
    progress: Float,
    downloadedBytes: Long,
    totalBytes: Long,
    onMoveToBackground: () -> Unit
) {
    var lastSampleMs by remember { mutableLongStateOf(0L) }
    var lastSampleBytes by remember { mutableLongStateOf(0L) }
    var speedMbPerSec by remember { mutableDoubleStateOf(0.0) }

    LaunchedEffect(downloadedBytes) {
        val now = System.currentTimeMillis()
        if (lastSampleMs > 0L) {
            val dt = now - lastSampleMs
            val db = downloadedBytes - lastSampleBytes
            if (dt >= 350L && db >= 0L) {
                val instantSpeed = (db.toDouble() / (1024.0 * 1024.0)) / (dt.toDouble() / 1000.0)
                speedMbPerSec = if (speedMbPerSec <= 0.01) {
                    instantSpeed
                } else {
                    speedMbPerSec * 0.65 + instantSpeed * 0.35
                }
                lastSampleMs = now
                lastSampleBytes = downloadedBytes
            }
        } else {
            lastSampleMs = now
            lastSampleBytes = downloadedBytes
        }
    }

    UpdateDialogCardShell(onDismissBackdrop = {}) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(IntraColors.EmeraldSuccess.copy(alpha = 0.14f))
                        .border(1.dp, IntraColors.EmeraldSuccess.copy(alpha = 0.45f), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudDownload,
                        contentDescription = null,
                        tint = IntraColors.EmeraldSuccess,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Загрузка обновления...",
                        color = IntraColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (cleanNew.isNotBlank()) {
                        Text(
                            text = "IntraService Mobile $cleanNew",
                            color = IntraColors.TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp
                        )
                    }
                }
            }

            if (progress >= 0f) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    color = IntraColors.EmeraldSuccess,
                    trackColor = IntraColors.SubtleBorder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
            } else {
                LinearProgressIndicator(
                    color = IntraColors.EmeraldSuccess,
                    trackColor = IntraColors.SubtleBorder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val leftLabel = when {
                    totalBytes > 0L -> String.format(
                        Locale.US,
                        "%.1f МБ / %.1f МБ",
                        downloadedBytes.toDouble() / (1024.0 * 1024.0),
                        totalBytes.toDouble() / (1024.0 * 1024.0)
                    )
                    downloadedBytes > 0L -> String.format(
                        Locale.US,
                        "%.1f МБ",
                        downloadedBytes.toDouble() / (1024.0 * 1024.0)
                    )
                    else -> "Подготовка загрузки..."
                }
                Text(
                    text = leftLabel,
                    color = IntraColors.TextSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (speedMbPerSec > 0.05) {
                        Text(
                            text = String.format(Locale.US, "%.1f МБ/с", speedMbPerSec),
                            color = IntraColors.TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                    if (progress >= 0f) {
                        Text(
                            text = "${(progress * 100f).toInt()}%",
                            color = IntraColors.EmeraldDeep,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            SecondaryActionButton(
                text = "В фоновый режим",
                modifier = Modifier.fillMaxWidth(),
                onClick = onMoveToBackground
            )
        }
    }
}

@Composable
private fun ReadyToInstallCard(
    cleanNew: String,
    onInstallNow: () -> Unit,
    onRemindLater: () -> Unit
) {
    UpdateDialogCardShell(onDismissBackdrop = onRemindLater) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(IntraColors.EmeraldSuccess.copy(alpha = 0.14f))
                        .border(1.dp, IntraColors.EmeraldSuccess.copy(alpha = 0.45f), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = IntraColors.EmeraldSuccess,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Обновление готово",
                        color = IntraColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = cleanNew,
                        color = IntraColors.EmeraldDeep,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Text(
                text = "Пакет обновления загружен и готов к установке.",
                color = IntraColors.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryActionButton(
                    text = "Позже",
                    modifier = Modifier.weight(1f),
                    onClick = onRemindLater
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(11.dp))
                        .background(IntraColors.EmeraldSuccess)
                        .clickable(onClick = onInstallNow)
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Установить",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorUpdateCard(
    cleanNew: String,
    errorMessage: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    UpdateDialogCardShell(onDismissBackdrop = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(IntraColors.ErrorRed.copy(alpha = 0.14f))
                        .border(1.dp, IntraColors.ErrorRed.copy(alpha = 0.45f), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = IntraColors.ErrorRed,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Ошибка загрузки обновления",
                        color = IntraColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (cleanNew.isNotBlank()) {
                        Text(
                            text = cleanNew,
                            color = IntraColors.ErrorRed,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(IntraColors.ErrorTint)
                    .padding(10.dp)
            ) {
                Text(
                    text = errorMessage,
                    color = IntraColors.ErrorRed,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryActionButton(
                    text = "Повторить",
                    modifier = Modifier.weight(1f),
                    onClick = onRetry
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(11.dp))
                        .background(IntraColors.BrandPrimary)
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Закрыть",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateDialogCardShell(
    onDismissBackdrop: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.58f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismissBackdrop
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(IntraColors.SurfaceWhite)
                .border(1.2.dp, IntraColors.SubtleBorder, RoundedCornerShape(20.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            content()
        }
    }
}

@Composable
private fun VersionBadgeColumn(
    label: String,
    version: String,
    accent: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (accent) IntraColors.EmeraldSuccess.copy(alpha = 0.14f) else IntraColors.BackgroundIce)
            .border(
                1.dp,
                if (accent) IntraColors.EmeraldSuccess.copy(alpha = 0.48f) else IntraColors.SubtleBorder,
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = IntraColors.TextSecondary,
            fontSize = 10.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = version,
            color = if (accent) IntraColors.EmeraldDeep else IntraColors.TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MetadataChip(
    icon: ImageVector,
    label: String,
    highlighted: Boolean = false
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (highlighted) IntraColors.EmeraldSuccess.copy(alpha = 0.12f) else IntraColors.SurfaceMuted)
            .border(
                1.dp,
                if (highlighted) IntraColors.EmeraldSuccess.copy(alpha = 0.40f) else IntraColors.SubtleBorder,
                RoundedCornerShape(9.dp)
            )
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (highlighted) IntraColors.EmeraldDeep else IntraColors.TextSecondary,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = label,
            color = if (highlighted) IntraColors.TextPrimary else IntraColors.TextSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun SecondaryActionButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(IntraColors.SurfaceMuted)
            .border(1.dp, IntraColors.SubtleBorder, RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = IntraColors.TextPrimary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
