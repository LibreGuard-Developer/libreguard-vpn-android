package net.libreguard.vpn.viewmodel

enum class ConnectionIssueSettings { APP, VPN, NONE }

data class ConnectionIssue(
    val message: String,
    val settings: ConnectionIssueSettings = ConnectionIssueSettings.APP,
    val title: String? = null
)
