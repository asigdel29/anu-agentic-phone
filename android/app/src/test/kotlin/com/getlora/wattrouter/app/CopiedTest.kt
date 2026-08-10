// CopiedTest.kt: a class name as something a model can act on.
//
// History
//   2026-08-09  A. Sigdel  Created.
//
// On the JVM. The mapping is the half of the conformance that is a decision
// rather than a framework call, and it is a pure function of a string.

package com.getlora.wattrouter.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CopiedTest {
    @Test
    fun aFrameworkWidgetIsItsRole() {
        assertEquals("button", roleOf("android.widget.Button"))
        assertEquals("field", roleOf("android.widget.EditText"))
        assertEquals("text", roleOf("android.widget.TextView"))
        assertEquals("image", roleOf("android.widget.ImageView"))
    }

    @Test
    fun everyToolkitSubclassesThoseAndTheyAreStillTheRole() {
        // The reason the test is on the suffix. A model shown
        // "androidx.appcompat.widget.AppCompatButton" learns nothing it can use.
        assertEquals("button", roleOf("androidx.appcompat.widget.AppCompatButton"))
        assertEquals("field", roleOf("com.google.android.material.textfield.TextInputEditText"))
        assertEquals("text", roleOf("androidx.appcompat.widget.AppCompatTextView"))
    }

    @Test
    fun aThingThatScrollsIsWorthTellingApartFromAThingThatDoesNot() {
        assertEquals("list", roleOf("androidx.recyclerview.widget.RecyclerView"))
        assertEquals("list", roleOf("android.widget.ListView"))
        assertEquals("scroll", roleOf("android.widget.ScrollView"))
    }

    @Test
    fun somethingToSwitchOnIsNotSomethingToPress() {
        assertEquals("toggle", roleOf("android.widget.Switch"))
        assertEquals("toggle", roleOf("android.widget.CheckBox"))
    }

    @Test
    fun theThingsEndingInButtonThatAreNotButtons() {
        // The order of the branches, and it is the whole of this test's point:
        // both of these end in Button, so a Button branch above them swallows
        // them and the model is told to press what it should set.
        assertEquals("choice", roleOf("android.widget.RadioButton"))
        assertEquals("toggle", roleOf("android.widget.ToggleButton"))
    }

    @Test
    fun anImageButtonIsAButtonRatherThanAnImage() {
        // Also ends in Button, and here that is the right answer.
        assertEquals("button", roleOf("android.widget.ImageButton"))
    }

    @Test
    fun anythingUnrecognisedIsAViewRatherThanAGuess() {
        // Honest, and enough: prune drops the ones that say nothing, so naming
        // a layout precisely buys nothing.
        assertEquals("view", roleOf("android.widget.FrameLayout"))
        assertEquals("view", roleOf("com.example.app.SomethingCustom"))
        assertEquals("view", roleOf(null))
        assertEquals("view", roleOf(""))
    }
}
