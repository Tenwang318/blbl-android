package blbl.cat3399.core.tv

import android.view.InputDevice
import android.view.KeyEvent

object RemoteKeys {
    fun isRefreshKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_REFRESH ||
            keyCode == KeyEvent.KEYCODE_F5 ||
            keyCode == KeyEvent.KEYCODE_MENU
    }

    fun isGamepadKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR,
            -> true
            else -> false
        }
    }

    fun getConnectedGamepads(): List<InputDevice> {
        return InputDevice.getDeviceIds().toList().mapNotNull { id ->
            InputDevice.getDevice(id)?.takeIf { it.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD }
        }
    }

    fun hasGamepadConnected(): Boolean {
        return getConnectedGamepads().isNotEmpty()
    }
}
