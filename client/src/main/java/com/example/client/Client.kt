package com.example.client

import android.text.SpannableString
import android.util.Log
import com.example.common_code.adapters.SpannableStringAdapter
import com.example.common_code.data_classes.DirectoryEntry
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import io.ktor.client.HttpClient
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.webSocket
import io.ktor.http.HttpMethod
import io.ktor.http.cio.websocket.DefaultWebSocketSession
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.close
import io.ktor.http.cio.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Client(private val host: String, private val port: Int) {
    private val client = HttpClient {
        install(WebSockets)
    }
    private val gson = GsonBuilder()
        .registerTypeAdapter(SpannableString::class.java, SpannableStringAdapter())
        .create()
    private var session: DefaultWebSocketSession? = null
    private var connectJob: Job? = null
    var onMemoryInfoReceived: ((String) -> Unit)? = null
    var onTreeReceived: ((SpannableString) -> Unit)? = null
    var onConnectionStatusChanged: ((Boolean) -> Unit)? = null
    var onScanStatusChanged: ((Boolean) -> Unit)? = null
    var onScanListChanged: ((List<DirectoryEntry>) -> Unit)? = null
    var onOverwritingChanged: ((Boolean) -> Unit)? = null
    var toChangeID: ((String) -> Unit)? = null
    var toChangeSizeTimeDatetime: ((String) -> Unit)? = null
    var showToast: ((String) -> Unit)? = null

    fun connect() {
        connectJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i("Client", "Attempting to connect to ws://$host:$port/scan")
                client.webSocket(method = HttpMethod.Get, host = host, port = port, path = "/scan") {
                    Log.i("Client", "WebSocket connection established")
                    session = this
                    Log.i("Client", "Connected to server at ws://$host:$port/scan")
                    withContext(Dispatchers.Main) {
                        onConnectionStatusChanged?.invoke(true)
                    }
                    Log.i("Client", "Called onConnectionStatusChanged with true")

                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val text = frame.readText()
                                    Log.i("Client", "Received: $text")
                                    withContext(Dispatchers.Main) {
                                        when {
                                            text.startsWith("MEMORY:") -> {
                                                val memoryInfo = text.removePrefix("MEMORY:")
                                                onMemoryInfoReceived?.invoke(memoryInfo)
                                            }
                                            text.startsWith("META-INF_AND_TREE:") -> {
                                                val response = text.removePrefix("META-INF_AND_TREE:")
                                                val arrResponse = response.split("#")
                                                val metaInf = arrResponse[0]
                                                val deserializedSpannable = gson.fromJson(arrResponse[1],
                                                    SpannableString::class.java)
                                                toChangeSizeTimeDatetime?.invoke(metaInf)
                                                onTreeReceived?.invoke(deserializedSpannable)
                                            }
                                            text == "SCAN_STARTED" -> {
                                                onScanStatusChanged?.invoke(true)
                                            }
                                            text == "SCAN_STOPPED" -> {
                                                onScanStatusChanged?.invoke(false)
                                            }
                                            text == "OVERWRITE_SUCCESS" -> {
                                                onOverwritingChanged?.invoke(false)
                                            }
                                            text.startsWith("TOAST:") -> {
                                                val string = text.removePrefix("TOAST:")
                                                showToast?.invoke(string)
                                            }
                                            text.startsWith("LAST_SCAN_ID:") -> {
                                                val id = text.removePrefix("LAST_SCAN_ID:")
                                                showToast?.invoke(id)
                                            }
                                            text.startsWith("STRING_LIST:") -> {
                                                val jsonString = text.removePrefix("STRING_LIST:")
                                                val listType = object : TypeToken<List<DirectoryEntry>>() {}.type
                                                val list = gson.fromJson<List<DirectoryEntry>>(jsonString,listType)
                                                onScanListChanged?.invoke(list)
                                            }

                                            else -> {}
                                        }
                                    }
                                }
                                else -> {
                                    Log.i("Client", "Received non-text frame: $frame")
                                }
                            }
                        }
                    } catch (e: ClosedReceiveChannelException) {
                        Log.i("Client", "Connection closed: ${e.message}")
                    } catch (e: Exception) {
                        Log.e("Client", "Error in WebSocket connection: ${e.message}")
                    } finally {
                        Log.i("Client", "WebSocket connection ended")
                        withContext(Dispatchers.Main) {
                            onConnectionStatusChanged?.invoke(false)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Client", "Error connecting to server", e)
                withContext(Dispatchers.Main) {
                    onConnectionStatusChanged?.invoke(false)
                }
            }
        }
    }

    fun close() {
        connectJob?.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                session?.close()
            } catch (e: Exception) {
                Log.e("Client", "Error closing session: ${e.message}")
            }
        }
        client.close()
        onConnectionStatusChanged?.invoke(false)
        Log.i("Client", "Client closed")
    }

    fun sendCommand(command: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                session?.send(Frame.Text(command))
                Log.i("Client", "Sent command: $command")
            } catch (e: Exception) {
                Log.e("Client", "Error sending command: ${e.message}")
            }
        }
    }

}
