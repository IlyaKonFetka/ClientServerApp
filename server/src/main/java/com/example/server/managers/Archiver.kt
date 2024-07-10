package com.example.server.managers

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException

class Archiver {
    companion object {
        private const val TAG = "Archiver"
    }

    suspend fun zipDirectory(directoryPath: String, zipFilePath: String) = withContext(Dispatchers.IO) {
        val command = "tar -czf $zipFilePath -C $directoryPath ."
        executeCommand(command)
    }

    suspend fun unzip(zipFilePath: String, destDirectory: String) = withContext(Dispatchers.IO) {
        val command = "tar -xzf $zipFilePath -C $destDirectory"
        executeCommand(command)
    }

    private fun executeCommand(command: String) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                Log.d(TAG, line!!)
            }

            var errorLine: String?
            while (errorReader.readLine().also { errorLine = it } != null) {
                Log.e(TAG, errorLine!!)
            }

            val exitVal = process.waitFor()
            if (exitVal != 0) {
                throw IOException("Command execution failed with exit code: $exitVal")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command: ${e.message}")
            throw IOException("Error executing command: ${e.message}")
        }
    }
}