package com.pigapocket.bootstrap

import android.content.Context
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView

/**
 * WebView variant that keeps Chromium's own InputConnection intact while
 * explicitly participating in Android's IME lifecycle.
 *
 * We never synthesize a BaseInputConnection: composition, selection and Clerk /
 * React controlled inputs must continue to use Chromium's implementation.
 */
class PigaWebView(context: Context) : WebView(context) {
    private val inputMethodManager =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        return super.onCreateInputConnection(outAttrs)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            requestFocusFromTouch()
            post { inputMethodManager.restartInput(this) }
        }
        return super.onTouchEvent(event)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            post { inputMethodManager.restartInput(this) }
        }
    }
}
