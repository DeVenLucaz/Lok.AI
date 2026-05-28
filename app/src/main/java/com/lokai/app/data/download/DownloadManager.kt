package com.lokai.app.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.lokai.app.model.DownloadState
import com.lokai.app.model.DownloadedModel
import com.lokai.app.model.ModelEntry
import com.lokai.app.model.ModelVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG              = "DownloadManager"
private const val NOTIF_CHANNEL_ID = "lokai_downloads"
private const val NOTIF_ID_BASE    = 9000

// WorkManager input data keys
const val KEY_MODEL_ID       = "model_id"
const val KEY_DOWNLOAD_URL   = "download_url"
const val KEY_DEST_PATH      = "dest_path"
const val KEY_SHA256         = "sha256"
const val KEY_MODEL_NAME     = "model_name"
const val KEY_QUANT          = "quant"
const val KEY_RAM_REQUIRED   = "ram_required"
const val KEY_THINKING       = "thinking_trained"

// WorkManager progress keys
const val PROGRESS_BYTES_DL  = "bytes_downloaded"
const val PROGRESS_BYTES_TOT = "bytes_total"
const val PROGRESS_RESUMING  = "is_resuming"

/**
 * Central controller for all model downloads.
 *
 * - Owns a per-model [StateFlow<DownloadState>] that the UI observes
 * - Starts/cancels WorkManager workers
 * - Delegates actual HTTP work to [ModelDownloadWorker]
 * - Handles storage path scanning for pre-existing GGUFs
 *
 * FIX: replaced observeForever (caused observer leak + state flicker) with
 * WorkManager's getWorkInfosByTagFlow() collected on a private coroutine scope.
 * FIX: requests are now marked setExpedited() so Android/ColorOS doesn't defer
 * or kill the worker when the app is backgrounded.
 */
class DownloadManager(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)
    private val verifier    = ChecksumVerifier()
    private val scope       = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // modelId → state flow
    private val _states = mutableMapOf<String, MutableStateFlow<DownloadState>>()

    init {
        createNotificationChannel()
    }

    fun stateFor(modelId: String): StateFlow<DownloadState> {
        return _states.getOrPut(modelId) {
            MutableStateFlow(DownloadState.Idle)
        }.asStateFlow()
    }

    /**
     * Picks the best variant for [availableRamGb] and enqueues the download.
     *
     * @return the chosen [ModelVariant], or null if no variant fits
     */
    fun startDownload(
        model: ModelEntry,
        availableRamGb: Float,
        storageDir: File
    ): ModelVariant? {
        val variant = model.bestVariantFor(availableRamGb) ?: run {
            Log.w(TAG, "No fitting variant for ${model.id} at ${availableRamGb} GB RAM")
            return null
        }

        val destFile = File(storageDir, "${model.id}_${variant.quant}.gguf")
        val state    = _states.getOrPut(model.id) { MutableStateFlow(DownloadState.Idle) }
        state.value  = DownloadState.Queued

        val inputData = workDataOf(
            KEY_MODEL_ID     to model.id,
            KEY_DOWNLOAD_URL to variant.downloadUrl,
            KEY_DEST_PATH    to destFile.absolutePath,
            KEY_SHA256       to variant.sha256,
            KEY_MODEL_NAME   to model.name,
            KEY_QUANT        to variant.quant,
            KEY_RAM_REQUIRED to variant.ramRequiredGb,
            KEY_THINKING     to model.thinkingTrained
        )

        // FIX: setExpedited() tells Android this is user-initiated work that must
        // not be deferred. OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST
        // ensures it still runs if the expedited quota is exhausted.
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(inputData)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(downloadTag(model.id))
            .build()

        workManager.enqueueUniqueWork(
            downloadTag(model.id),
            ExistingWorkPolicy.KEEP,
            request
        )

        // FIX: use Flow (not observeForever) so the collector is tied to the
        // coroutine scope and can't leak or double-fire across re-compositions.
        scope.launch {
            workManager.getWorkInfosByTagFlow(downloadTag(model.id))
                .collect { infos ->
                    val info = infos?.firstOrNull() ?: return@collect
                    when (info.state) {
                        WorkInfo.State.RUNNING -> {
                            val dl     = info.progress.getLong(PROGRESS_BYTES_DL, 0L)
                            val total  = info.progress.getLong(PROGRESS_BYTES_TOT, -1L)
                            val resume = info.progress.getBoolean(PROGRESS_RESUMING, false)
                            state.value = DownloadState.Downloading(dl, total, resume)
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            state.value = DownloadState.Completed(destFile.absolutePath)
                        }
                        WorkInfo.State.FAILED -> {
                            state.value = DownloadState.Failed("Download failed")
                        }
                        WorkInfo.State.CANCELLED -> {
                            state.value = DownloadState.Cancelled
                            if (destFile.exists()) destFile.delete()
                        }
                        else -> { /* ENQUEUED / BLOCKED — still Queued */ }
                    }
                }
        }

        Log.i(TAG, "Enqueued download: ${model.id} ${variant.quant} → ${destFile.absolutePath}")
        return variant
    }

    /** Cancel a running download and clean up partial file. */
    fun cancelDownload(modelId: String, storageDir: File) {
        workManager.cancelUniqueWork(downloadTag(modelId))
        _states[modelId]?.value = DownloadState.Cancelled
        storageDir.listFiles()?.filter { it.name.startsWith(modelId) }?.forEach { it.delete() }
        Log.i(TAG, "Cancelled download for $modelId")
    }

    /** Reset state to Idle (used after delete or cancel dismiss) */
    fun resetState(modelId: String) {
        _states[modelId]?.value = DownloadState.Idle
    }

    /**
     * Scans [storageDir] for existing .gguf files that match models in [knownModelIds].
     */
    fun scanExistingGgufs(storageDir: File, knownModelIds: Set<String>): Map<String, String> {
        if (!storageDir.exists()) return emptyMap()
        return storageDir.listFiles()
            ?.filter { it.extension == "gguf" }
            ?.mapNotNull { file ->
                val modelId = knownModelIds.firstOrNull { id -> file.name.startsWith(id) }
                modelId?.let { modelId to file.absolutePath }
            }
            ?.toMap()
            ?: emptyMap()
    }

    // ─── Notification channel (required for foreground service on API 26+) ────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background model download progress"
                setSound(null, null)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun downloadTag(modelId: String) = "download_$modelId"
}

