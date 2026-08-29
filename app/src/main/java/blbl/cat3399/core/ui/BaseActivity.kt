package blbl.cat3399.core.ui

import android.app.Activity
import android.content.Context
import android.os.BadParcelableException
import android.os.Bundle
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.theme.ThemePresets
import blbl.cat3399.core.tv.GamepadMotionDispatcher

open class BaseActivity : AppCompatActivity() {
    private var createdUiScaleFactor: Float? = null
    private var pendingUiScaleRecreate: Boolean = false
    protected var restoredState: Bundle? = null
        private set
    private val gamepadMotionDispatcher by lazy { GamepadMotionDispatcher(this) }

    protected open fun isGamepadMotionEnabled(): Boolean = true

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (isGamepadMotionEnabled()) {
            if (gamepadMotionDispatcher.dispatch(event)) {
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleGamepadConfirmBack(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * Fallback mapping for gamepad face buttons, only reached for events not consumed by a
     * subclass activity (players handle A/B themselves). A confirms the focused view; B goes back.
     */
    private fun handleGamepadConfirmBack(event: KeyEvent): Boolean {
        if (event.repeatCount > 0) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.action != KeyEvent.ACTION_UP) return false
                val focused = currentFocus ?: return false
                return focused.performClick()
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                if (event.action != KeyEvent.ACTION_UP) return false
                onBackPressedDispatcher.onBackPressed()
                return true
            }
        }
        return false
    }

    override fun onDestroy() {
        gamepadMotionDispatcher.release()
        super.onDestroy()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiDensity.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (shouldApplyThemePreset()) {
            ThemePresets.applyTo(this)
        }
        restoredState = sanitizeSavedInstanceState(savedInstanceState)
        super.onCreate(restoredState)
        // Views in this app draw their own focus backgrounds; the system's default focus
        // highlight would paint a full-surface wash when focus transiently lands on a
        // container (RecyclerView / page root) instead of an item.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            window.setDefaultFocusHighlightEnabled(false)
        }
        createdUiScaleFactor = UiScale.factor(this)
    }

    override fun onResume() {
        super.onResume()
        maybeRecreateOnUiScaleChanged()
    }

    fun reapplyWindowDisplayPolicy() {
        WindowDisplayPolicy.reapply(this)
    }

    protected open fun shouldRecreateOnUiScaleChange(): Boolean = true

    protected open fun shouldApplyThemePreset(): Boolean = true

    private fun sanitizeSavedInstanceState(savedInstanceState: Bundle?): Bundle? {
        val state = savedInstanceState ?: return null
        state.classLoader = javaClass.classLoader
        return try {
            // Force unparcel up front so a bad saved state degrades to cold start instead of
            // crashing inside Activity/Fragment restoration.
            state.keySet()
            state
        } catch (t: Throwable) {
            if (!isBadSavedStateThrowable(t)) throw t
            AppLog.w("SavedState", "drop corrupted savedInstanceState for ${javaClass.simpleName}", t)
            null
        }
    }

    private fun isBadSavedStateThrowable(t: Throwable): Boolean {
        var current: Throwable? = t
        while (current != null) {
            when (current) {
                is BadParcelableException,
                is ClassNotFoundException,
                -> return true
            }
            if (current.message?.contains("ClassNotFoundException when unmarshalling") == true) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun maybeRecreateOnUiScaleChanged() {
        if (!shouldRecreateOnUiScaleChange()) return
        if (isFinishing || isDestroyed) return

        val created = createdUiScaleFactor ?: UiScale.factor(this).also { createdUiScaleFactor = it }
        val now = UiScale.factor(this)
        if (created == now) return

        if (pendingUiScaleRecreate) return
        pendingUiScaleRecreate = true
        createdUiScaleFactor = now

        // Post to avoid triggering recreate while subclasses are still running their own onResume logic.
        window?.decorView?.post {
            pendingUiScaleRecreate = false
            if (!shouldRecreateOnUiScaleChange()) return@post
            if (isFinishing || isDestroyed) return@post
            recreate()
        }
    }

    protected fun applyCloseTransitionNoAnim() {
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
