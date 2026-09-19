package com.hesabat.twopersonmessenger

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** V6.5: one RTC worker + explicit Java AudioDeviceModule for Android 16/Xiaomi. */
class WebRtcClient(
    context: Context,
    private val video: Boolean,
    iceDtos: List<IceServerDto>,
    private val onIce: (IceCandidate) -> Unit,
    private val onState: (String) -> Unit,
    private val onRemoteVideo: ((VideoTrack) -> Unit)? = null
) {
    private val app = context.applicationContext
    private val thread = HandlerThread("TPM-WebRTC").apply { start() }
    private val h = Handler(thread.looper)
    private var egl: EglBase? = null
    private var adm: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: VideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    @Volatile private var closed = false

    val eglContext: EglBase.Context
        get() = requireNotNull(egl) { "EGL is available only for video calls" }.eglBaseContext

    init {
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        h.post {
            try { initRtc(iceDtos) } catch (t: Throwable) { failure = t } finally { latch.countDown() }
        }
        if (!latch.await(15, TimeUnit.SECONDS)) throw IllegalStateException("WebRTC init timeout")
        failure?.let { throw it }
    }

    private fun initRtc(iceDtos: List<IceServerDto>) {
        WebRtcRuntime.initialize(app)

        // Do not use WebRTC's native default ADM on this device path. The Java ADM
        // uses Android AudioRecord/AudioTrack and avoids hardware AEC/NS quirks.
        adm = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()

        val fb = PeerConnectionFactory.builder().setAudioDeviceModule(adm)
        if (video) {
            egl = EglBase.create()
            fb.setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
            fb.setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
        }
        factory = fb.createPeerConnectionFactory()

        val servers = iceDtos.mapNotNull { d ->
            if (d.urls.isEmpty()) null else PeerConnection.IceServer.builder(d.urls)
                .setUsername(d.username.orEmpty())
                .setPassword(d.credential.orEmpty())
                .createIceServer()
        }.ifEmpty {
            listOf(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer())
        }

        val cfg = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peer = factory!!.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) = onIce(c)
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) = onState(s.name)
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) = onState(s.name)
            override fun onTrack(t: RtpTransceiver) { (t.receiver.track() as? VideoTrack)?.let { onRemoteVideo?.invoke(it) } }
            override fun onAddTrack(r: RtpReceiver, streams: Array<out MediaStream>) { (r.track() as? VideoTrack)?.let { onRemoteVideo?.invoke(it) } }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(v: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }) ?: error("PeerConnection yaradıla bilmədi")

        audioSource = factory!!.createAudioSource(MediaConstraints())
        audioTrack = factory!!.createAudioTrack("audio0", audioSource).apply { setEnabled(true) }
        peer!!.addTrack(audioTrack, listOf("stream0"))
        if (video) createVideoTrack()
    }

    private fun createVideoTrack() {
        val e = Camera2Enumerator(app)
        val name = e.deviceNames.firstOrNull { e.isFrontFacing(it) } ?: e.deviceNames.firstOrNull() ?: return
        capturer = e.createCapturer(name, null) ?: return
        videoSource = factory!!.createVideoSource(false)
        surfaceHelper = SurfaceTextureHelper.create("TPM-Capture", eglContext)
        capturer!!.initialize(surfaceHelper, app, videoSource!!.capturerObserver)
        capturer!!.startCapture(640, 480, 24)
        videoTrack = factory!!.createVideoTrack("video0", videoSource).apply { setEnabled(true) }
        peer!!.addTrack(videoTrack, listOf("stream0"))
    }

    fun attachLocal(r: SurfaceViewRenderer) { r.init(eglContext, null); r.setMirror(true); r.setEnableHardwareScaler(true); h.post { videoTrack?.addSink(r) } }
    fun attachRemote(r: SurfaceViewRenderer) { r.init(eglContext, null); r.setMirror(false); r.setEnableHardwareScaler(true); refreshRemote(r) }
    fun refreshRemote(r: SurfaceViewRenderer) = h.post { peer?.receivers?.mapNotNull { it.track() as? VideoTrack }?.firstOrNull()?.addSink(r) }
    fun createOffer(cb: (SessionDescription) -> Unit) = h.post { peer?.createOffer(object : SimpleSdpObserver() { override fun onCreateSuccess(s: SessionDescription) { peer?.setLocalDescription(object : SimpleSdpObserver() { override fun onSetSuccess() { cb(s) } }, s) } }, MediaConstraints()) }
    fun createAnswer(cb: (SessionDescription) -> Unit) = h.post { peer?.createAnswer(object : SimpleSdpObserver() { override fun onCreateSuccess(s: SessionDescription) { peer?.setLocalDescription(object : SimpleSdpObserver() { override fun onSetSuccess() { cb(s) } }, s) } }, MediaConstraints()) }
    fun setRemote(type: String, sdp: String, done: () -> Unit = {}) = h.post { val t = if (type == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER; peer?.setRemoteDescription(object : SimpleSdpObserver() { override fun onSetSuccess() { done() } }, SessionDescription(t, sdp)) }
    fun addIce(mid: String?, index: Int, sdp: String) = h.post { peer?.addIceCandidate(IceCandidate(mid, index, sdp)) }
    fun mute(m: Boolean) = h.post { audioTrack?.setEnabled(!m) }
    fun camera(e: Boolean) = h.post { videoTrack?.setEnabled(e) }
    fun switchCamera() = h.post { (capturer as? CameraVideoCapturer)?.switchCamera(null) }

    fun close() {
        if (closed) return
        closed = true
        h.post {
            runCatching { capturer?.stopCapture() }
            runCatching { peer?.close() }
            runCatching { peer?.dispose() }
            runCatching { capturer?.dispose() }
            runCatching { surfaceHelper?.dispose() }
            runCatching { videoSource?.dispose() }
            runCatching { audioSource?.dispose() }
            runCatching { factory?.dispose() }
            runCatching { adm?.release() }
            runCatching { egl?.release() }
            thread.quitSafely()
        }
    }
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(s: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String) {}
    override fun onSetFailure(e: String) {}
}
