// SecureActivity.kt — a window that says it may not be captured.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// FLAG_SECURE is what a banking app or a password manager sets. It exists in
// the suite rather than in the app because the question it answers is about a
// window this app does not own, and there is no app on a bare emulator that
// reliably sets it.
//
// The button carries a label nothing else on the screen would produce, so a
// read either contains that exact string or does not.

package com.getlora.wattrouter.app

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button

class SecureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        setContentView(
            Button(this).apply {
                text = SECRET
                contentDescription = SECRET
            },
        )
    }

    companion object {
        /** Nothing else on any screen produces this. */
        const val SECRET = "zzsecretbuttonzz"
    }
}
