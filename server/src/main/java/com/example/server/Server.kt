package com.example.server

import android.util.Log
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.origin
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set
import kotlin.system.measureTimeMillis

class Server(private val port: Int, private val context: MainActivity) {
    private var server: ApplicationEngine? = null
    private val connectedClients = ConcurrentHashMap<String, WebSocketSession>()
    private val googleChromeScanner = GoogleChromeScanner(context)
    var onProcessChanged: ((String) -> Unit)? = null

    fun start() {
        server = embeddedServer(Netty, host = "0.0.0.0", port = port) {
            install(WebSockets) {
                pingPeriod = Duration.ofSeconds(15)
                timeout = Duration.ofSeconds(15)
                maxFrameSize = Long.MAX_VALUE
                masking = false
            }
            routing {
                webSocket("/scan") {
                    val clientAddress = call.request.origin.remoteHost
                    connectedClients[clientAddress] = this

                    try {
                        launch {
                            while (isActive) {
                                val memoryInfo = context.getMemoryInfo()
                                send(Frame.Text("MEMORY:$memoryInfo"))
                                delay(100)
                            }
                        }

                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val receivedText = frame.readText()
                                context.showToast("Received from $clientAddress: $receivedText")
                                handleMessage(receivedText, this)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("Server", "Error in WebSocket for $clientAddress: ${e.message}")
                    } finally {
                        Log.i("Server", "Client disconnected: $clientAddress")
                        connectedClients.remove(clientAddress)
                    }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 10000)
        connectedClients.clear()
        googleChromeScanner.stopScanning()
    }

    private suspend fun handleMessage(message: String, session: WebSocketSession) {
        when {
            message.startsWith("START_SCAN:") -> {
                val interval = message.substringAfter(":").toIntOrNull()
                if (interval != null && interval > 0) {
                    googleChromeScanner.startScanning(interval)
                    onProcessChanged?.invoke("scanning")
                    session.send(Frame.Text("SCAN_STARTED"))
                    session.send(Frame.Text("TOAST:Scanning started with interval $interval seconds"))
                } else {
                    session.send(Frame.Text("TOAST:Invalid interval"))
                }
            }
            message == "STOP_SCAN" -> {
                googleChromeScanner.stopScanning()
                onProcessChanged?.invoke("")
                session.send(Frame.Text("SCAN_STOPPED"))
                session.send(Frame.Text("Scanning stopped"))
            }
            message == "GET_LAST_SCAN" -> {
                val lastScan = googleChromeScanner.getLastScan()
                if (lastScan != null) {
                    session.send(Frame.Text("LAST_SCAN:$lastScan"))
                } else {
                    session.send(Frame.Text("TOAST:No scans available"))
                }
            }
            message.startsWith("OVERWRITE:") -> {
                val id = message.substringAfter(":").toLongOrNull()
                if (id != null) {
                    val zipFile = googleChromeScanner.getScanById(id)?.archiveFile
                    val duration = measureTimeMillis {
                        if (googleChromeScanner.insertDirectory(File(zipFile.toString()))) {
                            session.send(Frame.Text("OVERWRITE_SUCCESS"))
                        } else {
                            session.send(Frame.Text("TOAST:Something wrong"))
                        }
                    }
                    context.showToast("Process completed in $duration ms")
                } else {
                    session.send(Frame.Text("TOAST:Invalid id"))
                }
            }
            message.startsWith("GET_META-INF_AND_TREE:") -> {
                val scanId = message.substringAfter(":").toLongOrNull()
                if (scanId != null) {
                    val scan = googleChromeScanner.getScanById(scanId)
                    if (scan != null) {
                        val tree = googleChromeScanner.compareTreesFromScanToJson(scan)
                        session.send(Frame.Text("META-INF_AND_TREE:" +
                                "${scan.totalSize}, " +
                                "${scan.scanDurationMs} ms, " +
                                "${scan.scanStartTime.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))}#" +
                                tree
                        ))
                    } else {
                        session.send(Frame.Text("TOAST:Scan not found"))
                    }
                } else {
                    session.send(Frame.Text("TOAST:Invalid scan ID"))
                }
            }
            message == "GET LIST" -> {
                val list = googleChromeScanner.getAllDirectoryEntries()
                session.send(Frame.Text("STRING_LIST:$list"))
            }
            else -> {
                session.send(Frame.Text("TOAST:Unknown command"))
            }
        }
    }

    fun getConnectedClientsInfo(): String {
        return connectedClients.keys.mapIndexed { index, address ->
            "Client ${index + 1}: $address"
        }.joinToString("\n")
    }
}