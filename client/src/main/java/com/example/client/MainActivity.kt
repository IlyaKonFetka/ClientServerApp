package com.example.client

import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.common_code.data_classes.DirectoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var client: Client

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClientScreen()
        }
    }


    @Composable
    fun ClientScreen() {
        var host by remember { mutableStateOf("192.168.0.109") }
        var port by remember { mutableStateOf("8080") }
        var isConnected by remember { mutableStateOf(false) }
        var showConfigDialog by remember { mutableStateOf(false) }
        var serverMemoryInfo by remember { mutableStateOf("") }
        var isScanningStarted by remember { mutableStateOf(false) }
        var intervalSeconds by remember { mutableStateOf("5") }
        var treeString by remember { mutableStateOf(SpannableString("")) }
        var sizeTimeDate by remember { mutableStateOf("") }
        var entries by remember { mutableStateOf<List<DirectoryEntry>>(emptyList()) }
        var isOverwriting by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        var initialID by remember { mutableStateOf("") }
        var isLoading by remember { mutableStateOf(false) }


        fun setupClientCallbacks(scope: CoroutineScope) {
            client.onMemoryInfoReceived = { info ->
                scope.launch(Dispatchers.Main) {
                    serverMemoryInfo = info
                }
            }
            client.onTreeReceived = { tree ->
                scope.launch(Dispatchers.Main) {
                    treeString = tree
                    isLoading = false
                }
            }
            client.onConnectionStatusChanged = { connected ->
                scope.launch(Dispatchers.Main) {
                    isConnected = connected
                    if (connected) {
                        showToast("Connected to server")
                    } else {
                        showToast("Disconnected from server")
                    }
                }
            }
            client.onScanStatusChanged = { scanning ->
                scope.launch(Dispatchers.Main) {
                    isScanningStarted = scanning
                }
            }
            client.showToast = { string ->
                showToast(string)
            }
            client.onScanListChanged = {list ->
                scope.launch(Dispatchers.Main) {
                    entries = list
                }
            }
            client.onOverwritingChanged = {overwriting ->
                scope.launch(Dispatchers.Main) {
                    isOverwriting = overwriting
                }
            }
            client.toChangeID = {id ->
                scope.launch(Dispatchers.Main) {
                    initialID = id
                }
            }
            client.toChangeSizeTimeDatetime = {a ->
                scope.launch(Dispatchers.Main) {
                    sizeTimeDate = a
                }
            }
        }

        LaunchedEffect(Unit) {
            client = Client("", 0)
            setupClientCallbacks(scope)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Connection status: ${if (isConnected) "Connected" else "Disconnected"}")

            Spacer(modifier = Modifier.height(16.dp))

            Text("Server memory: $serverMemoryInfo")

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                modifier = Modifier
                    .fillMaxWidth(),
                onClick = { showConfigDialog = true }
            ) {
                Text("Config")
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!isConnected) {
                Button(
                    modifier = Modifier
                        .fillMaxWidth(),
                    onClick = {
                        if (host.isNotEmpty() && port.isNotEmpty()) {
                            client = Client(host, port.toInt())
                            setupClientCallbacks(scope)
                            scope.launch {
                                client.connect()
                            }
                        } else {
                            showToast("Please configure server settings")
                        }
                    },
                ) {
                    Text("Connect")
                }
            } else if(!isOverwriting){
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = intervalSeconds,
                        onValueChange = { intervalSeconds = it },
                        label = { Text("Interval (seconds)") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        visualTransformation = VisualTransformation.None,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val interval = intervalSeconds.toIntOrNull()
                            if (interval != null && interval > 0) {
                                scope.launch {
                                    if (!isScanningStarted) {
                                        client.sendCommand("START_SCAN:$interval")
                                    } else {
                                        client.sendCommand("STOP_SCAN")
                                    }
                                }
                            } else {
                                showToast("Invalid interval")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isScanningStarted) "Stop" else "Start")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if(!isOverwriting){
                DirectoryListScreen(
                    entries,
                    treeString,
                    sizeTimeDate,
                    { _ -> initialID },
                    { id -> initialID = id },
                    { ow -> isOverwriting = ow },
                    { loading -> isLoading = loading },
                    isLoading
                )
            } else {
                RestoringScanMessage()
            }

            if (showConfigDialog) {
                ConfigDialog(
                    initialHost = host,
                    initialPort = port,
                    onDismiss = { showConfigDialog = false },
                    onSave = { newHost, newPort ->
                        host = newHost
                        port = newPort
                        showConfigDialog = false
                        client = Client(newHost, newPort.toIntOrNull() ?: 8080)
                    }
                )
            }
        }
    }

    @Composable
    fun RestoringScanMessage() {
        Text(
            text = "Server is restoring the scan...",
            color = Color.Red,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp)
        )
    }
    @Composable
    fun ConfigDialog(
        initialHost: String,
        initialPort: String,
        onDismiss: () -> Unit,
        onSave: (String, String) -> Unit
    ) {
        var host by remember { mutableStateOf(initialHost) }
        var port by remember { mutableStateOf(initialPort) }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Configure Server") },
            text = {
                Column {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Server IP") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next
                        ),
                        visualTransformation = VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Server Port") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        visualTransformation = VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { onSave(host, port) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    @Composable
    fun DirectoryListScreen(
        entries: List<DirectoryEntry>,
        tree: SpannableString,
        std: String,
        getID: ((Any) -> String),
        setID: ((String) -> Unit),
        setIsOverwriting: ((Boolean) -> Unit),
        setIsLoading: (Boolean) -> Unit,
        isLoading: Boolean
    ) {
        val coroutineScope = rememberCoroutineScope()
        var showTreeDialog by remember { mutableStateOf(false) }

        Column {
            Button(
                onClick = {
                    client.sendCommand("GET LIST")
                },
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                Text("Update Scan List")
            }

            LazyColumn {
                items(entries) { entry ->
                    DirectoryEntryItem(
                        entry = entry,
                        onShowDirectory = {
                            showTreeDialog = true
                            setID.invoke(entry.id)
                            coroutineScope.launch {
                                client.sendCommand("GET_META-INF_AND_TREE:${entry.id}")
                            }
                        },
                        onOverwrite = { id ->
                            overwrite(coroutineScope, id)
                            setIsOverwriting.invoke(true)
                        },
                        setIsLoading = setIsLoading
                    )
                }
            }
            if (showTreeDialog) {
                TreeDialog(
                    onDismiss = {showTreeDialog = false},
                    tree,
                    std,
                    coroutineScope,
                    getID.invoke(""),
                    {ow -> setIsOverwriting.invoke(ow)},
                    isLoading
                )
            }
        }
    }
    private fun overwrite(scope: CoroutineScope, id: String){
        scope.launch {
            client.sendCommand("OVERWRITE:$id")
        }
    }
    @Composable
    fun TreeDialog(
        onDismiss: () -> Unit,
        tree: SpannableString,
        std: String,
        coroutineScope: CoroutineScope,
        id: String,
        setIsOverwriting: (Boolean) -> Unit,
        isLoading:Boolean
    ) {
        Dialog(onDismissRequest = { onDismiss() }) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxSize(0.95f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Directory Structure",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        FileTreeView(tree, isLoading)
                    }

                    Text(text = std)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { onDismiss() }) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                overwrite(coroutineScope, id)
                                setIsOverwriting.invoke(true)
                            }
                        ) {
                            Text("Overwrite")
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun FileTreeView(text: SpannableString, isLoading: Boolean) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary,
                    shape = RectangleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator()
            } else {
                val annotatedString = buildAnnotatedString {
                    append(text.toString())
                    text.getSpans(0, text.length, Any::class.java).forEach { span ->
                        val start = text.getSpanStart(span)
                        val end = text.getSpanEnd(span)
                        when (span) {
                            is ForegroundColorSpan -> addStyle(SpanStyle(color = Color(span.foregroundColor)), start, end)
                        }
                    }
                    addStyle(SpanStyle(fontFamily = FontFamily.Monospace), 0, text.length)
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = annotatedString,
                        style = TextStyle(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }

    @Composable
    fun DirectoryEntryItem(
        entry: DirectoryEntry,
        onShowDirectory: () -> Unit,
        onOverwrite: (String) -> Unit,
        setIsLoading: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = entry.date,
                    modifier = Modifier.padding(end = 16.dp)
                )
            }
            Row {
                Button(
                    onClick = {
                        setIsLoading(true)
                        onShowDirectory()
                    },
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text("Show")
                }
                Button(
                    onClick = { onOverwrite.invoke(entry.id) }
                ) {
                    Text("Overwrite")
                }
            }
        }
    }


    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

