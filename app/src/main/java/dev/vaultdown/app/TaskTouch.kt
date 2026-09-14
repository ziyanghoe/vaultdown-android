package dev.vaultdown.app

import android.text.Spanned
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.TextView
import io.noties.markwon.ext.tasklist.TaskListSpan
import kotlin.math.abs

internal fun installTaskTouchHandler(view: TextView, markdown: String, onToggle: (String) -> Unit) {
    var pressed = -1
    var downX = 0f
    var downY = 0f
    val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    fun taskAt(event: MotionEvent): Int {
        val layout = view.layout ?: return -1
        val text = view.text as? Spanned ?: return -1
        val y = event.y - view.totalPaddingTop + view.scrollY
        if (y < 0 || y >= layout.height) return -1
        val line = layout.getLineForVertical(y.toInt())
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line)
        val tasks = text.getSpans(0, text.length, TaskListSpan::class.java).sortedBy { text.getSpanStart(it) }
        // Allow the entire task row, including indented checkboxes, to be tapped.
        return tasks.indexOfLast { text.getSpanStart(it) < end && text.getSpanEnd(it) > start }
    }
    view.setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = taskAt(event)
                downX = event.x
                downY = event.y
                pressed >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                val handled = pressed >= 0
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) pressed = -1
                handled
            }
            MotionEvent.ACTION_UP -> {
                val index = pressed
                pressed = -1
                if (index >= 0 && taskAt(event) == index &&
                    abs(event.x - downX) <= slop && abs(event.y - downY) <= slop) {
                    toggleMarkdownTask(markdown, index)?.let(onToggle)
                    view.performClick()
                    true
                } else false
            }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; false }
            else -> false
        }
    }
}
