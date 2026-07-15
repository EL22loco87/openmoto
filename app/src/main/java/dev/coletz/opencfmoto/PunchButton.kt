package dev.coletz.opencfmoto

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat

/**
 * Neo-brutalist push button: an ink face with a paper border resting on a solid paper
 * "shadow" block offset to the bottom-right. Pressing slides the face (and its label) into
 * the block; the fill inverts to paper and the label to ink (via the button's textColor
 * state list), so the pressed button reads as one solid paper slab sitting in the socket.
 *
 * The style must keep android:background null — everything is drawn here.
 */
class PunchButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatButton(context, attrs) {

    private val density = resources.displayMetrics.density
    private val shadow = 5f * density
    private val strokeW = 2f * density

    private val ink = ContextCompat.getColor(context, R.color.ink)
    private val paper = ContextCompat.getColor(context, R.color.paper)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeW
        color = paper
    }

    /**
     * Engaged-mode accent: when set, the border, label, and shadow block all take this color
     * (mirroring the STOP button's danger treatment); null restores the paper monochrome.
     */
    var accent: Int? = null
        set(value) {
            if (field == value) return
            field = value
            val c = value ?: paper
            setTextColor(
                android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                    intArrayOf(ink, c),
                )
            )
            invalidate()
        }

    /** 0 = at rest (face top-left, shadow showing), 1 = fully pressed (face over the shadow). */
    private var press = 0f
        set(value) {
            field = value
            invalidate()
        }

    private var animator: ValueAnimator? = null

    override fun setPressed(pressed: Boolean) {
        val changed = isPressed != pressed
        super.setPressed(pressed)
        if (!changed) return
        if (pressed) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(press, if (pressed) 1f else 0f).apply {
            duration = if (pressed) 70 else 140
            interpolator = DecelerateInterpolator()
            addUpdateListener { press = it.animatedValue as Float }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val tone = accent ?: paper
        // Fixed shadow block at the bottom-right.
        fillPaint.color = tone
        canvas.drawRect(shadow, shadow, w, h, fillPaint)
        // Face slides into the block while pressed.
        val dx = press * shadow
        canvas.save()
        canvas.translate(dx, dx)
        fillPaint.color = if (isPressed) tone else ink
        canvas.drawRect(0f, 0f, w - shadow, h - shadow, fillPaint)
        val inset = strokeW / 2f
        strokePaint.color = tone
        canvas.drawRect(inset, inset, w - shadow - inset, h - shadow - inset, strokePaint)
        // super.onDraw centers the label in the full view bounds; pull it back so it centers
        // on the face (which is `shadow` smaller) and rides along with the press slide.
        canvas.translate(-shadow / 2f, -shadow / 2f)
        super.onDraw(canvas)
        canvas.restore()
    }
}
