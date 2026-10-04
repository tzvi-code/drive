package com.tzvi.drivedownloadmonitor.ui.overlay

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tzvi.drivedownloadmonitor.domain.model.DownloadItem
import com.tzvi.drivedownloadmonitor.domain.model.DownloadState
import com.tzvi.drivedownloadmonitor.util.Formatters
import kotlinx.coroutines.delay

@Composable
fun OverlayView(
    items: List<DownloadItem>,
    onClose: () -> Unit,
    onDragStart: (Float, Float) -> Unit,
    onDragMove: (Float, Float) -> Unit,
    onDragEnd: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val hasCompleted = items.any { it.state == DownloadState.COMPLETED }
    val allFinished = items.isNotEmpty() && items.all {
        it.state == DownloadState.COMPLETED ||
            it.state == DownloadState.CANCELLED ||
            it.state == DownloadState.FAILED
    }

    val dragModifier = Modifier
        .fillMaxWidth()
        .pointerInteropFilter { event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    onDragStart(event.rawX, event.rawY)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    onDragMove(event.rawX, event.rawY)
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    onDragEnd()
                    true
                }
                else -> true
            }
        }

    val pulseScale by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = if (hasCompleted) 1.04f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                if (hasCompleted) {
                    scaleX = pulseScale
                    scaleY = pulseScale
                }
            },
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 10.dp,
        shadowElevation = 12.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    ) {
        Column(
            modifier = Modifier
                .animateContentSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = dragModifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp)
                    ) {
                        Text(
                            text = if (items.size == 1) {
                                items.first().fileName
                            } else {
                                "${items.size} הורדות פעילות"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (items.size == 1) {
                                statusText(items.first())
                            } else {
                                items.joinToString(" • ") { statusText(it) }
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.PauseCircleOutline else Icons.Default.Download,
                        contentDescription = null
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = null)
                }
            }

            val visibleItems = if (expanded) items.take(3) else items.take(1)
            visibleItems.forEach { item ->
                DownloadRow(item)
            }

            if (!expanded && items.size > 1) {
                Text(
                    text = "לחץ על החץ כדי להציג עד ${items.size} הורדות",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }

    LaunchedEffect(allFinished) {
        if (allFinished) {
            delay(2_000)
            onClose()
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val stateIcon = when (item.state) {
                DownloadState.COMPLETED -> Icons.Default.CheckCircle
                DownloadState.FAILED,
                DownloadState.CANCELLED -> Icons.Default.ErrorOutline
                DownloadState.PAUSED -> Icons.Default.PauseCircleOutline
                DownloadState.DOWNLOADING -> Icons.Default.Download
            }
            Icon(stateIcon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                item.fileName,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                Formatters.speed(item.speedBytesPerSecond),
                style = MaterialTheme.typography.labelMedium
            )
        }

        when {
            item.state == DownloadState.COMPLETED -> {
                LinearProgressIndicator(
                    progress = { 1f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                )
                Text(
                    "הושלם • ${Formatters.bytes(item.currentBytes)}",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            item.state == DownloadState.FAILED ||
                item.state == DownloadState.CANCELLED -> {
                LinearProgressIndicator(
                    progress = { (item.effectiveProgressPercent ?: 0f) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                )
                Text(
                    item.errorMessage ?: "ההורדה הסתיימה ללא השלמה",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            item.effectiveProgressPercent != null -> {
                LinearProgressIndicator(
                    progress = {
                        (item.effectiveProgressPercent ?: 0f) / 100f
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                )
            }

            else -> {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val total = item.expectedBytes
            Text(
                if (total != null) {
                    "${Formatters.bytes(item.currentBytes)} / ${Formatters.bytes(total)}"
                } else {
                    "${Formatters.bytes(item.currentBytes)} הורדו"
                },
                style = MaterialTheme.typography.labelSmall
            )

            Text(
                when (item.state) {
                    DownloadState.DOWNLOADING ->
                        if (item.etaSeconds != null) "ETA ${Formatters.eta(item.etaSeconds)}" else "מוריד…"
                    DownloadState.PAUSED -> "מושהה"
                    DownloadState.COMPLETED -> "100%"
                    DownloadState.FAILED -> "נכשל"
                    DownloadState.CANCELLED -> "בוטל"
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private fun statusText(item: DownloadItem): String = when (item.state) {
    DownloadState.DOWNLOADING -> Formatters.speed(item.speedBytesPerSecond)
    DownloadState.PAUSED -> "מושהה"
    DownloadState.COMPLETED -> "הושלם"
    DownloadState.FAILED -> "נכשל"
    DownloadState.CANCELLED -> "בוטל"
}
