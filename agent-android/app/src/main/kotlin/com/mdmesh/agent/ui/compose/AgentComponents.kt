package com.mdmesh.agent.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.mdmesh.agent.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentTopBar(title: String, loading: Boolean, onBack: () -> Unit, onRefresh: () -> Unit, onAccount: () -> Unit) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.network_back), "Quay lại") } },
        actions = {
            IconButton(onClick = onRefresh, enabled = !loading) { Icon(painterResource(R.drawable.network_refresh), "Làm mới") }
            IconButton(onClick = onAccount) { Icon(painterResource(R.drawable.network_account), "Tài khoản quản trị") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentRoleSwitch(admin: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        listOf("Của thiết bị", "Quản trị").forEachIndexed { index, title ->
            SegmentedButton(
                selected = admin == (index == 1), onClick = { onChange(index == 1) },
                shape = SegmentedButtonDefaults.itemShape(index, 2), enabled = enabled, icon = {},
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(title) }
        }
    }
}

@Composable
internal fun AgentAppIcon(packageName: String, name: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val preview = LocalInspectionMode.current
    val bitmap by produceState<ImageBitmap?>(null, packageName, preview) {
        if (!preview) value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    val image = bitmap
    if (image != null) Image(image, contentDescription = null, modifier = modifier.size(36.dp))
    else Surface(modifier.size(36.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Box(contentAlignment = androidx.compose.ui.Alignment.Center) { Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer) }
    }
}

/** A compact, accessible row rather than an individual card and action button per request. */
@Composable
internal fun AgentAppRow(
    name: String, subtitle: String, packageName: String, modifier: Modifier = Modifier,
    detail: String = "", enabled: Boolean = true, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        leadingContent = { AgentAppIcon(packageName, name) }, trailingContent = trailing,
        modifier = modifier.fillMaxWidth().then(if (onClick == null) Modifier else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
internal fun AgentStatusBadge(label: String, status: String) {
    val (background, foreground) = when (status) {
        "approved", "installed" -> Color(0xFFE8F4EA) to Color(0xFF176534)
        "pending" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        "rejected", "revoked" -> Color(0xFFFCEAE8) to Color(0xFF9B2924)
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(4.dp), color = background) {
        Text(label, color = foreground, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp).padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
internal fun AgentMessage(message: String, modifier: Modifier = Modifier, error: Boolean = false) {
    Text(message, modifier, style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}
