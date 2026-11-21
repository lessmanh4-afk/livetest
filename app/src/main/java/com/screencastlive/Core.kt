package com.screencastlive

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-app log (last 200 lines), also written to Logcat with tag "ScreenCastLive". */
object AppLog {
    val lines = MutableStateFlow<List<String>>(emptyList())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun log(msg: String) {
        Log.d("ScreenCastLive", msg)
        val l = lines.value + "${fmt.format(Date())} $msg"
        lines.value = if (l.size > 200) l.takeLast(200) else l
    }
}

object BroadcastState {
    val state = MutableStateFlow("Idle")
    val running = MutableStateFlow(false)
    val viewers = MutableStateFlow(0)
}

data class StreamInfo(val id: String, val title: String, val viewers: Int, val startedAt: Long)

object Config {
    const val MAX_VIEWERS = 3

    // Metered TURN credentials (free tier). For a public release, regenerate them in the
    // Metered dashboard and fetch them from a small backend instead of shipping them in the APK.
    private const val TURN_USER = "f98dd7b60b12b175637b96b7"
    private const val TURN_PASS = "Fjozy0/RGNTVWGcR"

    fun iceServers(): List<PeerConnection.IceServer> {
        fun turn(url: String) = PeerConnection.IceServer.builder(url)
            .setUsername(TURN_USER).setPassword(TURN_PASS).createIceServer()
        return listOf(
            PeerConnection.IceServer.builder("stun:stun.relay.metered.ca:80").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            turn("turn:global.relay.metered.ca:80"),
            turn("turn:global.relay.metered.ca:80?transport=tcp"),
            turn("turn:global.relay.metered.ca:443"),
            turn("turns:global.relay.metered.ca:443?transport=tcp")
        )
    }
}

object Rtc {
    val eglBase: EglBase by lazy { EglBase.create() }
    lateinit var factory: PeerConnectionFactory

    @Synchronized
    fun init(ctx: Context) {
        if (::factory.isInitialized) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        AppLog.log("PeerConnectionFactory ready")
    }

    fun rtcConfig() = PeerConnection.RTCConfiguration(Config.iceServers()).apply {
        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    }
}

open class SdpAdapter(private val tag: String) : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) { AppLog.log("$tag create failed: $e") }
    override fun onSetFailure(e: String?) { AppLog.log("$tag set failed: $e") }
}

abstract class PcObserver : PeerConnection.Observer {
    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(p0: Boolean) {}
    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
    override fun onIceCandidate(p0: org.webrtc.IceCandidate?) {}
    override fun onIceCandidatesRemoved(p0: Array<out org.webrtc.IceCandidate>?) {}
    override fun onAddStream(p0: org.webrtc.MediaStream?) {}
    override fun onRemoveStream(p0: org.webrtc.MediaStream?) {}
    override fun onDataChannel(p0: org.webrtc.DataChannel?) {}
    override fun onRenegotiationNeeded() {}
}
