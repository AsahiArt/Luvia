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
                    connected = true,
                ),
                HostUiModel(
                    id = "laptop",
                    name = "Laptop",
                    address = "192.168.1.24",
                    sessionName = null,
                    connection = ConnectionBadge.Stale,
                    errorMessage = "Host key changed",
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
                connected = true,
            ),
            section = HostSection.Overview,
            onSection = {},
            terminal = null,
            onRequestControl = {},
            onSendText = {},
        )
    }
}

@Preview(name = "Pairing command", showBackground = true)
@Composable
private fun PairCommandPreview() {
    MaterialTheme {
        PairHostPane(
            command = "luvia-host pair --name 'Pixel 9' --role controller --key 'ssh-ed25519 AAAA...'",
            authorizedKeysLine = "ssh-ed25519 AAAA...",
            fingerprint = "SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDE",
            errorMessage = null,
            completing = false,
            onBegin = { _, _ -> },
            onCopyCommand = {},
            onComplete = {},
            onCancel = {},
        )
    }
}

@Preview(name = "Pairing label", showBackground = true)
@Composable
private fun PairLabelPreview() {
    MaterialTheme {
        PairHostPane(
            command = null,
            authorizedKeysLine = null,
            fingerprint = null,
            errorMessage = null,
            completing = false,
            onBegin = { _, _ -> },
            onCopyCommand = {},
            onComplete = {},
            onCancel = {},
        )
    }
}

