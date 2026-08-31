package tech.asahiart.luvia.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "Phone", device = Devices.PHONE, showBackground = true)
@Preview(name = "Foldable", device = Devices.FOLDABLE, showBackground = true)
@Preview(name = "Tablet", device = Devices.TABLET, showBackground = true)
annotation class FormFactorPreviews

@FormFactorPreviews
@Composable
private fun HostListPreview() {
    MaterialTheme {
        HostListPane(
            hosts = listOf(
                HostUiModel(
                    id = "studio",
                    name = "Studio",
                    address = "studio.tailnet.ts.net",
                    sessionName = "main",
                    connection = ConnectionBadge.Live,
                    workingAgents = 2,
                    blockedAgents = 1,
                    completedAgents = 5,
                ),
                HostUiModel(
                    id = "laptop",
                    name = "Laptop",
                    address = "192.168.1.24",
                    sessionName = null,
                    connection = ConnectionBadge.Stale,
                ),
            ),
            selectedHostId = "studio",
            onSelect = {},
            onAddHost = {},
        )
    }
}

@FormFactorPreviews
@Composable
private fun HostDetailPreview() {
    MaterialTheme {
        HostDetailPane(
            host = HostUiModel(
                id = "studio",
                name = "Studio",
                address = "studio.tailnet.ts.net",
                sessionName = "main",
                connection = ConnectionBadge.Live,
                workingAgents = 2,
                blockedAgents = 1,
                completedAgents = 5,
                activeTask = "Implement the authenticated UHP bridge",
            ),
            section = HostSection.Overview,
            onSection = {},
            terminal = null,
            onRequestControl = {},
            onSendText = {},
        )
    }
}
