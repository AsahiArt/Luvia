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
    val background = if (tailnet) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val foreground = if (tailnet) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    val label = stringResource(if (tailnet) R.string.route_tailnet else R.string.route_lan)
    val description = stringResource(if (tailnet) R.string.route_tailnet_cd else R.string.route_lan_cd)
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
