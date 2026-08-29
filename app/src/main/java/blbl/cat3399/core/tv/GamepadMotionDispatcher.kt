package blbl.cat3399.core.tv

import android.app.Activity
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import java.lang.ref.WeakReference

/**
 * Emulates four navigation buttons (NAV_UP/DOWN/LEFT/RIGHT) from the analog sticks, following
 * the wiliwili/borealis input model:
 *
 * - A direction "presses" once its stick axis passes the trigger point (default 50% throw,
 *   wiliwili uses 16383.5/32767). Left and right sticks are OR-ed, and the D-pad hat axes
 *   count too (button-style D-pads arrive as real key events and bypass this class).
 * - A new press fires one step immediately; holding fires repeats after 250ms, then every
 *   100ms — the same cadence borealis uses (BUTTOM_REPEAT_TRIGGER / BUTTON_REPEAT_DELAY).
 * - A repeat that did not move focus is a no-op until focus changes (borealis skips repeats
 *   while `repetitionOldFocus == currentFocus`), which keeps the focus from running away at
 *   the edge of a list.
 *
 * Each direction has an independent press state, so diagonal pushes step both axes.
 * Dead zone and enabled axes are read from prefs on every event so settings changes apply
 * immediately without recreating the dispatcher.
 */
class GamepadMotionDispatcher(
    private val activity: Activity,
) {
    companion object {
        // wiliwili/borealis half-throw trigger: 16383.5 out of a [-32768, 32767] axis range.
        const val DEFAULT_TRIGGER_PERCENT = 50

        // borealis: BUTTOM_REPEAT_TRIGGER (250ms) and BUTTON_REPEAT_DELAY (100ms).
        private const val REPEAT_TRIGGER_MS = 250L
        private const val REPEAT_DELAY_MS = 100L
        private const val TAG = "GamepadMotion"

        private const val DIRECTION_UP = 0
        private const val DIRECTION_DOWN = 1
        private const val DIRECTION_LEFT = 2
        private const val DIRECTION_RIGHT = 3
        private const val DIRECTION_COUNT = 4

        // A latched direction releases only below this fraction of the trigger point.
        private const val RELEASE_HYSTERESIS = 0.6f
    }

    private class NavState {
        var pressed: Boolean = false
        var repeatCount: Int = 0
        var nextRepeatAtMs: Long = 0L
    }

    private val navStates = Array(DIRECTION_COUNT) { NavState() }
    private var lastNavigateFocus: WeakReference<View>? = null

    private fun triggerValue(): Float = BiliClient.prefs.gamepadDeadZonePercent / 100f

    fun dispatch(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) {
            return false
        }
        if (event.action != MotionEvent.ACTION_MOVE) {
            return false
        }

        val trigger = triggerValue()
        val rightStickEnabled = BiliClient.prefs.gamepadRightStickEnabled

        val lx = event.getAxisValue(MotionEvent.AXIS_X)
        val ly = event.getAxisValue(MotionEvent.AXIS_Y)
        val rx = event.getAxisValue(MotionEvent.AXIS_Z)
        val ry = event.getAxisValue(MotionEvent.AXIS_RZ)
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

        // wiliwili: NAV_<dir> = leftStick <dir> || rightStick <dir> || dpad <dir>.
        // Release hysteresis: a latched direction only releases when the axis falls back to
        // 60% of the trigger point, so a stick hovering around the threshold keeps repeating
        // instead of flickering press/release (which kills the auto-repeat).
        val release = trigger * RELEASE_HYSTERESIS
        val pressedUp = hy < -trigger || (rightStickEnabled && ry < -trigger) ||
            (navStates[DIRECTION_UP].pressed && hy < -release)
        val pressedDown = hy > trigger || (rightStickEnabled && ry > trigger) ||
            (navStates[DIRECTION_DOWN].pressed && hy > release)
        val pressedLeft = hx < -trigger || (rightStickEnabled && rx < -trigger) ||
            (navStates[DIRECTION_LEFT].pressed && hx < -release)
        val pressedRight = hx > trigger || (rightStickEnabled && rx > trigger) ||
            (navStates[DIRECTION_RIGHT].pressed && hx > release)

        val now = SystemClock.uptimeMillis()
        val firedUp = updateNav(DIRECTION_UP, KeyEvent.KEYCODE_DPAD_UP, pressedUp, now)
        val firedDown = updateNav(DIRECTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, pressedDown, now)
        val firedLeft = updateNav(DIRECTION_LEFT, KeyEvent.KEYCODE_DPAD_LEFT, pressedLeft, now)
        val firedRight = updateNav(DIRECTION_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT, pressedRight, now)

        val anyPressed = pressedUp || pressedDown || pressedLeft || pressedRight
        return anyPressed || firedUp || firedDown || firedLeft || firedRight
    }

    /** Returns true when this call dispatched a key (edge press, repeat, or release). */
    private fun updateNav(direction: Int, keyCode: Int, pressed: Boolean, now: Long): Boolean {
        val state = navStates[direction]
        if (!pressed) {
            if (state.pressed) {
                state.pressed = false
                AppLog.d(TAG, "nav release dir=$direction")
                dispatchDpadKey(keyCode, KeyEvent.ACTION_UP, state.repeatCount)
                return true
            }
            return false
        }

        if (!state.pressed) {
            // Edge: fire one step, arm the repeat clock (borealis BUTTOM_REPEAT_TRIGGER).
            state.pressed = true
            AppLog.d(TAG, "nav press dir=$direction")
            state.repeatCount = 0
            if (navigate(direction, isRepeat = false)) {
                dispatchDpadKey(keyCode, KeyEvent.ACTION_DOWN, 0)
            }
            state.nextRepeatAtMs = now + REPEAT_TRIGGER_MS
            return true
        }

        if (now >= state.nextRepeatAtMs) {
            // Held: fire a repeat only when focus actually moved since the last navigation
            // (borealis skips repeats while repetitionOldFocus == currentFocus).
            if (navigate(direction, isRepeat = true)) {
                state.repeatCount++
                dispatchDpadKey(keyCode, KeyEvent.ACTION_DOWN, state.repeatCount)
            }
            state.nextRepeatAtMs = now + REPEAT_DELAY_MS
            return true
        }
        return false
    }

    /**
     * Dispatches one navigation step unless it is a repeat that cannot move focus. Returns
     * false when the step is suppressed (edge presses always navigate).
     */
    private fun navigate(direction: Int, isRepeat: Boolean): Boolean {
        val focusedBefore = activity.currentFocus
        if (isRepeat && lastNavigateFocus?.get() === focusedBefore) {
            return false
        }
        lastNavigateFocus = WeakReference(focusedBefore)
        return true
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
        for ((direction, state) in navStates.withIndex()) {
            if (state.pressed) {
                state.pressed = false
                dispatchDpadKey(directionToKeyCode(direction), KeyEvent.ACTION_UP, state.repeatCount)
            }
        }
        lastNavigateFocus = null
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
}
