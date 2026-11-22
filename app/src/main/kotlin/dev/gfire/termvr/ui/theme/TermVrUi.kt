package dev.gfire.termvr.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.gfire.termvr.core.model.SandboxRunState

val TermVrControlShape = RoundedCornerShape(6.dp)
val TermVrCardShape = RoundedCornerShape(8.dp)

@Composable
fun TermVrCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cardModifier = if (onClick != null) {
        modifier
            .clip(TermVrCardShape)
            .clickable(onClick = onClick)
    } else {
        modifier
    }
    Card(
        modifier = cardModifier,
        shape = TermVrCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, borderColor),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        content = content,
    )
}

@Composable
fun TermVrStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
fun TermVrBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    dotColor: Color? = null,
    monospace: Boolean = true,
) {
    Surface(
        modifier = modifier,
        shape = TermVrControlShape,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dotColor != null) {
                TermVrStatusDot(color = dotColor, size = 6.dp)
            }
            Text(
                text = text,
                style = if (monospace) {
                    MaterialTheme.typography.labelSmall
                } else {
                    MaterialTheme.typography.labelMedium
                },
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun TermVrSelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingDotColor: Color? = null,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val border = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    Surface(
        modifier = modifier
            .clip(TermVrControlShape)
            .clickable(onClick = onClick),
        shape = TermVrControlShape,
        color = container,
        contentColor = content,
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingDotColor != null) {
                TermVrStatusDot(color = leadingDotColor, size = 7.dp)
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun TermVrButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    content: @Composable () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = TermVrControlShape,
        colors = colors,
        contentPadding = contentPadding,
        content = { content() },
    )
}

@Composable
fun TermVrOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: @Composable () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = TermVrControlShape,
        border = BorderStroke(1.dp, borderColor),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
        contentPadding = contentPadding,
        content = { content() },
    )
}

@Composable
fun TermVrTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = TermVrControlShape,
        colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
        content = { content() },
    )
}

@Composable
fun TermVrNoticeBanner(
    message: String,
    isError: Boolean = false,
    onDismiss: (() -> Unit)? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val container = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val content = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    val border = if (isError) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)
    }
    TermVrCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = container,
        borderColor = border,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isError) {
                Icon(
                    imageVector = TermVrIcons.Warning,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                TermVrTextButton(onClick = onAction, contentColor = content) {
                    Text(actionLabel)
                }
            }
            if (onDismiss != null) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = TermVrIcons.Close,
                        contentDescription = "Dismiss",
                        tint = content,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun TermVrSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (trailing != null) {
            trailing()
        }
    }
}

@Composable
fun TermVrRunStateDot(
    runState: SandboxRunState,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
) {
    TermVrStatusDot(
        color = runStateColor(runState),
        modifier = modifier,
        size = size,
    )
}

@Composable
fun runStateColor(runState: SandboxRunState): Color {
    val tokens = LocalTermVrPaletteTokens.current
    return when (runState) {
        SandboxRunState.RUNNING -> tokens.statusRunning
        SandboxRunState.PAUSED,
        SandboxRunState.FROZEN,
        SandboxRunState.STARTING,
        SandboxRunState.FREEZING,
        SandboxRunState.RESTORING,
        -> tokens.statusFrozen
        SandboxRunState.FAILED -> tokens.statusError
        SandboxRunState.STOPPED -> tokens.statusStopped
    }
}

fun runStateLabel(runState: SandboxRunState): String = when (runState) {
    SandboxRunState.RUNNING -> "Running"
    SandboxRunState.PAUSED -> "Paused"
    SandboxRunState.FROZEN -> "Frozen"
    SandboxRunState.STARTING -> "Starting"
    SandboxRunState.FREEZING -> "Freezing"
    SandboxRunState.RESTORING -> "Restoring"
    SandboxRunState.FAILED -> "Failed"
    SandboxRunState.STOPPED -> "Stopped"
}

fun shortImageRef(imageRef: String): String =
    imageRef.removePrefix("docker.io/library/").removePrefix("docker.io/")
