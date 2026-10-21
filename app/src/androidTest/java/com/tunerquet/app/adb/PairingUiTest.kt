package com.tunerquet.app.adb

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import com.tunerquet.app.MainActivity
import org.junit.Assert.*
import org.junit.Test

class PairingUiTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun activityRecreationDoesNotRestorePairingCode() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val views = descendants(activity.window.decorView)
                views.filterIsInstance<Button>().first { it.text.toString() == "Pair using a TV code" }.performClick()
                val inputs = descendants(activity.window.decorView).filterIsInstance<EditText>()
                assertEquals(3, inputs.size)
                val code = inputs.first { it.hint.toString() == "Six-digit pairing code" }
                assertFalse(code.isSaveEnabled)
                code.setText("123456")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val code = descendants(activity.window.decorView).filterIsInstance<EditText>()
                    .first { it.hint.toString() == "Six-digit pairing code" }
                assertEquals("", code.text.toString())
            }
        }
    }
}
