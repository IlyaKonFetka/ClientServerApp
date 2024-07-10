package com.example.server.data_classes

import java.time.LocalDateTime

data class ScanInfo(
    val id: Long,
    val scanFile: String,
    val archiveFile: String,
    val totalSize: String,
    val scanDurationMs: Long,
    val scanStartTime: LocalDateTime
)