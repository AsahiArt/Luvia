package tech.asahiart.luvia.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import tech.asahiart.luvia.ConnectionRoute
import tech.asahiart.luvia.R

@Composable
internal fun RouteChip(route: ConnectionRoute, modifier: Modifier = Modifier) {
    val tailnet = route == ConnectionRoute.Tailnet
    val background = when (route) {
        ConnectionRoute.Tailnet -> MaterialTheme.colorScheme.tertiaryContainer
        ConnectionRoute.Lan -> MaterialTheme.colorScheme.secondaryContainer
        ConnectionRoute.Unknown -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when (route) {
        ConnectionRoute.Tailnet -> MaterialTheme.colorScheme.onTertiaryContainer
        ConnectionRoute.Lan -> MaterialTheme.colorScheme.onSecondaryContainer
        ConnectionRoute.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = stringResource(
        when (route) {
            ConnectionRoute.Tailnet -> R.string.route_tailnet
            ConnectionRoute.Lan -> R.string.route_lan
            ConnectionRoute.Unknown -> R.string.route_unknown
        },
    )
    val description = stringResource(
        when (route) {
            ConnectionRoute.Tailnet -> R.string.route_tailnet_cd
            ConnectionRoute.Lan -> R.string.route_lan_cd
            ConnectionRoute.Unknown -> R.string.route_unknown_cd
        },
    )
    Row(
        modifier
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (tailnet) {
            Icon(
                painterResource(R.drawable.ic_network),
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = foreground,
            )
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = foreground, maxLines = 1)
    }
}
