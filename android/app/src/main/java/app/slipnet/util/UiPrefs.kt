package app.slipnet.util

/** PattNG "UI settings" switches shared by Settings, the profile list and the theme. */
data class UiPrefs(
    val deleteConfirm: Boolean = true,
    val twoColumn: Boolean = false,
    val showAllTab: Boolean = true,
    val dynamicColor: Boolean = false
)
