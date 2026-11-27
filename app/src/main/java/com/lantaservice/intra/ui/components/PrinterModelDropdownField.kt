package com.lantaservice.intra.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lantaservice.intra.ui.theme.IntraColors
import com.lantaservice.intra.util.PrinterCatalogEntry
import com.lantaservice.intra.util.PrinterModelCatalogEngine

/**
 * Smooth searchable dropdown field over the official printer / MFP catalog,
 * supporting instant multi-token search, brand filter pills, and custom entry fallback.
 */
@Composable
fun PrinterModelDropdownField(
    value: String,
    onValueChange: (String) -> Unit,
    catalogEntries: List<PrinterCatalogEntry>,
    modifier: Modifier = Modifier,
    label: String = "Модель аппарата",
    placeholder: String = "Начните ввод или выберите из списка",
    initialBrandFilter: String? = null,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedBrand by remember(initialBrandFilter) { mutableStateOf(initialBrandFilter) }

    val indexedCatalog = remember(catalogEntries) {
        PrinterModelCatalogEngine.buildIndex(catalogEntries)
    }
    val availableBrands = remember(catalogEntries) {
        PrinterModelCatalogEngine.availableBrands(catalogEntries)
    }
    val filteredEntries = remember(indexedCatalog, value, selectedBrand, expanded) {
        if (!expanded) {
            emptyList()
        } else {
            PrinterModelCatalogEngine.search(
                indexed = indexedCatalog,
                query = value,
                brandFilter = selectedBrand,
                limit = 65
            )
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { next ->
                onValueChange(next)
                if (!expanded) expanded = true
            },
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (value.isNotEmpty() && enabled) {
                        IconButton(
                            onClick = {
                                onValueChange("")
                                expanded = true
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Очистить модель",
                                tint = IntraColors.TextTertiary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = { if (enabled) expanded = !expanded },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                            contentDescription = if (expanded) "Свернуть список" else "Открыть список моделей",
                            tint = IntraColors.BrandPrimary
                        )
                    }
                }
            },
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = IntraColors.BrandPrimary,
                unfocusedBorderColor = IntraColors.SubtleBorder
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    if (focusState.isFocused && enabled) {
                        expanded = true
                    }
                }
        )

        AnimatedVisibility(
            visible = expanded && catalogEntries.isNotEmpty(),
            enter = expandVertically(animationSpec = tween(180)) + fadeIn(tween(140)),
            exit = shrinkVertically(animationSpec = tween(140)) + fadeOut(tween(110))
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = IntraColors.SurfaceWhite,
                border = BorderStroke(1.dp, IntraColors.BrandPrimary.copy(alpha = 0.28f)),
                shadowElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    // Horizontal brand filter pills
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item(key = "__ALL__") {
                            BrandFilterChip(
                                title = "Все (${catalogEntries.size})",
                                selected = selectedBrand == null,
                                onClick = { selectedBrand = null }
                            )
                        }
                        items(availableBrands, key = { it }) { brand ->
                            BrandFilterChip(
                                title = brand,
                                selected = selectedBrand == brand,
                                onClick = {
                                    selectedBrand = if (selectedBrand == brand) null else brand
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.size(6.dp))
                    HorizontalDivider(color = IntraColors.SubtleBorder)

                    if (filteredEntries.isEmpty()) {
                        Text(
                            text = "Модель не найдена в справочнике - будет сохранено введённое название",
                            style = MaterialTheme.typography.bodySmall,
                            color = IntraColors.TextSecondary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                        ) {
                            items(
                                items = filteredEntries,
                                key = { "${it.brand}|${it.model}" }
                            ) { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onValueChange(item.fullName)
                                            expanded = false
                                        }
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Print,
                                            contentDescription = null,
                                            tint = IntraColors.BrandPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = item.fullName,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            color = IntraColors.TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = IntraColors.BackgroundIce
                                    ) {
                                        Text(
                                            text = item.category,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = IntraColors.TextSecondary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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
}

@Composable
private fun BrandFilterChip(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = if (selected) IntraColors.BrandPrimary else IntraColors.BackgroundIce,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) IntraColors.BrandPrimary else IntraColors.SubtleBorder
        )
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) Color.White else IntraColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}
