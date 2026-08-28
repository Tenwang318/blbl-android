package blbl.cat3399.core.tv

import android.app.Activity
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient

/**
 * Dispatches joystick / D-pad motion events as synthesized DPAD key events.
 *
 * Supports:
 * - Left stick (AXIS_X / AXIS_Y)
 * - D-pad (AXIS_HAT_X / AXIS_HAT_Y)
 * - Right stick (AXIS_Z / AXIS_RZ) — optional, off by default (see prefs.gamepadRightStickEnabled)
 *
 * Behavior:
 * - Crossing the dead zone fires one step immediately; holding fires repeats only after an
 *   initial delay and at a fixed interval, so a brief push never skates across the grid.
 * - Direction changes are latched with hysteresis: near a diagonal the dominant axis must
 *   clearly win before focus turns, otherwise analog noise makes focus jump around.
 * - Dead zone and enabled axes are read from prefs on every event so settings changes apply
 *   immediately without recreating the dispatcher.
 */
class GamepadMotionDispatcher(
    private val activity: Activity,
) {
    companion object {
        const val AXIS_LEFT_STICK = 1
        const val AXIS_DPAD = 2
        const val AXIS_RIGHT_STICK = 4

        private const val DEFAULT_INITIAL_REPEAT_DELAY_MS = 350L
        private const val DEFAULT_REPEAT_INTERVAL_MS = 130L
        // When a direction is latched, the competing axis must dominate by this ratio to turn.
        private const val DIRECTION_SWITCH_MARGIN = 1.25f
        private const val TAG = "GamepadMotion"
    }

    private val initialRepeatDelayMs: Long = DEFAULT_INITIAL_REPEAT_DELAY_MS
    private val repeatIntervalMs: Long = DEFAULT_REPEAT_INTERVAL_MS

    private fun enabledAxes(): Int {
        var axes = AXIS_LEFT_STICK or AXIS_DPAD
        if (BiliClient.prefs.gamepadRightStickEnabled) {
            axes = axes or AXIS_RIGHT_STICK
        }
        return axes
    }

    private fun deadZone(): Float = BiliClient.prefs.gamepadDeadZonePercent / 100f

    private data class DirectionState(
        val keyCode: Int,
        val lastDispatchMs: Long,
        val repeatCount: Int,
    )

    private var directionState: DirectionState? = null

    fun dispatch(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) {
            return false
        }
        if (event.action != MotionEvent.ACTION_MOVE) {
            return false
        }

        val axes = enabledAxes()
        val deadZone = deadZone()

        // Source priority: D-pad hat > left stick > right stick. Only the first source that is
        // outside the dead zone drives navigation, so a controller that reports both does not
        // produce double steps.
        val hatActive = axes and AXIS_DPAD != 0 &&
            (kotlin.math.abs(event.getAxisValue(MotionEvent.AXIS_HAT_X)) >= deadZone ||
                kotlin.math.abs(event.getAxisValue(MotionEvent.AXIS_HAT_Y)) >= deadZone)
        val leftActive = axes and AXIS_LEFT_STICK != 0 &&
            (kotlin.math.abs(event.getAxisValue(MotionEvent.AXIS_X)) >= deadZone ||
                kotlin.math.abs(event.getAxisValue(MotionEvent.AXIS_Y)) >= deadZone)

        val primary: Int
        val secondary: Int
        when {
            hatActive -> {
                primary = MotionEvent.AXIS_HAT_X
                secondary = MotionEvent.AXIS_HAT_Y
            }
            leftActive -> {
                primary = MotionEvent.AXIS_X
                secondary = MotionEvent.AXIS_Y
            }
            axes and AXIS_RIGHT_STICK != 0 -> {
                primary = MotionEvent.AXIS_Z
                secondary = MotionEvent.AXIS_RZ
            }
            else -> {
                clearDirection()
                return false
            }
        }

        val primaryValue = event.getAxisValue(primary)
        val secondaryValue = event.getAxisValue(secondary)
        val now = SystemClock.uptimeMillis()
        val newDir = resolveDirection(primaryValue, secondaryValue, deadZone)

        if (newDir != DIRECTION_NONE) {
            updateDirection(newDir, primaryValue, secondaryValue, deadZone, now)
            return true
        }

        clearDirection()
        return false
    }

    /**
     * Resolves the dominant axis into a direction, or NONE when both axes sit inside the
     * dead zone.
     */
    private fun resolveDirection(
        primaryValue: Float,
        secondaryValue: Float,
        deadZone: Float,
    ): Int {
        val absPrimary = kotlin.math.abs(primaryValue)
        val absSecondary = kotlin.math.abs(secondaryValue)
        if (absPrimary < deadZone && absSecondary < deadZone) {
            return DIRECTION_NONE
        }
        return if (absPrimary > absSecondary) {
            if (primaryValue > 0) DIRECTION_RIGHT else DIRECTION_LEFT
        } else {
            if (secondaryValue > 0) DIRECTION_DOWN else DIRECTION_UP
        }
    }

    /**
     * Servo the latched direction with hysteresis, or switch when the new direction clearly
     * wins / the latched axis has returned inside the dead zone.
     */
    private fun updateDirection(
        direction: Int,
        primaryValue: Float,
        secondaryValue: Float,
        deadZone: Float,
        now: Long,
    ) {
        val keyCode = directionToKeyCode(direction)
        if (keyCode < 0) {
            clearDirection()
            return
        }

        val state = directionState
        if (state != null && state.keyCode == keyCode) {
            // Same direction held: repeat only after the initial delay, then at a fixed interval.
            val elapsed = now - state.lastDispatchMs
            val required = if (state.repeatCount == 0) initialRepeatDelayMs else repeatIntervalMs
            if (elapsed >= required) {
                val nextRepeat = state.repeatCount + 1
                dispatchDpadKey(keyCode, KeyEvent.ACTION_DOWN, nextRepeat)
                directionState = DirectionState(keyCode, now, nextRepeat)
            }
            return
        }

        // Hysteresis: while a direction is latched (its axis still beyond the dead zone), the
        // competing axis must dominate by a clear margin before focus turns. This keeps 45°
        // pushes from flip-flopping between two directions on every motion sample.
        if (state != null) {
            val currentKeyCode = state.keyCode
            val currentIsHorizontal = currentKeyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                currentKeyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            val newIsHorizontal = direction == DIRECTION_LEFT || direction == DIRECTION_RIGHT
            if (currentIsHorizontal != newIsHorizontal) {
                // primaryValue is the horizontal axis of the active source, secondaryValue the vertical one.
                val currentAbs = if (currentIsHorizontal) kotlin.math.abs(primaryValue) else kotlin.math.abs(secondaryValue)
                val newAbs = if (newIsHorizontal) kotlin.math.abs(primaryValue) else kotlin.math.abs(secondaryValue)
                if (currentAbs >= deadZone && newAbs < currentAbs * DIRECTION_SWITCH_MARGIN) {
                    // Keep the latched direction; just service its repeat clock.
                    val elapsed = now - state.lastDispatchMs
                    val required = if (state.repeatCount == 0) initialRepeatDelayMs else repeatIntervalMs
                    if (elapsed >= required) {
                        val nextRepeat = state.repeatCount + 1
                        dispatchDpadKey(currentKeyCode, KeyEvent.ACTION_DOWN, nextRepeat)
                        directionState = DirectionState(currentKeyCode, now, nextRepeat)
                    }
                    return
                }
            }
        }

        // Direction changed or new: release old, press new.
        if (state != null) {
            dispatchDpadKey(state.keyCode, KeyEvent.ACTION_UP, 0)
        }
        dispatchDpadKey(keyCode, KeyEvent.ACTION_DOWN, 0)
        directionState = DirectionState(keyCode, now, 0)
    }

    private fun clearDirection() {
        val state = directionState ?: return
        dispatchDpadKey(state.keyCode, KeyEvent.ACTION_UP, 0)
        directionState = null
    }

    private fun directionToKeyCode(direction: Int): Int {
        return when (direction) {
            DIRECTION_UP -> KeyEvent.KEYCODE_DPAD_UP
            DIRECTION_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            DIRECTION_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            DIRECTION_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> -1
        }
    }

    private fun dispatchDpadKey(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
    ) {
        val eventTime = SystemClock.uptimeMillis()
        val event = KeyEvent(
            eventTime,
            eventTime,
            action,
            keyCode,
            repeatCount,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
            InputDevice.SOURCE_KEYBOARD,
        )
        val consumed = activity.dispatchKeyEvent(event)
        AppLog.d(
            TAG,
            "dispatch motion->dpad key=$keyCode action=$action repeat=$repeatCount consumed=${if (consumed) 1 else 0}",
        )
    }

    fun release() {
        clearDirection()
    }
}

private const val DIRECTION_NONE = 0
private const val DIRECTION_UP = 1
private const val DIRECTION_DOWN = 2
private const val DIRECTION_LEFT = 3
private const val DIRECTION_RIGHT = 4
