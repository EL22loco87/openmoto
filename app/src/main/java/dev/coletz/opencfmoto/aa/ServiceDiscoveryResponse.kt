// Adapted from headunit-revived (AGPLv3): aap/protocol/messages/ServiceDiscoveryResponse.kt
// Video-only head-unit profile for OpenCfMoto: advertises 800x480 H.264 video (GPU-scaled to
// the bike's 800x384 encoder), a driving-status sensor, a touchscreen input service, and a PCM
// microphone (required for AA bring-up). Audio sink, navigation-status, media-playback and
// bluetooth services from HUR are intentionally dropped (video-only v1 — see docs 03 M5).
package dev.coletz.opencfmoto.aa

import com.google.protobuf.Message
import dev.coletz.opencfmoto.aa.proto.Common
import dev.coletz.opencfmoto.aa.proto.Control
import dev.coletz.opencfmoto.aa.proto.Media
import dev.coletz.opencfmoto.aa.proto.Sensors

class ServiceDiscoveryResponse
    : AapMessage(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_SERVICE_DISCOVERY_RESPONSE_VALUE, makeProto()) {

    companion object {
        // AA won't project at the bike's native 800x384; request the smallest fixed enum
        // (800x480) and let the decoder->encoder Surface scale it down to 800x384.
        const val AA_WIDTH = 800
        const val AA_HEIGHT = 480
        private const val DENSITY_DPI = 160

        private fun makeProto(): Message {
            val services = mutableListOf<Control.Service>()

            // --- Sensor service (driving status + night) ---
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_SEN
                service.sensorSourceService = Control.Service.SensorSourceService.newBuilder().also { s ->
                    s.addSensors(makeSensorType(Sensors.SensorType.DRIVING_STATUS))
                    s.addSensors(makeSensorType(Sensors.SensorType.NIGHT))
                }.build()
            }.build())

            // --- Video service (800x480 H.264 baseline, 30 fps) ---
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_VID
                service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also { sink ->
                    sink.availableType = Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
                    sink.audioType = Media.AudioStreamType.NONE
                    sink.availableWhileInCall = true
                    sink.addVideoConfigs(
                        Control.Service.MediaSinkService.VideoConfiguration.newBuilder().apply {
                            codecResolution = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480
                            frameRate = Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30
                            setDensity(DENSITY_DPI)
                            setMarginWidth(0)
                            setMarginHeight(0)
                            setVideoCodecType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
                        }.build()
                    )
                }.build()
            }.build())

            // --- Input service (touchscreen; declared for compatibility, driven by voice in v1) ---
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_INP
                service.inputSourceService = Control.Service.InputSourceService.newBuilder().also { inp ->
                    inp.touchscreen = Control.Service.InputSourceService.TouchConfig.newBuilder().apply {
                        setWidth(AA_WIDTH)
                        setHeight(AA_HEIGHT)
                    }.build()
                }.build()
            }.build())

            // --- Audio2 sink (system sounds). Android Auto rejects a head unit that advertises
            //     no audio sink and drops the connection right after service discovery, so we
            //     always advertise this even though v1 discards the PCM (see AapMessageHandlerType). ---
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_AU2
                service.mediaSinkService = Control.Service.MediaSinkService.newBuilder().also { sink ->
                    sink.availableType = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
                    sink.audioType = Media.AudioStreamType.SYSTEM
                    sink.addAudioConfigs(
                        Media.AudioConfiguration.newBuilder().apply {
                            sampleRate = 16000
                            numberOfBits = 16
                            numberOfChannels = 1
                        }.build()
                    )
                }.build()
            }.build())

            // --- Microphone service (required for AA connection / Assistant) ---
            services.add(Control.Service.newBuilder().also { service ->
                service.id = Channel.ID_MIC
                service.mediaSourceService = Control.Service.MediaSourceService.newBuilder().also { src ->
                    src.type = Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM
                    src.audioConfig = Media.AudioConfiguration.newBuilder().apply {
                        sampleRate = 16000
                        numberOfBits = 16
                        numberOfChannels = 1
                    }.build()
                }.build()
            }.build())

            return Control.ServiceDiscoveryResponse.newBuilder().apply {
                make = "OpenCfMoto"
                model = "MotoPlay"
                year = "2024"
                vehicleId = "opencfmoto"
                headUnitModel = "CFDL16-6GUV"
                headUnitMake = "CFMoto"
                headUnitSoftwareBuild = "1"
                headUnitSoftwareVersion = "0.1.0"
                driverPosition = Control.DriverPosition.DRIVER_POSITION_LEFT
                canPlayNativeMediaDuringVr = false
                hideProjectedClock = false
                setDisplayName("OpenCfMoto")
                setHeadunitInfo(Common.HeadUnitInfo.newBuilder().apply {
                    setHeadUnitMake("CFMoto")
                    setHeadUnitModel("CFDL16-6GUV")
                    setMake("OpenCfMoto")
                    setModel("MotoPlay")
                    setYear("2024")
                    setVehicleId("opencfmoto")
                    setHeadUnitSoftwareBuild("1")
                    setHeadUnitSoftwareVersion("0.1.0")
                }.build())
                addAllServices(services)
            }.build()
        }

        private fun makeSensorType(type: Sensors.SensorType): Control.Service.SensorSourceService.Sensor =
            Control.Service.SensorSourceService.Sensor.newBuilder().setType(type).build()
    }
}
