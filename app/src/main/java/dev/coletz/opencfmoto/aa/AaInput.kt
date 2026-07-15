package dev.coletz.opencfmoto.aa

import android.os.SystemClock
import dev.coletz.opencfmoto.aa.proto.Input

/**
 * Injects touch events into the running Android Auto session so the rider can drive the AA UI from
 * an on-screen control surface in the app (start nav, pick music, change the map view) before
 * pocketing the phone.
 *
 * This works because our app IS the AA head unit: [ServiceDiscoveryResponse] already advertises an
 * InputSourceService with a touchscreen, so Google Android Auto accepts head-unit-originated touch
 * on the input channel ([Channel.ID_INP]). We just build the [Input.InputReport] and send it through
 * the live [AapTransport], which [AaReceiver] publishes here for the duration of the session.
 *
 * Coordinates are in AA's projected resolution (the profile's aaVideo width/height), not view pixels
 * — the caller maps from its view before calling.
 *
 * NOTE: touch is the ONLY input we send. A key/d-pad path was tried (advertising keycodes → AA focus
 * mode), but advertising keycodes destabilised the video session (decoder stalls ~30s in). See
 * PROJECT_STATUS.md "Feature 4" for the full write-up; touch-only keeps AA in its stable mode.
 */
object AaInput {
    /** Set by [AaReceiver] while an AAP session is live; null otherwise. */
    @Volatile var transport: AapTransport? = null

    enum class Action { DOWN, MOVE, UP }

    val isReady: Boolean get() = transport != null

    private fun proto(a: Action): Input.TouchEvent.PointerAction = when (a) {
        Action.DOWN -> Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN
        Action.MOVE -> Input.TouchEvent.PointerAction.TOUCH_ACTION_MOVE
        Action.UP -> Input.TouchEvent.PointerAction.TOUCH_ACTION_UP
    }

    /** Send one touch sample at AA-resolution [aaX],[aaY]. Returns false if no session is live. */
    fun sendTouch(aaX: Int, aaY: Int, action: Action): Boolean {
        val t = transport ?: return false
        val report = Input.InputReport.newBuilder()
            .setTimestamp(SystemClock.elapsedRealtimeNanos())
            .setTouchEvent(
                Input.TouchEvent.newBuilder()
                    .addPointerData(
                        Input.TouchEvent.Pointer.newBuilder()
                            .setX(aaX)
                            .setY(aaY)
                            .setPointerId(0)
                            .build()
                    )
                    .setActionIndex(0)
                    .setAction(proto(action))
                    .build()
            )
            .build()
        return try {
            t.send(AapMessage(Channel.ID_INP, Input.MsgType.EVENT_VALUE, report))
            true
        } catch (e: Exception) {
            AaLog.e("AaInput.sendTouch failed: ${e.message}")
            false
        }
    }
}
