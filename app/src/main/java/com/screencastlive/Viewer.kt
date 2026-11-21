package com.screencastlive

import android.content.Context
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/** Watches one broadcast. Viewer id == own uid. */
class Viewer(private val ctx: Context, private val streamId: String, private val uid: String) {
    val state = MutableStateFlow("Connecting...")
    val track = MutableStateFlow<VideoTrack?>(null)

    private val db = FirebaseDatabase.getInstance()
    private val ref = db.getReference("signaling/$streamId/viewers/$uid")
    private val statusRef = db.getReference("streams/$streamId/status")
    private var pc: PeerConnection? = null
    private var lastN = 0
    private var remoteSet = false
    private val pending = ArrayList<IceCandidate>()
    private var offerL: ValueEventListener? = null
    private var candL: ChildEventListener? = null
    private var statusL: ValueEventListener? = null
    private var errL: ValueEventListener? = null

    fun join() {
        Rtc.init(ctx)
        AppLog.log("Viewer: joining $streamId")
        ref.removeValue()
        ref.onDisconnect().removeValue()

        val conn = Rtc.factory.createPeerConnection(Rtc.rtcConfig(), object : PcObserver() {
            override fun onIceCandidate(c: IceCandidate?) {
                c ?: return
                ref.child("vc").push().setValue(
                    mapOf("mid" to c.sdpMid, "idx" to c.sdpMLineIndex, "sdp" to c.sdp)
                )
            }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                AppLog.log("Viewer ICE $s")
                when (s) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> state.value = "Live"
                    PeerConnection.IceConnectionState.DISCONNECTED -> state.value = "Connection interrupted, waiting..."
                    PeerConnection.IceConnectionState.FAILED -> state.value = "Connection failed, waiting for broadcaster to retry..."
                    else -> {}
                }
            }
            override fun onTrack(t: RtpTransceiver?) {
                val tr = t?.receiver?.track()
                if (tr is VideoTrack) {
                    AppLog.log("Viewer: video track received")
                    track.value = tr
                }
            }
        })
        if (conn == null) { state.value = "Error: could not create PeerConnection"; return }
        pc = conn

        offerL = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val sdp = s.child("sdp").getValue(String::class.java) ?: return
                val n = (s.child("n").value as? Number)?.toInt() ?: return
                if (n <= lastN) return
                lastN = n
                val p = pc ?: return
                AppLog.log("Viewer: offer #$n received")
                p.setRemoteDescription(object : SdpAdapter("setRemote(offer)") {
                    override fun onSetSuccess() {
                        synchronized(pending) {
                            remoteSet = true
                            pending.forEach { p.addIceCandidate(it) }
                            pending.clear()
                        }
                        p.createAnswer(object : SdpAdapter("createAnswer") {
                            override fun onCreateSuccess(d: SessionDescription?) {
                                d ?: return
                                p.setLocalDescription(object : SdpAdapter("setLocal(answer)") {
                                    override fun onSetSuccess() {
                                        ref.child("answer").setValue(mapOf("sdp" to d.description, "n" to n))
                                        AppLog.log("Viewer: answer #$n sent")
                                    }
                                }, d)
                            }
                        }, MediaConstraints())
                    }
                }, SessionDescription(SessionDescription.Type.OFFER, sdp))
            }
            override fun onCancelled(e: DatabaseError) { AppLog.log("Viewer offer listener: ${e.message}") }
        }
        ref.child("offer").addValueEventListener(offerL!!)

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
        ref.child("bc").addChildEventListener(candL!!)

        errL = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (s.value == "full") state.value = "Viewer limit reached for this stream"
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        ref.child("error").addValueEventListener(errL!!)

        statusL = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                if (s.value == "offline") state.value = "Broadcaster is offline / reconnecting..."
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        statusRef.addValueEventListener(statusL!!)

        ref.child("join").setValue(true)
    }

    fun leave() {
        AppLog.log("Viewer: leaving")
        track.value = null
        offerL?.let { ref.child("offer").removeEventListener(it) }
        candL?.let { ref.child("bc").removeEventListener(it) }
        errL?.let { ref.child("error").removeEventListener(it) }
        statusL?.let { statusRef.removeEventListener(it) }
        try { pc?.close(); pc?.dispose() } catch (_: Throwable) {}
        pc = null
        ref.removeValue()
        ref.onDisconnect().cancel()
    }
}
