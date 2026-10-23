package dev.wristbound.companion.core

import kotlinx.serialization.Serializable

/** A session as the watch sees it. */
@Serializable
data class WatchSession(val id: String, val title: String, val flags: Int)

/** A message as the watch sees it: role code, protocol flags and display text. */
@Serializable
data class WatchMessage(val id: String, val role: Int, val flags: Int, val text: String)

enum class ToolActivity { HIDDEN, SILENT, NOTIFY }
