package com.streamflixreborn.streamflix.utils.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi

class DownloadCancelReceiver : BroadcastReceiver() {

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == "CANCEL_DOWNLOAD") {
            VideoDownloadManager.downloader?.cancelDownload()
        }
    }
}