// ─── WorkManager Worker ───────────────────────────────────────────────────────

/**
 * Performs the actual HTTP download in a WorkManager coroutine worker.
 *
 * Features:
 * - Promotes to foreground service immediately so ColorOS / Android battery
 *   optimisation cannot kill it while the app is backgrounded (FIX)
 * - HTTP Range header resume (skips already-downloaded bytes)
 * - Streams directly to file (no temp copy)
 * - Reports progress via setProgress()
 * - SHA-256 verification after completion
 * - Deletes partial file on failure
 */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val verifier = ChecksumVerifier()

    // FIX: getForegroundInfo() is called by WorkManager when the worker is
    // promoted to an expedited / foreground job. Without this, the worker is
    // killed by Android when the app goes to background.
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val modelName = inputData.getString(KEY_MODEL_NAME) ?: "model"
        val notification = NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL_ID)
            .setContentTitle("Downloading $modelName")
            .setContentText("Download in progress…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()
        return ForegroundInfo(NOTIF_ID_BASE, notification)
    }

    override suspend fun doWork(): Result {
        val modelId  = inputData.getString(KEY_MODEL_ID)     ?: return Result.failure()
        val url      = inputData.getString(KEY_DOWNLOAD_URL) ?: return Result.failure()
        val destPath = inputData.getString(KEY_DEST_PATH)    ?: return Result.failure()
        val sha256   = inputData.getString(KEY_SHA256)       ?: ""
        val modelName = inputData.getString(KEY_MODEL_NAME)  ?: modelId

        val destFile = File(destPath)

        // FIX: promote to foreground immediately so the worker survives app backgrounding
        setForeground(getForegroundInfo())

        return try {
            val existingBytes = if (destFile.exists()) destFile.length() else 0L
            val isResuming    = existingBytes > 0

            Log.i(TAG, "Downloading $modelId | resuming=$isResuming | offset=$existingBytes")

            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout    = 60_000
                setRequestProperty("User-Agent", "LokAI/1.0 Android")
                if (isResuming) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
                connect()
            }

            val responseCode = connection.responseCode
            val isPartial    = responseCode == HttpURLConnection.HTTP_PARTIAL
            val isOk         = responseCode == HttpURLConnection.HTTP_OK

            if (!isOk && !isPartial) {
                Log.e(TAG, "HTTP $responseCode for $modelId")
                return Result.failure()
            }

            // If server doesn't support range requests, restart from 0
            val startOffset = if (isPartial) existingBytes else 0L
            if (!isPartial && isResuming) {
                destFile.delete()
                Log.i(TAG, "Server doesn't support Range; restarting download for $modelId")
            }

            val contentLength = connection.contentLengthLong
            val totalBytes    = if (isPartial) existingBytes + contentLength else contentLength
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                     as NotificationManager

            connection.inputStream.use { input ->
                FileOutputStream(destFile, isPartial).use { output ->
                    val buffer  = ByteArray(8192)
                    var written = startOffset
                    var bytes:  Int

                    while (input.read(buffer).also { bytes = it } != -1) {
                        if (isStopped) {
                            Log.i(TAG, "Worker stopped — cleaning up $modelId")
                            destFile.delete()
                            return Result.failure()
                        }
                        output.write(buffer, 0, bytes)
                        written += bytes

                        setProgress(
                            workDataOf(
                                PROGRESS_BYTES_DL  to written,
                                PROGRESS_BYTES_TOT to totalBytes,
                                PROGRESS_RESUMING  to isResuming
                            )
                        )

                        // Update notification progress every ~1 MB
                        if (written % (1024 * 1024) < 8192 && totalBytes > 0) {
                            val pct = (written * 100 / totalBytes).toInt()
                            val updatedNotif = NotificationCompat.Builder(
                                applicationContext, NOTIF_CHANNEL_ID
                            )
                                .setContentTitle("Downloading $modelName")
                                .setContentText("${written / (1024 * 1024)} MB / ${totalBytes / (1024 * 1024)} MB")
                                .setSmallIcon(android.R.drawable.stat_sys_download)
                                .setOngoing(true)
                                .setSilent(true)
                                .setProgress(100, pct, false)
                                .build()
                            nm.notify(NOTIF_ID_BASE, updatedNotif)
                        }
                    }
                }
            }

            connection.disconnect()
            Log.i(TAG, "Download complete for $modelId (${destFile.length()} bytes)")

            // Show verifying notification
            nm.notify(NOTIF_ID_BASE, NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL_ID)
                .setContentTitle("Verifying $modelName")
                .setContentText("Checking integrity…")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .setSilent(true)
                .setProgress(0, 0, true)
                .build())

            val valid = verifier.verify(destFile, sha256)
            nm.cancel(NOTIF_ID_BASE)

            if (!valid) {
                Log.e(TAG, "Checksum failed for $modelId — deleting")
                destFile.delete()
                return Result.failure()
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Download error for $modelId: ${e.message}")
            // Don't delete partial file — allows resume on next attempt
            Result.failure()
        }
    }
}
