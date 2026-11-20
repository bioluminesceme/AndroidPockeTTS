package nl.user.pockettest

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.EditText
import android.widget.OverScroller
import android.util.Log
import org.json.JSONObject
import kotlin.math.roundToInt

/** Scroll on animation frames. Select text only after drag and momentum have stopped. */
class ReaderScroller(
    private val text: EditText,
    private val readOnly: () -> Boolean,
    private val browsing: (Boolean) -> Unit,
    private val selected: (Int, Int) -> Unit,
    private val doubleTap: (Int) -> Unit,
) {
    var revision = 0
        private set
    private val motion = OverScroller(text.context)
    private var readerGesture = false
    private var doubleGesture = false
    private var moved = false
    private var dragY = 0f
    private var firstY = 0
    private var flingVelocity = 0
    private val settle = Runnable {
        browsing(false)
        if (moved && readOnly()) {
            val layout = text.layout
            if (layout != null) {
                val row = layout.getLineForVertical(text.scrollY)
                Log.i("PocketScroll", JSONObject().put("event", "settled")
                    .put("velocity_y", flingVelocity).put("distance_px", text.scrollY - firstY).toString())
                selected(layout.getLineStart(row), text.scrollY - layout.getLineTop(row))
            }
        }
        moved = false
    }
    private val frame = object : Runnable {
        override fun run() {
            if (motion.computeScrollOffset()) {
                text.scrollTo(0, motion.currY.coerceIn(0, maximumY()))
                text.postOnAnimation(this)
            } else text.postDelayed(settle, 180)
        }
    }
    private fun maximumY(): Int = maxOf(0,
        (text.layout?.height ?: 0) - (text.height - text.totalPaddingTop - text.totalPaddingBottom))

    private val detector = GestureDetector(text.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onScroll(first: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!readerGesture || doubleGesture) return false
            dragY = (dragY + distanceY).coerceIn(0f, maximumY().toFloat())
            text.scrollTo(0, dragY.roundToInt())
            moved = true
            return true
        }
        override fun onFling(first: MotionEvent?, current: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (!readerGesture || doubleGesture || !moved) return false
            flingVelocity = -velocityY.roundToInt()
            motion.fling(0, text.scrollY, 0, flingVelocity, 0, 0, 0, maximumY())
            text.postOnAnimation(frame)
            return true
        }
        override fun onDoubleTap(event: MotionEvent): Boolean {
            val layout = text.layout ?: return false
            val y = event.y - text.totalPaddingTop + text.scrollY
            if (text.text.isBlank() || y < 0 || y >= layout.height) return false
            doubleGesture = true
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            text.onTouchEvent(cancel)
            cancel.recycle()
            browsing(false)
            text.performClick()
            doubleTap(text.getOffsetForPosition(event.x, event.y))
            return true
        }
    }).apply { setIsLongpressEnabled(false) }

    @SuppressLint("ClickableViewAccessibility")
    fun install() {
        text.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val interrupted = !motion.isFinished || moved
                cancel()
                readerGesture = readOnly()
                doubleGesture = false
                moved = interrupted && readerGesture
                firstY = text.scrollY
                dragY = firstY.toFloat()
                flingVelocity = 0
                browsing(true)
            }
            detector.onTouchEvent(event)
            val consumed = readerGesture || doubleGesture
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                if (doubleGesture || !readerGesture) browsing(false)
                else if (motion.isFinished) text.postDelayed(settle, 180)
            } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) cancel()
            consumed
        }
    }

    fun cancel() {
        revision++
        motion.forceFinished(true)
        text.removeCallbacks(frame)
        text.removeCallbacks(settle)
        moved = false
        browsing(false)
    }
}
