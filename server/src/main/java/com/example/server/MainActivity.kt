package com.example.server

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.StringBuilder
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {
    private lateinit var server: Server
    private var serverJob: Job? = null
    private var memoryMonitorJob: Job? = null
    private var portToStart: Int = 8080

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (checkStoragePermission()) {
            startServer()
        } else {
            showToast("Permission denied. Cannot access files.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ipAddress = getIpAddress(this) ?: "Not available"
            ServerScreen(ipAddress, this)
        }

    }

    private fun checkAndRequestPermissions(port: Int) {
        portToStart = port
        if (checkStoragePermission()) {
            startServer()
        } else {
            requestStoragePermission()
        }
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val readPermission = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            )
            val writePermission = ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            readPermission == PackageManager.PERMISSION_GRANTED &&
                    writePermission == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.addCategory("android.intent.category.DEFAULT")
                intent.data = Uri.parse("package:${applicationContext.packageName}")
                requestPermissionLauncher.launch(intent)
            } catch (e: Exception) {
                val intent = Intent()
                intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                requestPermissionLauncher.launch(intent)
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                STORAGE_PERMISSION_CODE
            )
        }
    }

    private fun startServer() {
        server = Server(portToStart, this)

        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                server.start()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showToast("Error starting server: ${e.message}")
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startServer()
            } else {
                showToast("Permission denied. Cannot access files.")
            }
        }
    }

    fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun getIpAddress(context: Context): String? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return null
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return null

        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            try {
                val interfaces: List<NetworkInterface> = NetworkInterface.getNetworkInterfaces().toList()
                for (intf in interfaces) {
                    val addresses: List<InetAddress> = intf.inetAddresses.toList()
                    for (address in addresses) {
                        if (!address.isLoopbackAddress && address is Inet4Address) {
                            return address.hostAddress
                        }
                    }
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
        return null
    }

    fun getMemoryInfo(): String {
        val runtime = Runtime.getRuntime()
        val usedMemInMB = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L
        val maxHeapSizeInMB = runtime.maxMemory() / 1048576L
        return "$usedMemInMB MB / $maxHeapSizeInMB MB"
    }

    @Composable
    fun ServerScreen(
        ipAddress: String,
        context: MainActivity
    ) {
        var port by remember { mutableStateOf("8080") }
        var serverRunning by remember { mutableStateOf(false) }
        var memoryInfo by remember { mutableStateOf("") }
        var process by remember { mutableStateOf("") }
        var connectedClients by remember { mutableStateOf("") }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        val coroutineScope = rememberCoroutineScope()

        fun setupServerCallbacks(scope: CoroutineScope) {
            server.onProcessChanged = { isProcess ->
                scope.launch(Dispatchers.Main) {
                    process = isProcess
                }
            }
        }

        LaunchedEffect(Unit) {
            memoryMonitorJob = launch {
                while (isActive) {
                    memoryInfo = getMemoryInfo()
                    delay(100)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Server IP: $ipAddress")

            Spacer(modifier = Modifier.height(16.dp))

            Text("Memory Usage: $memoryInfo")

            Spacer(modifier = Modifier.height(16.dp))

            Text("Process: $process")

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("Enter port") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                ),
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    coroutineScope.launch {
                        try {
                            if (!serverRunning) {
                                val portNumber = port.toIntOrNull()
                                if (portNumber != null) {
                                    context.checkAndRequestPermissions(portNumber)
                                    setupServerCallbacks(coroutineScope);
                                    serverRunning = true
                                    errorMessage = null
                                } else {
                                    errorMessage = "Invalid port number"
                                }
                            } else {
                                serverJob?.cancel()
                                server.stop()
                                serverRunning = false
                                errorMessage = null
                            }
                        } catch (e: Exception) {
                            errorMessage = "Error: ${e.message}"
                            serverRunning = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (serverRunning) "Stop Server" else "Start Server")
            }


            Spacer(modifier = Modifier.height(16.dp))

            errorMessage?.let {
                Text(it, color = Color.Red)
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text("Connected Clients:")
            Text(connectedClients)

            LaunchedEffect(serverRunning) {
                while (serverRunning) {
                    try {
                        connectedClients = server.getConnectedClientsInfo()
                    } catch (e: Exception) {
                        Log.e("ServerScreen", "Error getting connected clients: ${e.message}")
                    }
                    delay(1000)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        memoryMonitorJob?.cancel()
        serverJob?.cancel()
    }

    companion object {
        private const val STORAGE_PERMISSION_CODE = 100
    }
}