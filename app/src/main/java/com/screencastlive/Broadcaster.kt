package com.screencastlive

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap

/**
 * Captures the screen and streams it to viewers over WebRTC.
 * Stream id == owner uid. Signaling lives under signaling/{uid}/viewers/{viewerUid}.
 * Must be created and started from ScreenBroadcastService AFTER startForeground(mediaProjection).
 */
class Broadcaster(
    private val ctx: Context,
    private val uid: String,
    private val title: String,
    private val projectionData: Intent,
    private val onStopped: () -> Unit
) {
    private val db = FirebaseDatabase.getInstance()
    private val streamRef = db.getReference("streams/$uid")
    private val sigRoot = db.getReference("signaling/$uid")
    private val sigRef = db.getReference("signaling/$uid/viewers")
    private val handler = Handler(Looper.getMainLooper())

    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private val peers = ConcurrentHashMap<String, Peer>()
    private var childL: ChildEventListener? = null
    private var connL: ValueEventListener? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    @Volatile private var netLost = false
    @Volatile private var stopped = false

    fun start() {
        Rtc.init(ctx)

        AppLog.log("Step 1: creating ScreenCapturerAndroid")
        val cap = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() {
                AppLog.log("MediaProjection stopped (user or system)")
                handler.post { if (!stopped) onStopped() }
            }
        })
        capturer = cap
        helper = SurfaceTextureHelper.create("ScreenCaptureThread", Rtc.eglBase.eglBaseContext)
        val src = Rtc.factory.createVideoSource(true)
        source = src
        cap.initialize(helper, ctx, src.capturerObserver)

        val (w, h) = captureSize()
        AppLog.log("Step 2: startCapture ${w}x$h @24fps")
        cap.startCapture(w, h, 24)
        AppLog.log("Step 2 OK: capture started")

        track = Rtc.factory.createVideoTrack("screen", src).also { it.setEnabled(true) }

        AppLog.log("Step 3: publishing stream in Firebase")
        sigRoot.removeValue()
        streamRef.setValue(
            mapOf(
                "ownerUid" to uid, "title" to title, "status" to "live",
                "startedAt" to ServerValue.TIMESTAMP, "viewerCount" to 0
            )
        )

        // Keep status correct across reconnects: whenever Firebase (re)connects, mark live
        // and re-arm the server-side onDisconnect -> offline.
        connL = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (s.getValue(Boolean::class.java) == true && !stopped) {
                    AppLog.log("Firebase connected -> status live")
                    streamRef.child("status").onDisconnect().setValue("offline")
                    streamRef.child("status").setValue("live")
                } else if (!stopped) {
                    AppLog.log("Firebase disconnected")
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        db.getReference(".info/connected").addValueEventListener(connL!!)

        childL = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) {
                val k = s.key ?: return
                addPeer(k)
            }
            override fun onChildRemoved(s: DataSnapshot) {
                val k = s.key ?: return
                removePeer(k)
            }
            override fun onChildChanged(s: DataSnapshot, prev: String?) {}
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) { AppLog.log("signaling cancelled: ${e.message}") }
        }
        sigRef.addChildEventListener(childL!!)

        registerNetwork()
        BroadcastState.state.value = "Live"
        AppLog.log("Broadcast is LIVE")
    }

    private fun captureSize(): Pair<Int, Int> {
        val dm = ctx.resources.displayMetrics
        val scale = minOf(1f, 1280f / maxOf(dm.widthPixels, dm.heightPixels))
        val w = ((dm.widthPixels * scale).toInt() / 2) * 2
        val h = ((dm.heightPixels * scale).toInt() / 2) * 2
        return Pair(w, h)
    }

    private fun registerNetwork() {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                netLost = true
                AppLog.log("Network lost")
                BroadcastState.state.value = "Reconnecting"
            }
            override fun onAvailable(network: Network) {
                if (!netLost) return
                netLost = false
                handler.post {
                    if (stopped) return@post
                    AppLog.log("Network back -> ICE restart for ${peers.size} viewer(s)")
                    peers.values.forEach { it.restart() }
                    BroadcastState.state.value = "Live"
                }
            }
        }
        netCb = cb
        cm.registerDefaultNetworkCallback(cb)
    }

    private fun addPeer(vid: String) {
        if (stopped) return
        peers.remove(vid)?.close()
        if (peers.size >= Config.MAX_VIEWERS) {
            AppLog.log("Viewer limit reached, rejecting $vid")
            sigRef.child(vid).child("error").setValue("full")
            return
        }
        val p = Peer(vid)
        peers[vid] = p
        p.start()
        updateCount()
    }

    private fun removePeer(vid: String) {
        peers.remove(vid)?.close()
        updateCount()
    }

    private fun updateCount() {
        BroadcastState.viewers.value = peers.size
        if (!stopped) streamRef.child("viewerCount").setValue(peers.size)
    }

    fun stop() {
        if (stopped) return
        stopped = true
        AppLog.log("Stopping broadcast")
        try { netCb?.let { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } } catch (_: Throwable) {}
        connL?.let { db.getReference(".info/connected").removeEventListener(it) }
        childL?.let { sigRef.removeEventListener(it) }
        peers.values.forEach { it.close() }
        peers.clear()
        try { capturer?.stopCapture() } catch (e: Throwable) { AppLog.log("stopCapture: ${e.message}") }
        try { capturer?.dispose() } catch (_: Throwable) {}
        try { track?.dispose() } catch (_: Throwable) {}
        try { source?.dispose() } catch (_: Throwable) {}
        try { helper?.dispose() } catch (_: Throwable) {}
        streamRef.child("status").setValue("offline")
        streamRef.child("status").onDisconnect().cancel()
        streamRef.child("viewerCount").setValue(0)
        sigRoot.removeValue()
        BroadcastState.viewers.value = 0
    }

    /** One PeerConnection per viewer. */
    private inner class Peer(val vid: String) {
        private val ref = sigRef.child(vid)
        private var pc: PeerConnection? = null
        private var n = 0
        private var remoteSet = false
        private val pending = ArrayList<IceCandidate>()
        private var answerL: ValueEventListener? = null
        private var candL: ChildEventListener? = null
        private var attempts = 0
        private val restartRunnable = Runnable { attempts++; restart() }

        fun start() {
            AppLog.log("[$vid] new viewer")
            val conn = Rtc.factory.createPeerConnection(Rtc.rtcConfig(), object : PcObserver() {
                override fun onIceCandidate(c: IceCandidate?) {
                    c ?: return
                    ref.child("bc").push().setValue(
                        mapOf("mid" to c.sdpMid, "idx" to c.sdpMLineIndex, "sdp" to c.sdp)
                    )
                }
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                    AppLog.log("[$vid] ICE $s")
                    handler.post {
                        when (s) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> {
                                attempts = 0
                                handler.removeCallbacks(restartRunnable)
                            }
                            PeerConnection.IceConnectionState.DISCONNECTED -> scheduleRestart(5000)
                            PeerConnection.IceConnectionState.FAILED -> scheduleRestart(
                                minOf(2000L shl minOf(attempts, 4), 30000L)
                            )
                            else -> {}
                        }
                    }
                }
            })
            if (conn == null) { AppLog.log("[$vid] createPeerConnection returned null"); return }
            pc = conn

            val sender = conn.addTrack(track, listOf("screen"))
            try {
                val params = sender.parameters
                params.encodings.forEach { it.maxBitrateBps = 2_500_000; it.maxFramerate = 24 }
                sender.setParameters(params)
            } catch (e: Throwable) { AppLog.log("[$vid] bitrate params: ${e.message}") }

            answerL = object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    val sdp = s.child("sdp").getValue(String::class.java) ?: return
                    val an = (s.child("n").value as? Number)?.toInt() ?: return
                    val p = pc ?: return
                    if (an != n || p.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return
                    p.setRemoteDescription(object : SdpAdapter("setRemote(answer)") {
                        override fun onSetSuccess() {
                            AppLog.log("[$vid] answer applied")
                            synchronized(pending) {
                                remoteSet = true
                                pending.forEach { p.addIceCandidate(it) }
                                pending.clear()
                            }
                        }
                    }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
                }
                override fun onCancelled(e: DatabaseError) {}
            }
            ref.child("answer").addValueEventListener(answerL!!)

            candL = object : ChildEventListener {
                override fun onChildAdded(s: DataSnapshot, prev: String?) {
                    val sdp = s.child("sdp").getValue(String::class.java) ?: return
                    val mid = s.child("mid").getValue(String::class.java)
                    val idx = (s.child("idx").value as? Number)?.toInt() ?: 0
                    val cand = IceCandidate(mid, idx, sdp)
                    synchronized(pending) {
                        if (remoteSet) pc?.addIceCandidate(cand) else pending.add(cand)
                    }
                }
                override fun onChildChanged(s: DataSnapshot, prev: String?) {}
                override fun onChildRemoved(s: DataSnapshot) {}
                override fun onChildMoved(s: DataSnapshot, prev: String?) {}
                override fun onCancelled(e: DatabaseError) {}
            }
            ref.child("vc").addChildEventListener(candL!!)

            makeOffer(false)
        }

        private fun scheduleRestart(delayMs: Long) {
            handler.removeCallbacks(restartRunnable)
            handler.postDelayed(restartRunnable, delayMs)
        }

        fun restart() {
            if (stopped) return
            AppLog.log("[$vid] ICE restart")
            makeOffer(true)
        }

        private fun makeOffer(iceRestart: Boolean) {
            val p = pc ?: return
            val myN = ++n
            val c = MediaConstraints()
            if (iceRestart) c.mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            p.createOffer(object : SdpAdapter("createOffer") {
                override fun onCreateSuccess(d: SessionDescription?) {
                    d ?: return
                    p.setLocalDescription(object : SdpAdapter("setLocal(offer)") {
                        override fun onSetSuccess() {
                            ref.child("offer").setValue(mapOf("sdp" to d.description, "n" to myN))
                            AppLog.log("[$vid] offer #$myN sent")
                        }
                    }, d)
                }
            }, c)
        }

        fun close() {
            handler.removeCallbacks(restartRunnable)
            answerL?.let { ref.child("answer").removeEventListener(it) }
            candL?.let { ref.child("vc").removeEventListener(it) }
            try { pc?.close(); pc?.dispose() } catch (_: Throwable) {}
            pc = null
            AppLog.log("[$vid] closed")
        }
    }
}
