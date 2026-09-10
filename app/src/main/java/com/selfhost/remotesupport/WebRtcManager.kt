package com.selfhost.remotesupport

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import org.json.JSONObject
import org.webrtc.*

class WebRtcManager(
    private val context: Context,
    private val signaling: SignalingClient,
    private val turnUrl: String? = null,
    private val turnUsername: String? = null,
    private val turnCredential: String? = null,
) {
    private val eglBase = EglBase.create()
    private lateinit var factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var controlChannel: DataChannel? = null
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null

    private val activeDragPoints = mutableListOf<Pair<Float, Float>>()
    private var dragStartTime = 0L

    fun start(mediaProjectionPermission: Intent, screenWidth: Int, screenHeight: Int, screenDensity: Int) {
        initFactory()
        peerConnection = createPeerConnection()

        controlChannel = peerConnection!!.createDataChannel("control", DataChannel.Init())
        controlChannel!!.registerObserver(controlChannelObserver(screenWidth, screenHeight))

        val videoTrack = createScreenVideoTrack(mediaProjectionPermission, screenWidth, screenHeight, screenDensity)
        peerConnection!!.addTrack(videoTrack, listOf("screen-stream"))

        createAndSendOffer()
    }

    private fun initFactory() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions(),
        )
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private fun createPeerConnection(): PeerConnection {
        val iceServers = mutableListOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        )
        if (turnUrl != null) {
            iceServers.add(
                PeerConnection.IceServer.builder(turnUrl)
                    .setUsername(turnUsername ?: "")
                    .setPassword(turnCredential ?: "")
                    .createIceServer(),
            )
        }
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        return factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                signaling.sendSignal(
                    JSONObject()
                        .put("kind", "candidate")
                        .put("sdpMid", candidate.sdpMid)
                        .put("sdpMLineIndex", candidate.sdpMLineIndex)
                        .put("candidate", candidate.sdp),
                )
            }
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        })!!
    }

    private fun createScreenVideoTrack(permission: Intent, width: Int, height: Int, density: Int): VideoTrack {
        val capturer = ScreenCapturerAndroid(permission, object : MediaProjection.Callback() {
            override fun onStop() { }
        })
        screenCapturer = capturer

        val source = factory.createVideoSource(capturer.isScreencast)
        videoSource = source
        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        capturer.initialize(surfaceTextureHelper, context, source.capturerObserver)
        capturer.startCapture(width, height, 0)

        return factory.createVideoTrack("screen0", source)
    }

    private fun createAndSendOffer() {
        val constraints = MediaConstraints()
        val observer = object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {
                val desc = p0 ?: return
                val setObserver = object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }
                peerConnection!!.setLocalDescription(setObserver, desc)
                signaling.sendSignal(JSONObject().put("kind", "offer").put("sdp", desc.description))
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }
        peerConnection!!.createOffer(observer, constraints)
    }

    fun onRemoteSignal(payload: JSONObject) {
        val noopObserver = object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }
        when (payload.optString("kind")) {
            "answer" -> {
                val desc = SessionDescription(SessionDescription.Type.ANSWER, payload.getString("sdp"))
                peerConnection?.setRemoteDescription(noopObserver, desc)
            }
            "candidate" -> {
                peerConnection?.addIceCandidate(
                    IceCandidate(
                        payload.getString("sdpMid"),
                        payload.getInt("sdpMLineIndex"),
                        payload.getString("candidate"),
                    ),
                )
            }
        }
    }

    private fun controlChannelObserver(screenWidth: Int, screenHeight: Int) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(amount: Long) {}
        override fun onStateChange() {}
        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val service = RemoteControlAccessibilityService.instance ?: return

            fun px(nx: Double) = (nx * screenWidth).toFloat()
            fun py(ny: Double) = (ny * screenHeight).toFloat()

            when (json.optString("type")) {
                "tap" -> service.performTap(px(json.getDouble("x")), py(json.getDouble("y")))
                "drag-start" -> {
                    activeDragPoints.clear()
                    activeDragPoints.add(px(json.getDouble("x")) to py(json.getDouble("y")))
                    dragStartTime = System.currentTimeMillis()
                }
                "drag-move" -> activeDragPoints.add(px(json.getDouble("x")) to py(json.getDouble("y")))
                "drag-end" -> {
                    activeDragPoints.add(px(json.getDouble("x")) to py(json.getDouble("y")))
                    val duration = (System.currentTimeMillis() - dragStartTime).coerceIn(50, 5000)
                    service.performPath(activeDragPoints.toList(), duration)
                    activeDragPoints.clear()
                }
                "back" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                "home" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                "recents" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
            }
        }
    }

    fun stop() {
        controlChannel?.close()
        peerConnection?.close()
        screenCapturer?.stopCapture()
        screenCapturer?.dispose()
        videoSource?.dispose()
    }
}
