package com.sinh.timelapse

/** UI values only; no source lists/bitmaps or live mutable store objects escape the owner. */
data class SessionReview(
    val id: String,
    val startedAt: Long,
    val mode: String,
    val cap: Int?,
    val fps: Int,
    val activeMillis: Long?,
    val unknownTail: Boolean,
    val selected: Int,
    val total: Int,
    val retained: Int,
    val bytes: Long,
    val stage: Long,
    val status: String,
    val progress: Int,
    val attempts: Int,
    val cleanupAttempts: Int,
    val error: String,
    val output: String?,
    val canRetry: Boolean,
    val canDelete: Boolean,
)
