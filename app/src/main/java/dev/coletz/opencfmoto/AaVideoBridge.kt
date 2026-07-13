package dev.coletz.opencfmoto

/**
 * Shared hand-off between the Android Auto receiver (which owns the H.264 encoder/[VideoPipeline]
 * fed by AA's decoded video) and [EasyConnProber] (the bike PXC pipeline that pulls encoded
 * frames). When [pipeline] is non-null, the prober uses it as the video source instead of
 * creating its own Presentation/mirror pipeline — this is how Android Auto reaches the dash.
 */
object AaVideoBridge {
    @Volatile var pipeline: VideoPipeline? = null

    /**
     * Fired once (by the AA receiver) when decoded Android Auto video reaches a steady frame
     * rate — MainActivity uses this to auto-open the bike QR scanner so the AA→bike hand-off
     * doesn't depend on the user remembering to scan after starting the receiver.
     */
    @Volatile var onSteadyVideo: (() -> Unit)? = null
}
