package dev.vaultdown.app

import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tasklist.TaskListPlugin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TaskTouchTest {
    @Test fun dispatchedTapChecksAndUnchecksTask() {
        val context = RuntimeEnvironment.getApplication()
        val markwon = Markwon.builder(context).usePlugin(TaskListPlugin.create(context)).build()
        for (source in listOf("- [ ] task", "- [x] task", "    - [ ] code")) {
            val view = TextView(context)
            view.setTextIsSelectable(true)
            markwon.setMarkdown(view, source)
            view.movementMethod = null
            view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, 600, view.measuredHeight)
            var changed: String? = null
            installTaskTouchHandler(view, source) { changed = it }
            val y = view.layout.getLineBottom(0) / 2f
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(0, 10, action, 5f, y, 0)
                view.dispatchTouchEvent(event)
                event.recycle()
            }
            when (source) {
                "- [ ] task" -> assertEquals("- [x] task", changed)
                "- [x] task" -> assertEquals("- [ ] task", changed)
                else -> assertNull(changed)
            }
        }
    }
}
