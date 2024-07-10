package com.example.server

import android.annotation.SuppressLint
import android.text.SpannableString
import android.util.Log
import com.example.common_code.data_classes.DirectoryEntry
import com.example.common_code.adapters.SpannableStringAdapter
import com.example.server.adapters.FileNodeAdapter
import com.example.server.data_classes.FileNode
import com.example.server.data_classes.ScanInfo
import com.example.server.exceptions.NodeFormatException
import com.example.server.exceptions.NodeIsAbsentException
import com.example.server.managers.Archiver
import com.example.server.managers.FileScanner
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.lang.StringBuilder
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class GoogleChromeScanner(private val context: MainActivity) {
    companion object {
        @SuppressLint("SdCardPath")
        private val INSIDE_HOLDER = File("/data/data/com.android.chrome")
        private const val SCANS_DIRECTORY = "chrome_scans"
        private const val ARCHIVES_DIRECTORY = "chrome_archives"
        private val FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(ZoneId.systemDefault())
    }

    private val fileScanner = FileScanner()
    private val archiver = Archiver()
    private val dbHelper = ScanDatabaseHelper(context)
    private var scanJob: Job? = null
    private val gson = GsonBuilder()
        .registerTypeAdapter(SpannableString::class.java, SpannableStringAdapter())
        .registerTypeAdapter(FileNode::class.java, FileNodeAdapter())
        .create()

    private val scansDirectory: File = File(context.filesDir, SCANS_DIRECTORY).apply { mkdirs() }
    private val archivesDirectory: File = File(context.filesDir, ARCHIVES_DIRECTORY).apply { mkdirs() }

    private var currentNode: FileNode

    init {
        currentNode = if (dbHelper.isEmpty()) {
            val initialNode = fileScanner.scanDirectory(INSIDE_HOLDER.absolutePath)
            val scanStartTime = LocalDateTime.now()
            val scanFile = uploadNodeToFile(initialNode, scanStartTime)
            val totalSizeBytes = fileScanner.getDirectorySize(INSIDE_HOLDER.absolutePath)
            val totalSize = fileScanner.convertBytesToString(totalSizeBytes)
            val scanDurationMs = System.currentTimeMillis() - scanStartTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val archiveFileName = "archive_${scanStartTime.format(FILE_DATE_FORMAT)}.tar.gz"
            val archiveFile = File(archivesDirectory, archiveFileName)
            CoroutineScope(Dispatchers.IO).launch {
                archiver.zipDirectory(INSIDE_HOLDER.absolutePath, archiveFile.absolutePath)
            }
            dbHelper.insertScan(scanFile.absolutePath, archiveFile.absolutePath, totalSize, scanDurationMs, scanStartTime)
            initialNode
        } else {
            val nodeFilePath = dbHelper.getLastScan()!!.scanFile
            loadNodeFromFile(File(nodeFilePath))
        }
    }

    fun startScanning(intervalSeconds: Int) {
        scanJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                scanChromeDirectories()
                delay(intervalSeconds * 1000L)
            }
        }
    }

    fun stopScanning() {
        scanJob?.cancel()
        scanJob = null
    }



    fun getLastScan(): ScanInfo? = dbHelper.getLastScan()

    fun getScanById(id: Long): ScanInfo? = dbHelper.getScanById(id)

    fun getAllDirectoryEntries(): String {
        val list = dbHelper.getAllDirectoryEntries()
        val type = object : TypeToken<List<DirectoryEntry>>() {}.type
        return gson.toJson(list, type)
    }


    private suspend fun scanChromeDirectories() {
        withContext(Dispatchers.IO) {
            val scanStartTime = LocalDateTime.now()
            val newNode = fileScanner.scanDirectory(INSIDE_HOLDER.absolutePath)
            if (fileScanner.treesAreDifferent(currentNode, newNode)) {
                val scanFile = uploadNodeToFile(newNode, scanStartTime)

                val archiveFileName = "archive_${scanStartTime.format(FILE_DATE_FORMAT)}.tar.gz"
                val archiveFile = File(archivesDirectory, archiveFileName)
                archiver.zipDirectory(INSIDE_HOLDER.absolutePath, archiveFile.absolutePath)

                val totalSizeBytes = fileScanner.getDirectorySize(INSIDE_HOLDER.absolutePath)
                val totalSize = fileScanner.convertBytesToString(totalSizeBytes)

                val scanDurationMs = System.currentTimeMillis() - scanStartTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

                dbHelper.insertScan(scanFile.absolutePath, archiveFile.absolutePath, totalSize, scanDurationMs, scanStartTime)

                currentNode = newNode
            }
        }
    }

    private fun executeCommandAsRoot(command: String) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            process.waitFor()
        } catch (e: Exception) {
            Log.e("Inserting", "Error executing command as root: ${e.message}")
        }
    }


    private fun copyDirectoryAsRoot(sourceDir: File, destDir: File) {
        val command = "cp -R ${sourceDir.absolutePath}/* ${destDir.absolutePath}"
        executeCommandAsRoot(command)
    }

    suspend fun insertDirectory(zipPath: File): Boolean {
        return try {
            killChromeProcess()

            val tempDirName = "temp_chrome_data_${System.currentTimeMillis()}"
            val tempDir = File(context.filesDir, tempDirName)

            if (!tempDir.mkdirs()) {
                throw IOException("Failed to create temporary directory")
            }

            archiver.unzip(zipPath.absolutePath, tempDir.absolutePath)

            val clearCommand = "rm -rf ${INSIDE_HOLDER.absolutePath}/*"
            executeCommandAsRoot(clearCommand)

            copyDirectoryAsRoot(tempDir, INSIDE_HOLDER)

            tempDir.deleteRecursively()

            setProperPermissions(INSIDE_HOLDER)
            launchChrome()

            true
        } catch (e: Exception) {
            false
        }
    }

    private fun killChromeProcess() {
        executeRootCommand("am force-stop com.android.chrome")
    }

    private fun launchChrome() {
        executeRootCommand("am start -n com.android.chrome/com.google.android.apps.chrome.Main")
    }

    private fun setProperPermissions(file: File) {
        val chmodResult = executeRootCommand("chmod -R 755 ${file.absolutePath}")
        if (chmodResult != 0) {
            Log.e("Permissions", "Failed to set permissions for: ${file.absolutePath}")
        }

        val chownResult = executeRootCommand("chown -R u0_a76:u0_a76 ${file.absolutePath}")
        if (chownResult != 0) {
            Log.e("Ownership", "Failed to set owner for: ${file.absolutePath}")
        }
    }

    private fun executeRootCommand(command: String): Int {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor()
            return process.exitValue()
        } catch (e: IOException) {
            Log.e("RootCommand", "Error executing root command: ${e.message}", e)
        } catch (e: InterruptedException) {
            Log.e("RootCommand", "Root command interrupted: ${e.message}", e)
        }
        return -1
    }

    fun compareTreesFromScanToJson(currentScan:ScanInfo):String{

        val currentNodeFile = File(currentScan.scanFile)
        val currentNode = loadNodeFromFile(currentNodeFile)
        val prevNodeFilePath = dbHelper.getPreviousScanFileById(currentScan.id)
        val prevNode = if(prevNodeFilePath != null){
            loadNodeFromFile(File(prevNodeFilePath))
        }else{
            null
        }
        return gson.toJson(fileScanner.compareTreesToSpannableString(prevNode,currentNode))
    }

    private fun uploadNodeToFile(scan: FileNode, timestamp: LocalDateTime): File {
        val fileName = "scan_${timestamp.format(FILE_DATE_FORMAT)}.json"
        val file = File(scansDirectory, fileName)
        file.writeText(gson.toJson(scan))
        return file
    }

    private fun loadNodeFromFile(file: File): FileNode {
        return if (file.exists()) {
            try {
                gson.fromJson(file.readText(), FileNode::class.java)
            } catch (e: Exception) {
                throw NodeFormatException("Deserialization error detected")
            }
        } else {
            throw NodeIsAbsentException("Node not found")
        }
    }
}