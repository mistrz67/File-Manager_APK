package org.fossify.filemanager.network.transfer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.helpers.isOreoPlus
import org.fossify.filemanager.R
import org.fossify.filemanager.activities.MainActivity
import org.fossify.filemanager.network.core.TransferProgress
import org.fossify.filemanager.network.core.TransferResult
import java.util.concurrent.atomic.AtomicInteger

class TransferNotifications(private val context: Context) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        if (isOreoPlus()) {
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.transfer_channel_name), NotificationManager.IMPORTANCE_LOW)
            channel.description = context.getString(R.string.transfer_channel_description)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
    }

    /** Ongoing notification of the running transfer; [percent] < 0 shows an indeterminate bar. */
    fun progress(title: String, text: String, percent: Int) = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification_transfer)
        .setContentTitle(title)
        .setContentText(text)
        .setProgress(100, percent.coerceAtLeast(0), percent < 0)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setContentIntent(openAppIntent())
        .addAction(0, context.getString(R.string.transfer_cancel), cancelIntent())
        .build()

    fun update(notification: android.app.Notification) {
        manager.notify(ONGOING_ID, notification)
    }

    fun finished(request: TransferRequest, result: TransferResult) {
        val (title, text) = when {
            result.cancelled -> context.getString(R.string.transfer_cancelled) to ""
            result.failures.isNotEmpty() -> context.getString(R.string.transfer_failed_title) to
                context.getString(R.string.transfer_failed_text, result.failures.size, result.failures.first().error.message.orEmpty())

            else -> context.getString(R.string.transfer_done_title) to summary(request, result)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_transfer)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        manager.notify(RESULT_ID_BASE + resultCounter.incrementAndGet() % RESULT_ID_SPAN, notification)
    }

    private fun summary(request: TransferRequest, result: TransferResult): String {
        val done = context.getString(
            if (request.kind == TransferRequest.Kind.DELETE) R.string.transfer_deleted_text else R.string.transfer_done_text,
            result.completed,
        )
        return if (result.skipped > 0) "$done · ${context.getString(R.string.transfer_skipped_text, result.skipped)}" else done
    }

    fun describeProgress(progress: TransferProgress, isDelete: Boolean): Pair<String, Int> {
        if (isDelete) return context.getString(R.string.transfer_deleted_progress, progress.filesDone) to -1
        val percent = if (progress.bytesTotal > 0) (progress.bytesDone * 100 / progress.bytesTotal).toInt() else -1
        val text = context.getString(
            R.string.transfer_progress_files,
            progress.filesDone, progress.filesTotal, progress.bytesDone.formatSize(), progress.bytesTotal.formatSize(),
        )
        return (if (progress.currentName.isNotEmpty()) "${progress.currentName}\n$text" else text) to percent
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        context, 1, Intent(context, TransferService::class.java).setAction(TransferService.ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "network_transfers"
        const val ONGOING_ID = 7301
        private const val RESULT_ID_BASE = 7400
        private const val RESULT_ID_SPAN = 50
        private val resultCounter = AtomicInteger()
    }
}
