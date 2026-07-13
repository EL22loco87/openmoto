// Adapted from headunit-revived (AGPLv3): aap/AapMessageHandlerType.kt (video + control only)
package dev.coletz.opencfmoto.aa

internal class AapMessageHandlerType(
    private val transport: AapTransport,
    private val aapVideo: AapVideo
) : AapMessageHandler {

    private val aapControl: AapControl = AapControlGateway(transport)

    @Throws(AapMessageHandler.HandleException::class)
    override fun handle(message: AapMessage) {
        val msgType = message.type

        // 1. Video stream first (highest priority for smooth display).
        if (message.channel == Channel.ID_VID) {
            if (aapVideo.process(message)) {
                if (msgType == 0 || msgType == 1) transport.sendMediaAck(message.channel)
                return
            }
        }

        // 2. Audio (video-only v1): we advertise an audio sink to keep AA happy but don't play it.
        //    ACK data buffers so AA's unacked window never stalls, then discard the PCM.
        if (message.isAudio && (msgType == 0 || msgType == 1)) {
            transport.sendMediaAck(message.channel)
            return
        }

        // 3. Control message fallback.
        if (msgType in 0..31 || msgType in 32768..32799 || msgType in 65504..65535) {
            try {
                aapControl.execute(message)
            } catch (e: Exception) {
                AaLog.e(e)
                throw AapMessageHandler.HandleException(e)
            }
        } else {
            AaLog.e("Unknown msg_type: %d, flags: %d, channel: %d", msgType, message.flags, message.channel)
        }
    }
}
