package io.livekit.android.room

import com.github.ajalt.timberkt.Timber
import com.google.protobuf.util.JsonFormat
import io.livekit.android.ConnectOptions
import io.livekit.android.Version
import io.livekit.android.dagger.InjectionNames
import io.livekit.android.room.track.Track
import io.livekit.android.util.Either
import io.livekit.android.util.safe
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import livekit.LivekitModels
import livekit.LivekitRtc
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import javax.inject.Inject
import javax.inject.Named
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

/**
 * SignalClient to LiveKit WS servers
 * @suppress
 */
class SignalClient
@Inject
constructor(
    private val websocketFactory: WebSocket.Factory,
    private val fromJsonProtobuf: JsonFormat.Parser,
    private val toJsonProtobuf: JsonFormat.Printer,
    private val json: Json,
    private val okHttpClient: OkHttpClient,
    @Named(InjectionNames.SIGNAL_JSON_ENABLED)
    private val useJson: Boolean,
) : WebSocketListener() {
    var isConnected = false
        private set
    private var currentWs: WebSocket? = null
    private var isReconnecting: Boolean = false
    var listener: Listener? = null
    private var lastUrl: String? = null

    private var joinContinuation: CancellableContinuation<Either<LivekitRtc.JoinResponse, Unit>>? = null

    suspend fun join(
        url: String,
        token: String,
        options: ConnectOptions?,
    ) : LivekitRtc.JoinResponse {
        val joinResponse = connect(url,token, options)
        return (joinResponse as Either.Left).value
    }

    suspend fun reconnect(url: String, token: String){
        connect(
            url,
            token,
            ConnectOptions()
                .apply { reconnect = true }
        )
    }

    suspend fun connect(
        url: String,
        token: String,
        options: ConnectOptions?
    ) : Either<LivekitRtc.JoinResponse, Unit> {
        var wsUrlString = "$url/rtc" +
                "?protocol=$PROTOCOL_VERSION" +
                "&access_token=$token" +
                "&sdk=$SDK_TYPE" +
                "&version=${Version.CLIENT_VERSION}"
        isReconnecting = false
        if (options != null) {
            wsUrlString += "&auto_subscribe="
            wsUrlString += if (options.autoSubscribe) {
                "1"
            } else {
                "0"
            }
            if (options.reconnect) {
                wsUrlString += "&reconnect=1"
                isReconnecting = true
            }
        }

        Timber.i { "connecting to $wsUrlString" }

        isConnected = false
        currentWs?.cancel()
        currentWs = null

        joinContinuation?.cancel()
        joinContinuation = null

        lastUrl = wsUrlString

        val request = Request.Builder()
            .url(wsUrlString)
            .build()
        currentWs = websocketFactory.newWebSocket(request, this)

        return suspendCancellableCoroutine {
            // Wait for join response through WebSocketListener
            joinContinuation = it
        }
    }

    //--------------------------------- WebSocket Listener --------------------------------------//
    override fun onOpen(webSocket: WebSocket, response: Response) {
        if (isReconnecting) {
            isReconnecting = false
            isConnected = true
            joinContinuation?.resumeWith(Result.success(Either.Right(Unit)))
        }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        Timber.v { text }
        val signalResponseBuilder = LivekitRtc.SignalResponse.newBuilder()
        fromJsonProtobuf.merge(text, signalResponseBuilder)
        val response = signalResponseBuilder.build()

        handleSignalResponse(response)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        val byteArray = bytes.toByteArray()
        val signalResponseBuilder = LivekitRtc.SignalResponse.newBuilder()
            .mergeFrom(byteArray)
        val response = signalResponseBuilder.build()

        handleSignalResponse(response)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Timber.v { "websocket closed" }

        listener?.onClose(reason, code)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        Timber.v { "websocket closing" }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        var reason: String? = null
        try {
            lastUrl?.let {
                val validationUrl = "http" + it.
                    substring(2).
                    replaceFirst("/rtc?", "/rtc/validate?")
                val request = Request.Builder().url(validationUrl).build()
                val resp = okHttpClient.newCall(request).execute()
                if (!resp.isSuccessful) {
                    reason = resp.body?.string()
                }
            }
        } catch (e: Throwable) {
            Timber.e(e) { "failed to validate connection" }
        }

        if (reason != null) {
            Timber.e(t) { "websocket failure: $reason" }
            listener?.onError(Exception(reason))
        } else {
            Timber.e(t) { "websocket failure: $response" }
            listener?.onError(t as Exception)
        }
    }

    //------------------------------- End WebSocket Listener ------------------------------------//

    private fun fromProtoSessionDescription(sd: LivekitRtc.SessionDescription): SessionDescription {
        val rtcSdpType = when (sd.type) {
            SD_TYPE_ANSWER -> SessionDescription.Type.ANSWER
            SD_TYPE_OFFER -> SessionDescription.Type.OFFER
            SD_TYPE_PRANSWER -> SessionDescription.Type.PRANSWER
            else -> throw IllegalArgumentException("invalid RTC SdpType: ${sd.type}")
        }
        return SessionDescription(rtcSdpType, sd.sdp)
    }

    private fun toProtoSessionDescription(sdp: SessionDescription): LivekitRtc.SessionDescription {
        val sdBuilder = LivekitRtc.SessionDescription.newBuilder()
        sdBuilder.sdp = sdp.description
        sdBuilder.type = when (sdp.type) {
            SessionDescription.Type.ANSWER -> SD_TYPE_ANSWER
            SessionDescription.Type.OFFER -> SD_TYPE_OFFER
            SessionDescription.Type.PRANSWER -> SD_TYPE_PRANSWER
            else -> throw IllegalArgumentException("invalid RTC SdpType: ${sdp.type}")
        }

        return sdBuilder.build()
    }

    fun sendOffer(offer: SessionDescription) {
        val sd = toProtoSessionDescription(offer)
        val request = LivekitRtc.SignalRequest.newBuilder()
            .setOffer(sd)
            .build()

        sendRequest(request)
    }

    fun sendAnswer(answer: SessionDescription) {
        val sd = toProtoSessionDescription(answer)
        val request = LivekitRtc.SignalRequest.newBuilder()
            .setAnswer(sd)
            .build()

        sendRequest(request)
    }

    fun sendCandidate(candidate: IceCandidate, target: LivekitRtc.SignalTarget){
        val iceCandidateJSON = IceCandidateJSON(
            candidate = candidate.sdp,
            sdpMid = candidate.sdpMid,
            sdpMLineIndex = candidate.sdpMLineIndex
        )

        val trickleRequest = LivekitRtc.TrickleRequest.newBuilder()
            .setCandidateInit(json.encodeToString(iceCandidateJSON))
            .setTarget(target)
            .build()

        val request = LivekitRtc.SignalRequest.newBuilder()
            .setTrickle(trickleRequest)
            .build()

        sendRequest(request)
    }

    fun sendMuteTrack(trackSid: String, muted: Boolean) {
        val muteRequest = LivekitRtc.MuteTrackRequest.newBuilder()
            .setSid(trackSid)
            .setMuted(muted)
            .build()

        val request = LivekitRtc.SignalRequest.newBuilder()
            .setMute(muteRequest)
            .build()

        sendRequest(request)
    }

    fun sendAddTrack(cid: String, name: String, type: LivekitModels.TrackType, dimensions: Track.Dimensions? = null) {
        val addTrackRequest = LivekitRtc.AddTrackRequest.newBuilder()
            .setCid(cid)
            .setName(name)
            .setType(type)
        if (dimensions != null) {
            addTrackRequest.width = dimensions.width
            addTrackRequest.height = dimensions.height
        }

        val request = LivekitRtc.SignalRequest.newBuilder()
            .setAddTrack(addTrackRequest)
            .build()

        sendRequest(request)
    }

    fun sendUpdateTrackSettings(sid: String, disabled: Boolean, videoQuality: LivekitRtc.VideoQuality) {
        val trackSettings = LivekitRtc.UpdateTrackSettings.newBuilder()
            .addTrackSids(sid)
            .setDisabled(disabled)
            .setQuality(videoQuality)

         val request = LivekitRtc.SignalRequest.newBuilder()
            .setTrackSetting(trackSettings)
            .build()

        sendRequest(request)
    }

    fun sendUpdateSubscription(sid: String, subscribe: Boolean) {
        val subscription = LivekitRtc.UpdateSubscription.newBuilder()
            .addTrackSids(sid)
            .setSubscribe(subscribe)

         val request = LivekitRtc.SignalRequest.newBuilder()
            .setSubscription(subscription)
            .build()

        sendRequest(request)
    }
    
    fun sendLeave() {
        val request = LivekitRtc.SignalRequest.newBuilder()
            .setLeave(LivekitRtc.LeaveRequest.newBuilder().build())
            .build()
        sendRequest(request)
    }

    private fun sendRequest(request: LivekitRtc.SignalRequest) {
        Timber.v { "sending request: $request" }
        if (!isConnected || currentWs == null) {
            Timber.w { "not connected, could not send request $request" }
            return
        }
        val sent: Boolean
        if (useJson) {
            val message = toJsonProtobuf.print(request)
            sent = currentWs?.send(message) ?: false
        } else {
            val message = request.toByteArray().toByteString()
            sent = currentWs?.send(message) ?: false
        }

        if (!sent) {
            Timber.e { "error sending request: $request" }
        }
    }

    private fun handleSignalResponse(response: LivekitRtc.SignalResponse) {
        if (!isConnected) {
            // Only handle joins if not connected.
            if (response.hasJoin()) {
                isConnected = true
                joinContinuation?.resumeWith(Result.success(Either.Left(response.join)))
            } else {
                Timber.e { "Received response while not connected. ${toJsonProtobuf.print(response)}" }
            }
            return
        }

        Timber.v { "response: $response" }
        when (response.messageCase) {
            LivekitRtc.SignalResponse.MessageCase.ANSWER -> {
                val sd = fromProtoSessionDescription(response.answer)
                listener?.onAnswer(sd)
            }
            LivekitRtc.SignalResponse.MessageCase.OFFER -> {
                val sd = fromProtoSessionDescription(response.offer)
                listener?.onOffer(sd)
            }
            LivekitRtc.SignalResponse.MessageCase.TRICKLE -> {
                val iceCandidateJson =
                    json.decodeFromString<IceCandidateJSON>(response.trickle.candidateInit)
                val iceCandidate = IceCandidate(
                    iceCandidateJson.sdpMid,
                    iceCandidateJson.sdpMLineIndex,
                    iceCandidateJson.candidate
                )
                listener?.onTrickle(iceCandidate, response.trickle.target)
            }
            LivekitRtc.SignalResponse.MessageCase.UPDATE -> {
                listener?.onParticipantUpdate(response.update.participantsList)
            }
            LivekitRtc.SignalResponse.MessageCase.TRACK_PUBLISHED -> {
                listener?.onLocalTrackPublished(response.trackPublished)
            }
            LivekitRtc.SignalResponse.MessageCase.SPEAKERS_CHANGED -> {
                listener?.onSpeakersChanged(response.speakersChanged.speakersList)
            }
            LivekitRtc.SignalResponse.MessageCase.JOIN -> {
                Timber.d { "received unexpected extra join message?" }
            }
            LivekitRtc.SignalResponse.MessageCase.LEAVE -> {
                listener?.onLeave()
            }
            LivekitRtc.SignalResponse.MessageCase.MUTE -> {
                listener?.onRemoteMuteChanged(response.mute.sid, response.mute.muted)
            }
            LivekitRtc.SignalResponse.MessageCase.ROOM_UPDATE -> {
                //TODO
            }
            LivekitRtc.SignalResponse.MessageCase.MESSAGE_NOT_SET,
            null -> {
                Timber.v { "empty messageCase!" }
            }
        }.safe()
    }

    fun close() {
        isConnected = false
        currentWs?.close(1000, "Normal Closure")
    }

    interface Listener {
        fun onAnswer(sessionDescription: SessionDescription)
        fun onOffer(sessionDescription: SessionDescription)
        fun onTrickle(candidate: IceCandidate, target: LivekitRtc.SignalTarget)
        fun onLocalTrackPublished(response: LivekitRtc.TrackPublishedResponse)
        fun onParticipantUpdate(updates: List<LivekitModels.ParticipantInfo>)
        fun onSpeakersChanged(speakers: List<LivekitModels.SpeakerInfo>)
        fun onClose(reason: String, code: Int)
        fun onRemoteMuteChanged(trackSid: String, muted: Boolean)
        fun onLeave()
        fun onError(error: Exception)
    }

    companion object {
        const val SD_TYPE_ANSWER = "answer"
        const val SD_TYPE_OFFER = "offer"
        const val SD_TYPE_PRANSWER = "pranswer"
        const val PROTOCOL_VERSION = 2
        const val SDK_TYPE = "android"

        private fun iceServer(url: String) =
            PeerConnection.IceServer.builder(url).createIceServer()

        // more stun servers might slow it down, WebRTC recommends 3 max
        val DEFAULT_ICE_SERVERS = listOf(
            iceServer("stun:stun.l.google.com:19302"),
            iceServer("stun:stun1.l.google.com:19302"),
//            iceServer("stun:stun2.l.google.com:19302"),
//            iceServer("stun:stun3.l.google.com:19302"),
//            iceServer("stun:stun4.l.google.com:19302"),
        )
    }
}