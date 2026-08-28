package blbl.cat3399.core.tv

/**
 * Actions bindable to homepage gamepad buttons (L1 / R1 / START).
 * Stored as stable string keys in prefs.
 */
object GamepadMainActions {
    const val PREV_TAB = "prev_tab"
    const val NEXT_TAB = "next_tab"
    const val TOGGLE_SIDEBAR = "toggle_sidebar"
    const val NONE = "none"

    val ALL: List<String> =
        listOf(
            PREV_TAB,
            NEXT_TAB,
            TOGGLE_SIDEBAR,
            NONE,
        )

    fun isValid(action: String): Boolean = ALL.contains(action)

    fun label(action: String): String =
        when (action) {
            PREV_TAB -> "切换到上一个标签"
            NEXT_TAB -> "切换到下一个标签"
            TOGGLE_SIDEBAR -> "打开/关闭侧边栏"
            NONE -> "无操作"
            else -> label(PREV_TAB)
        }
}
