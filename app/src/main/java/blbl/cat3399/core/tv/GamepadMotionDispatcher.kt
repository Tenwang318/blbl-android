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
 * Dead zone and enabled axes are read from prefs on every event so settings
 * changes apply immediately without recreating the dispatcher.
 */
class GamepadMotionDispatcher(
    private val activity: Activity,
) {
    companion object {
        const val AXIS_LEFT_STICK = 1
        const val AXIS_DPAD = 2
        const val AXIS_RIGHT_STICK = 4

        private const val DEFAULT_REPEAT_INTERVAL_MS = 50L
        private const val TAG = "GamepadMotion"
    }

    private val repeatIntervalMs: Long = DEFAULT_REPEAT_INTERVAL_MS

    private fun enabledAxes(): Int {
        var axes = AXIS_LEFT_STICK or AXIS_DPAD
        if (BiliClient.prefs.gamepadRightStickEnabled) {
            axes = axes or AXIS_RIGHT_STICK
        }
        return axes
    }

    private fun deadZone(): Float = BiliClient.prefs.gamepadDeadZonePercent / 100f

    private var lastHatX = 0f
    private var lastHatY = 0f
    private var lastXAxis = 0f
    private var lastYAxis = 0f
    private var lastZAxis = 0f
    private var lastRZAxis = 0f

    private data class DirectionState(
        val keyCode: Int,
        val lastDispatchMs: Long,
    )

    private var directionState: DirectionState? = null

    private fun dispatchDpadKey(keyCode: Int, action: Int) {
        val eventTime = SystemClock.uptimeMillis()
        val event = KeyEvent(
            eventTime,
            eventTime,
            action,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
            InputDevice.SOURCE_KEYBOARD,
        )
        val consumed = activity.dispatchKeyEvent(event)
        AppLog.d(
            TAG,
            "dispatch motion->dpad key=$keyCode action=$action consumed=${if (consumed) 1 else 0}",
        )
    }

    fun dispatch(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) {
            return false
        }
        if (event.action != MotionEvent.ACTION_MOVE) {
            return false
        }

        // Read all enabled axes
        val axes = enabledAxes()
        val deadZoneValue = deadZone()
        val hatX: Float
        val hatY: Float
        val x: Float
        val y: Float
        val z: Float
        val rz: Float

        if (axes and AXIS_DPAD != 0) {
            hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            lastHatX = hatX
            lastHatY = hatY
        } else {
            hatX = 0f
            hatY = 0f
        }

        if (axes and AXIS_LEFT_STICK != 0) {
            x = event.getAxisValue(MotionEvent.AXIS_X)
            y = event.getAxisValue(MotionEvent.AXIS_Y)
            lastXAxis = x
            lastYAxis = y
        } else {
            x = 0f
            y = 0f
        }

        if (axes and AXIS_RIGHT_STICK != 0) {
            z = event.getAxisValue(MotionEvent.AXIS_Z)
            rz = event.getAxisValue(MotionEvent.AXIS_RZ)
            lastZAxis = z
            lastRZAxis = rz
        } else {
            z = 0f
            rz = 0f
        }

        val now = SystemClock.uptimeMillis()

        // Resolve direction from each source
        val hatDir = resolveDirection(hatX, hatY, deadZoneValue)
        val stickDir = resolveDirection(x, y, deadZoneValue)
        val rightDir = resolveDirection(z, rz, deadZoneValue)

        // Priority: D-pad hat > left stick > right stick
        val newDir = when {
            hatDir != DIRECTION_NONE -> hatDir
            stickDir != DIRECTION_NONE -> stickDir
            rightDir != DIRECTION_NONE -> rightDir
            else -> DIRECTION_NONE
        }

        if (newDir != DIRECTION_NONE) {
            updateDirection(newDir, now)
            return true
        }

        clearDirection()
        return false
    }

    private fun resolveDirection(
        x: Float,
        y: Float,
        deadZone: Float,
    ): Int {
        val absX = kotlin.math.abs(x)
        val absY = kotlin.math.abs(y)
        if (absX < deadZone && absY < deadZone) {
            return DIRECTION_NONE
        }
        return if (absX > absY) {
            if (x > 0) DIRECTION_RIGHT else DIRECTION_LEFT
        } else {
            if (y > 0) DIRECTION_DOWN else DIRECTION_UP
        }
    }

    private fun updateDirection(direction: Int, now: Long) {
        val keyCode = directionToKeyCode(direction)
        if (keyCode < 0) {
            clearDirection()
            return
        }

        val state = directionState
        if (state != null && state.keyCode == keyCode) {
            // Same direction held: repeat if interval elapsed
            if (now - state.lastDispatchMs >= repeatIntervalMs) {
                dispatchDpadKey(keyCode, KeyEvent.ACTION_DOWN)
                directionState = DirectionState(keyCode, now)
            }
            return
        }

        // Direction changed or new: release old, press new
        if (state != null) {
            dispatchDpadKey(state.keyCode, KeyEvent.ACTION_UP)
        }
        dispatchDpadKey(keyCode, KeyEvent.ACTION_DOWN)
        directionState = DirectionState(keyCode, now)
    }

    private fun clearDirection() {
        val state = directionState ?: return
        dispatchDpadKey(state.keyCode, KeyEvent.ACTION_UP)
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

    fun release() {
        clearDirection()
    }
}

private const val DIRECTION_NONE = 0
private const val DIRECTION_UP = 1
private const val DIRECTION_DOWN = 2
private const val DIRECTION_LEFT = 3
private const val DIRECTION_RIGHT = 4
