// SensitiveActivity.kt: a view an application has marked as not for tools.
//
// History
//   2026-08-10  A. Sigdel  Created, to measure what accessibilityDataSensitive
//                          withholds from a service that is not declared one.
//
// FLAG_SECURE is the older question and SecureActivity is where it is asked.
// This is the newer one. Since API 34 a view can carry
// accessibilityDataSensitive, and the framework withholds it from every
// accessibility service that has not declared isAccessibilityTool. It is what
// an application reaches for once it learns FLAG_SECURE does not do what it
// hoped, so what it withholds from this service is a fact about what the agent
// can read.
//
// Two views, not one. A sensitive label beside an ordinary one is what tells a
// withheld node apart from a window that has not arrived: a read finding
// neither string proves nothing, and a read finding the ordinary one and not
// the sensitive one is the measurement.
//
// The API guard is a runtime one rather than an annotation. minSdk here is 29,
// so a bare call is a lint failure on a file the lint is right about, and a
// device below 34 should produce a window with two ordinary buttons rather
// than no window at all. The test asserts the level it needs.

package com.getlora.wattrouter.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout

class SensitiveActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(labelled(ORDINARY, sensitive = false))
                addView(labelled(SENSITIVE, sensitive = true))
            },
        )
    }

    /**
     * A button carrying one of the two labels.
     *
     * # Arguments
     * * `text`: what the node says, WHERE nothing else on any screen says it.
     * * `sensitive`: whether to mark it as not for tools, WHERE the mark is
     *   applied only from API 34 and silently skipped below it.
     */
    private fun labelled(text: String, sensitive: Boolean) =
        Button(this).apply {
            this.text = text
            contentDescription = text
            // The setter, not a property. It takes an int and the getter answers
            // a boolean, so Kotlin forms no synthetic property from the pair.
            if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
            }
        }

    companion object {
        /** Marked accessibilityDataSensitive. Nothing else produces this. */
        const val SENSITIVE = "zzsensitivebuttonzz"

        /** Not marked. The control that says the window arrived. */
        const val ORDINARY = "zzordinarybuttonzz"
    }
}
