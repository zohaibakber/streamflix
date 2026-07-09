package com.streamflixreborn.streamflix.utils.download

import java.lang.ref.WeakReference

object VideoDownloadManager {
    private var downloaderRef: WeakReference<VideoDownloader>? = null

    var downloader: VideoDownloader?
        get() = downloaderRef?.get()
        set(value) {
            downloaderRef = value?.let { WeakReference(it) }
        }
}