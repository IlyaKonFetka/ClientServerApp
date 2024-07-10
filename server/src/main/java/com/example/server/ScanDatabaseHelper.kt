package com.example.server

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.example.common_code.data_classes.DirectoryEntry
import com.example.server.data_classes.ScanInfo
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class ScanDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "scans.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_SCANS = "scans"

        //ENTRY TEMPLATE
        private const val COLUMN_ID = "id"
        private const val COLUMN_SCAN_FILE = "scan_file"
        private const val COLUMN_ARCHIVE_FILE = "archive_file"
        //META_VALUES
        private const val COLUMN_TOTAL_SIZE = "total_size"
        private const val COLUMN_SCAN_DURATION = "scan_duration"
        private const val COLUMN_SCAN_START_TIME = "scan_start_time"

        private val DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTable = """
            CREATE TABLE $TABLE_SCANS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_SCAN_FILE TEXT,
                $COLUMN_ARCHIVE_FILE TEXT,
                $COLUMN_TOTAL_SIZE TEXT,
                $COLUMN_SCAN_DURATION INTEGER,
                $COLUMN_SCAN_START_TIME TEXT
            )
        """.trimIndent()
        db.execSQL(createTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("""
            CREATE TABLE ${TABLE_SCANS}_new (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_SCAN_FILE TEXT,
                $COLUMN_ARCHIVE_FILE TEXT,
                $COLUMN_TOTAL_SIZE INTEGER,
                $COLUMN_SCAN_DURATION INTEGER,
                $COLUMN_SCAN_START_TIME TEXT
            )
        """)

            db.execSQL("""
            INSERT INTO ${TABLE_SCANS}_new 
            SELECT $COLUMN_ID, $COLUMN_SCAN_FILE, $COLUMN_ARCHIVE_FILE, 
                   $COLUMN_TOTAL_SIZE, $COLUMN_SCAN_DURATION, $COLUMN_SCAN_START_TIME 
            FROM $TABLE_SCANS
        """)
            db.execSQL("DROP TABLE $TABLE_SCANS")
            db.execSQL("ALTER TABLE ${TABLE_SCANS}_new RENAME TO $TABLE_SCANS")
        }
    }

    fun insertScan(scanFile: String, archiveFile: String, totalSize: String, scanDurationMs: Long, scanStartTime: LocalDateTime): Long {
        val values = ContentValues().apply {
            put(COLUMN_SCAN_FILE, scanFile)
            put(COLUMN_ARCHIVE_FILE, archiveFile)
            put(COLUMN_TOTAL_SIZE, totalSize)
            put(COLUMN_SCAN_DURATION, scanDurationMs)
            put(COLUMN_SCAN_START_TIME, scanStartTime.format(DATE_FORMAT))
        }
        return writableDatabase.insert(TABLE_SCANS, null, values)
    }
    fun getPreviousScanFileById(id: Long): String? {
        val db = readableDatabase
        var previousScanFile: String? = null

        val currentElementQuery = "SELECT $COLUMN_SCAN_START_TIME FROM $TABLE_SCANS WHERE $COLUMN_ID = ?"
        val cursor = db.rawQuery(currentElementQuery, arrayOf(id.toString()))
        var currentScanStartTime: LocalDateTime? = null

        if (cursor.moveToFirst()) {
            val scanStartTimeStr = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SCAN_START_TIME))
            currentScanStartTime = LocalDateTime.parse(scanStartTimeStr, DATE_FORMAT)
        }
        cursor.close()

        currentScanStartTime?.let {
            val previousElementQuery = """
                SELECT $COLUMN_SCAN_FILE FROM $TABLE_SCANS 
                WHERE $COLUMN_SCAN_START_TIME < ? 
                ORDER BY $COLUMN_SCAN_START_TIME DESC 
                LIMIT 1
            """.trimIndent()

            val previousCursor = db.rawQuery(previousElementQuery, arrayOf(it.format(DATE_FORMAT)))
            if (previousCursor.moveToFirst()) {
                previousScanFile = previousCursor.getString(previousCursor.getColumnIndexOrThrow(COLUMN_SCAN_FILE))
            }
            previousCursor.close()
        }

        db.close()
        return previousScanFile
    }
    fun getScanById(id: Long): ScanInfo? {
        val query = "SELECT * FROM $TABLE_SCANS WHERE $COLUMN_ID = ?"
        val cursor = readableDatabase.rawQuery(query, arrayOf(id.toString()))

        var scanInfo: ScanInfo? = null
        with(cursor) {
            if (moveToFirst()) {
                val scanFile = getString(getColumnIndexOrThrow(COLUMN_SCAN_FILE))
                val archiveFile = getString(getColumnIndexOrThrow(COLUMN_ARCHIVE_FILE))
                val totalSize = getString(getColumnIndexOrThrow(COLUMN_TOTAL_SIZE))
                val scanDurationMs = getLong(getColumnIndexOrThrow(COLUMN_SCAN_DURATION))
                val scanStartTimeStr = getString(getColumnIndexOrThrow(COLUMN_SCAN_START_TIME))
                val scanStartTime = LocalDateTime.parse(scanStartTimeStr, DATE_FORMAT)

                scanInfo = ScanInfo(id, scanFile, archiveFile, totalSize, scanDurationMs, scanStartTime)
            }
        }
        cursor.close()
        return scanInfo
    }

    fun getAllDirectoryEntries(): List<DirectoryEntry> {
        val entries = mutableListOf<DirectoryEntry>()
        val db = this.readableDatabase
        val selectQuery = "SELECT $COLUMN_ID, $COLUMN_SCAN_START_TIME FROM $TABLE_SCANS ORDER BY $COLUMN_SCAN_START_TIME DESC"

        db.rawQuery(selectQuery, null).use { cursor ->
            if (cursor.moveToFirst()) {
                do {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID)).toString()
                    val scanStartTimeStr = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SCAN_START_TIME))

                    val dateTime = LocalDateTime.parse(scanStartTimeStr, DATE_FORMAT)
                    val formattedDate = dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

                    entries.add(DirectoryEntry(formattedDate, id))
                } while (cursor.moveToNext())
            }
        }

        return entries
    }

    fun getLastScan(): ScanInfo? {
        val query = "SELECT * FROM $TABLE_SCANS ORDER BY $COLUMN_SCAN_START_TIME DESC LIMIT 1"
        val cursor = readableDatabase.rawQuery(query, null)

        var scanInfo: ScanInfo? = null
        with(cursor) {
            if (moveToLast()) {
                val id = getLong(getColumnIndexOrThrow(COLUMN_ID))
                val scanFile = getString(getColumnIndexOrThrow(COLUMN_SCAN_FILE))
                val archiveFile = getString(getColumnIndexOrThrow(COLUMN_ARCHIVE_FILE))
                val totalSize = getString(getColumnIndexOrThrow(COLUMN_TOTAL_SIZE))
                val scanDurationMs = getLong(getColumnIndexOrThrow(COLUMN_SCAN_DURATION))
                val scanStartTimeStr = getString(getColumnIndexOrThrow(COLUMN_SCAN_START_TIME))
                val scanStartTime = LocalDateTime.parse(scanStartTimeStr, DATE_FORMAT)

                scanInfo = ScanInfo(id, scanFile, archiveFile, totalSize, scanDurationMs, scanStartTime)
            }
        }
        cursor.close()
        return scanInfo
    }

    fun clearTable() {
        val db = writableDatabase
        db.delete(TABLE_SCANS, null, null)
        db.close()
    }

    fun isEmpty(): Boolean {
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_SCANS", null)
        var isEmpty = true
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                val count = cursor.getInt(0)
                isEmpty = (count == 0)
            }
            cursor.close()
        }
        return isEmpty
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    fun logAllScans() {
        val db = this.readableDatabase
        val query = "SELECT * FROM $TABLE_SCANS"
        val cursor = db.rawQuery(query, null)

        if (cursor.moveToFirst()) {
            Log.d("SCANS_LOG", "=== Records in $TABLE_SCANS ===")
            Log.d("SCANS_LOG", "ID | Scan File | Archive File | Total Size | Scan Duration | Scan Start Time")
            Log.d("SCANS_LOG", "-".repeat(80))

            do {
                val id = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_ID))
                val scanFile = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SCAN_FILE))
                val archiveFile = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ARCHIVE_FILE))
                val totalSize = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TOTAL_SIZE))
                val scanDuration = cursor.getInt(cursor.getColumnIndexOrThrow(COLUMN_SCAN_DURATION))
                val scanStartTime = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_SCAN_START_TIME))

                val formattedSize = String.format("%.2f MB", totalSize / (1024.0 * 1024.0))
                val formattedDuration = String.format("%d sec", scanDuration / 1000)
                val formattedDateTime = formatDateTime(scanStartTime)

                Log.d("SCANS_LOG", String.format("%-3d | %-20s | %-20s | %-10s | %-13s | %s",
                    id,
                    truncateString(scanFile, 20), truncateString(archiveFile, 20),
                    formattedSize, formattedDuration, formattedDateTime))
            } while (cursor.moveToNext())

            Log.d("SCANS_LOG", "=== End of records ===")
        } else {
            Log.d("SCANS_LOG", "No records found in $TABLE_SCANS")
        }
        cursor.close()
    }

    private fun truncateString(str: String, maxLength: Int): String {
        return if (str.length <= maxLength) str else str.substring(0, maxLength - 3) + "..."
    }

    private fun formatDateTime(dateTimeStr: String): String {
        val inputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val outputFormat = SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault())
        return try {
            val date = inputFormat.parse(dateTimeStr)
            outputFormat.format(date!!)
        } catch (e: Exception) {
            dateTimeStr
        }
    }
}