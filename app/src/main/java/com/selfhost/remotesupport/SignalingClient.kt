package com.selfhost.remotesupport

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI

class SignalingClient(
    serverUrl: String,
    private val onRegistered: (code: String) -> Unit,
    private val onControllerJoined: () -> Unit,
    private val onSignal: (payload: JSONObject) -> Unit,
    private val onPeerLeft: () -> Unit,
    private val onError: (message: String) -> Unit,
) {
    private val client: WebSocketClient = object : WebSocketClient(URI(serverUrl)) {
        override fun onOpen(handshakedata: ServerHandshake?) {
            send(JSONObject().put("type", "host-register").toString())
        }

        override fun onMessage(message: String?) {
            if (message == null) return
            val json = JSONObject(message)
            when (json.optString("type")) {
                "registered" -> onRegistered(json.getString("code"))
                "controller-joined" -> onControllerJoined()
                "signal" -> onSignal(json.getJSONObject("payload"))
                "peer-left" -> onPeerLeft()
                "code-expired" -> onError("Pairing code expired before anyone connected.")
                "error" -> onError(json.optString("message", "unknown error"))
            }
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            onError("Disconnected from signaling server.")
        }

        override fun onError(ex: Exception?) {
            onError(ex?.message ?: "signaling error")
        }
    }

    fun connect() = client.connect()

    fun sendSignal(payload: JSONObject) {
        client.send(JSONObject().put("type", "signal").put("payload", payload).toString())
    }

    fun endSession() {
        runCatching { client.send(JSONObject().put("type", "end-session").toString()) }
        client.close()
    }
}
