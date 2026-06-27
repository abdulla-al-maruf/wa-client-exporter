package com.jbd.waexport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jbd.waexport.ui.theme.Coral
import com.jbd.waexport.ui.theme.MintDeep
import com.jbd.waexport.ui.theme.PeachDeep
import com.jbd.waexport.ui.theme.Periwinkle
import kotlin.math.absoluteValue

@Composable
fun Avatar(name: String, modifier: Modifier = Modifier) {
    val colors = listOf(PeachDeep, MintDeep, Periwinkle, Coral)
    val color = colors[name.hashCode().absoluteValue % colors.size]
    val initials = name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (initials.isEmpty()) "?" else initials,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            fontSize = 18.sp
        )
    }
}

@Composable
fun StatusChip(isReady: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (isReady) MintDeep.copy(alpha = 0.2f) else Coral.copy(alpha = 0.2f),
        shape = CircleShape,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = if (isReady) "Ready" else "Tap to fix",
            color = if (isReady) MintDeep else Coral,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val mod = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surface)

    Column(
        modifier = if (onClick != null) mod.clickable(onClick = onClick).padding(16.dp) else mod.padding(16.dp),
        content = content
    )
}

@Composable
fun BottomSelectionBar(
    count: Int,
    onClear: () -> Unit,
    onExport: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClear) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Clear selection")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$count selected",
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            
            Button(
                onClick = onExport,
                colors = ButtonDefaults.buttonColors(containerColor = MintDeep)
            ) {
                Text("Export", color = Color.White)
            }
        }
    }
}
