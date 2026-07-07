package com.streamflixreborn.streamflix.utils

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.streamflixreborn.streamflix.R
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.streamflixreborn.streamflix.fragments.player.PlayerMobileFragmentArgs
import com.streamflixreborn.streamflix.models.Video
import okhttp3.Headers
import okhttp3.internal.userAgent
import java.util.Base64
import kotlin.math.max

data class HlsStream(
    val uri: String,
    val bandwidth: Long,
    val codecs: String?,
    val resolution: String? = null,
    val audioGroup: String? = null
)

fun parseBestStreams(m3u8: String): Triple<HlsStream?, HlsStream?, HlsStream?> {
    val lines = m3u8.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val streams = mutableListOf<HlsStream>()
    var pendingStreamInf: Map<String, String>? = null

    for (line in lines) {
        when {
            line.startsWith("#EXT-X-STREAM-INF") -> {
                pendingStreamInf = parseAttributes(
                    line.substringAfter(":")
                )
            }

            !line.startsWith("#") && pendingStreamInf != null -> {
                val attrs = pendingStreamInf!!

                streams += HlsStream(
                    uri = line,
                    bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0,
                    codecs = attrs["CODECS"]?.trim('"'),
                    resolution = attrs["RESOLUTION"],
                    audioGroup = attrs["AUDIO"]?.trim('"')
                )

                pendingStreamInf = null
            }
        }
    }

    fun hasVideo(stream: HlsStream): Boolean {
        val codecs = stream.codecs?.lowercase() ?: return false
        return listOf(
            "avc",
            "hvc",
            "hev",
            "vp8",
            "vp9",
            "av01"
        ).any { codecs.contains(it) }
    }

    fun hasAudio(stream: HlsStream): Boolean {
        val codecs = stream.codecs?.lowercase() ?: return false
        return listOf(
            "mp4a",
            "opus",
            "ac-3",
            "ec-3",
            "vorbis",
            "flac"
        ).any { codecs.contains(it) }
    }

    fun isMuxed(stream: HlsStream): Boolean =
        hasVideo(stream) && hasAudio(stream)

    val muxed = streams
        .filter(::isMuxed)
        .maxByOrNull { it.bandwidth }

    if (muxed != null) {
        return Triple(
            null,
            null,
            muxed
        )
    }

    val bestVideo = streams
        .filter { hasVideo(it) && !hasAudio(it) }
        .maxByOrNull { it.bandwidth }

    val bestAudio = streams
        .filter { hasAudio(it) && !hasVideo(it) }
        .maxByOrNull { it.bandwidth }

    return Triple(
        bestAudio,
        bestVideo,
        null
    )
}

private fun parseAttributes(input: String): Map<String, String> {
    val result = mutableMapOf<String, String>()

    Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")
        .findAll(input)
        .forEach {
            result[it.groupValues[1]] = it.groupValues[2]
        }

    return result
}

@SuppressLint("MissingPermission")

@RequiresApi(Build.VERSION_CODES.Q)
class VideoDownloader(
    private val context: Context,
    private val getCurrentVideo: () -> Video?,
    private val getArgs: () -> PlayerMobileFragmentArgs,
    private val client: OkHttpClient = OkHttpClient(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {

    private val parallelism = 16
    private val retryLimit = 3

    private var restartWatcher = false
    private var isDownloading = false

    @Volatile
    private var bestAudio: String = ""
    @Volatile
    private var bestVideo: String = ""

    @Volatile
    private var bestAudioVideo: String = ""

    private val refreshMutex = Mutex()

    private var queue = Channel<Segment>(Channel.UNLIMITED)

    private var title: String? = getArgs().title + getArgs().subtitle

    // paths
    private val tempDir: File by lazy {
        File(context.cacheDir, "hls/session_${System.currentTimeMillis()}").apply {
            mkdirs()
        }
    }
    private val audioFiles = Collections.synchronizedList(mutableListOf<File>())
    private val videoFiles = Collections.synchronizedList(mutableListOf<File>())
    private val audioOutputFile = File(tempDir, "audio.ts")
    private val videoOutputFile = File(tempDir, "video.ts")

    // segments decryption
    private var aesKey: ByteArray? = null
    private var aesIv: ByteArray? = null
    private var keyUrl: String? = null

    // segments to dl count
    @Volatile
    private var audioSegToDownload = 0
    @Volatile
    private var videoSegToDownload = 0

    private val notificationId = 1
    private val notificationChannelId = "streamflix download"
    private val notificationBuilder =
        NotificationCompat.Builder(this.context, notificationChannelId)
            .setSmallIcon(R.drawable.exo_styled_controls_download)
            .setContentTitle("Downloading $title")
            .setContentText("Download in progress...")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOnlyAlertOnce(true)
            .setProgress(100, 0, false)

    private val notificationManager = NotificationManagerCompat.from(this.context)
    private val notificationChannel = NotificationChannel(
        notificationChannelId,
        "StreamFlix Downloads",
        NotificationManager.IMPORTANCE_HIGH
    )

    data class Segment(
        val index: Int,
        var url: String,
        val file: File,
        var attempts: Int = 0
    )

    fun start() {
        notificationManager.createNotificationChannel(notificationChannel)
        if (isDownloading) return
        isDownloading = true
        scope.launch {
        startWorkers()
            refreshPlaylistsAndEnqueue()
        }
        notificationManager.notify(notificationId, notificationBuilder.build())
    }

    private fun resetState() {
        isDownloading = false
        restartWatcher = false

        audioSegToDownload = 0
        videoSegToDownload = 0

        audioFiles.clear()
        videoFiles.clear()

        bestAudio = ""
        bestVideo = ""

        aesKey = null
        aesIv = null
        keyUrl = null
        queue = Channel(Channel.UNLIMITED)

    }

    private fun startWorkers() {
        repeat(parallelism) {
            scope.launch {
                for (segment in queue) {
                    Log.d("SEG:", "downloading segment...")
                    downloadSegmentSafe(segment)
                }
            }
        }
    }

    private fun ensureKeyLoaded() {
        if (aesKey != null) return
        if (keyUrl == null) return

        val baseUrl = UserPreferences.currentProvider!!.baseUrl

        // TODO: Baseurl changed. find a way to take the extractor's main url
        // TODO: parseBestAudio finds an audio+video, but it's only video. fix it.

        val resolved = if (keyUrl!!.startsWith("http")) {
            keyUrl!!
        } else {
            baseUrl.split("//")[0] + "//" + baseUrl.split("//")[1].split("/")[0] + keyUrl!!
        }

        val response = makeRequest(resolved)
        aesKey = response.toByteArray()
    }

    private suspend fun downloadSegmentSafe(segment: Segment) {
        try {
            downloadSegment(segment.url, segment.file)

            synchronized(this) {
                if (segment.file.name.startsWith("audio")) {
                    audioFiles.add(segment.file)
                } else {
                    videoFiles.add(segment.file)
                }
            }

        } catch (e: Exception) {
            Log.d("EXCEPTION:", e.message.toString())
            segment.attempts++

            val isAuthError = e.message?.contains("401") == true ||
                    e.message?.contains("403") == true

            if (isAuthError) {
                handleTokenExpiry()
                queue.send(segment.copy(url = segment.url))
                return
            }

            if (segment.attempts < retryLimit) {
                queue.send(segment)
            } else {
                Log.e("HLS", "Failed segment ${segment.index}")
            }
        }
    }

    private fun downloadSegment(url: String, file: File) {
        val body = makeRequest(url)

        ensureKeyLoaded()

        val encrypted = body.toByteArray()

        val decrypted = decryptAes128(
            encrypted,
            aesKey!!,
            aesIv ?: ByteArray(16)
        )

        file.outputStream().use { it.write(decrypted) }
    }

    private suspend fun refreshPlaylistsAndEnqueue() {
        refreshMutex.withLock {

            Log.d("HLS", "Refreshing playlists...")

            resolveStreams()

            if (bestAudio != "" && bestVideo != "" ) {
                val audioSegments = parsePlaylist(bestAudio, "audio")
                val videoSegments = parsePlaylist(bestVideo, "video")
                audioSegToDownload = audioSegments.size
                videoSegToDownload = videoSegments.size
                enqueueSegments(audioSegments, videoSegments)
            } else {
                val audioVideoSegments = parsePlaylist(bestAudioVideo, "video")
                audioSegToDownload = 0
                videoSegToDownload = audioVideoSegments.size
                enqueueSegments(audioVideoSegments, emptyList())
            }
            title = getArgs().title + getArgs().subtitle
            startCompletionWatcher()
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.startsWith("0x")) hex.substring(2) else hex
        return clean.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }

    private fun decodeBase64Uri(uri: String): String? {
        return try {
            val parts = uri.split(",")
            if (parts.size == 2 && parts[0].contains(";base64")) {
                val base64Data = parts[1]
                val decodedBytes = Base64.getDecoder().decode(base64Data)
                String(decodedBytes, Charsets.UTF_8)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun makeRequest(uri: String): String {
        val headers = mapOf(
            "User-Agent" to userAgent,
        ) + (getCurrentVideo()?.headers ?: emptyMap())

        val request = Request.Builder()
            .url(uri)
            .headers(
                Headers.Builder().apply {
                    headers.forEach { (key, value) ->
                        add(key, value)
                    }
                }.build()
            )
            .build()

        client.newCall(request).execute().use {
            if (!it.isSuccessful) {
                Log.d("MAKEREQUEST FAILED(${it.code}):", uri)
                throw RuntimeException("Playlist failed: ${it.code}")
            }
            return it.body?.string() ?: ""
        }
    }


    private fun resolveStreams() {
        val uri = getCurrentVideo()?.source.toString()
        val decoded = if (uri.startsWith("http"))  {
            makeRequest(uri)
        } else decodeBase64Uri(uri)

        val (bestAudioTemp, bestVideoTemp, bestAudioVideoTemp) = parseBestStreams(decoded!!)

        bestAudio = bestAudioTemp?.uri ?: ""
        bestVideo = bestVideoTemp?.uri ?: ""
        bestAudioVideo = bestAudioVideoTemp?.uri ?: ""

        if (!bestAudio.startsWith("http") && bestAudio != "") {
            val cdn = uri.split("master")[0]
            bestAudio = cdn + bestAudio
        }

        if (!bestVideo.startsWith("http") && bestVideo != "") {
            val cdn = uri.split("master")[0]
            bestVideo = cdn + bestVideo
        }

        if (!bestAudioVideo.startsWith("http") && bestAudioVideo != "") {
            val cdn = uri.split("master")[0]
            bestAudioVideo = cdn + bestAudioVideo
        }

        Log.d("BEST-AUDIO", bestAudio)
        Log.d("BEST-VIDEO", bestVideo)
        Log.d("BEST-AUDIOVIDEO", bestAudioVideo)
    }

    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val secretKey = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)

        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        return cipher.doFinal(data)
    }

    private fun parsePlaylist(url: String, type: String): List<Segment> {
        val playlistText = makeRequest(url)
        var lines = playlistText.split("\n")
            .filter { it.isNotBlank() }

        lines.forEach { line ->
            if (line.startsWith("#EXT-X-KEY")) {
                val ivMatch = Regex("IV=0x([0-9A-Fa-f]+)").find(line)
                val encKeyRoute = Regex("URI=\"([^\"]+)\"").find(line)

                encKeyRoute?.groupValues?.get(1)?.let { encKey ->
                    keyUrl = encKey
                }

                ivMatch?.groupValues?.get(1)?.let { aesIv = hexToBytes(it) }
            }
        }

        lines = lines.filter { !it.startsWith("#") }

        return lines.mapIndexedNotNull { index, segmentUrl ->

            val file = File(tempDir, "$type-$index.ts")

            // SKIP if already downloaded on disk
            if (file.exists() && file.length() > 0) {
                null
            } else {
                Segment(
                    index = index,
                    url = segmentUrl,
                    file = file
                )
            }
        }
    }

    private suspend fun enqueueSegments(videoSegments: List<Segment>, audioSegments: List<Segment>) {
        val max = max(audioSegments.size, videoSegments.size)

        for (i in 0 until max) {
            audioSegments.getOrNull(i)?.let { queue.send(it) }
            videoSegments.getOrNull(i)?.let { queue.send(it) }
        }
    }

    private suspend fun handleTokenExpiry() {
        refreshMutex.withLock {
            restartWatcher = true
            Log.d("HLS", "Token expired → refreshing playlists")

            queue.close()

            queue = Channel(Channel.UNLIMITED)

            resolveStreams()

            // replace queue reference safely (simple restart model)
            if (bestAudio != "" && bestVideo != "" ) {
                val audioSegments = parsePlaylist(bestAudio, "audio")
                val videoSegments = parsePlaylist(bestVideo, "video")
                audioSegToDownload = audioSegments.size
                videoSegToDownload = videoSegments.size
                enqueueSegments(audioSegments, videoSegments)
            } else {
                val audioVideoSegments = parsePlaylist(bestAudioVideo, "video")
                audioSegToDownload = 0
                videoSegToDownload = audioVideoSegments.size
                enqueueSegments(audioVideoSegments, emptyList())
            }

            startWorkersFromQueue(queue)
            startCompletionWatcher()
        }
    }

    private fun startWorkersFromQueue(newQueue: Channel<Segment>) {
        repeat(parallelism) {
            scope.launch {
                for (segment in newQueue) {
                    downloadSegmentSafe(segment)
                }
            }
        }
    }

    fun mergeTsSegments(
        segmentFiles: List<File>,
        outputFile: File
    ) {
        outputFile.outputStream().use { output ->
            for (file in segmentFiles.sortedBy {
                it.name.substringAfter("-")
                    .substringBefore(".")
                    .toInt()
            }) {
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
        }
    }

    fun buildFinalStreams() {
        scope.launch(Dispatchers.IO) {

            Log.d("HLS", "Merging audio segments...")
            mergeTsSegments(audioFiles, audioOutputFile)

            Log.d("HLS", "Merging video segments...")
            mergeTsSegments(videoFiles, videoOutputFile)

            Log.d("HLS", "Done: ${audioOutputFile.path} + ${videoOutputFile.path}")

            Log.d("TS", "audio exists=${audioOutputFile.exists()} size=${audioOutputFile.length()}")
            Log.d("TS", "video exists=${videoOutputFile.exists()} size=${videoOutputFile.length()}")


            val outputFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "$title.mp4"
            )

            notificationBuilder
                .setProgress(0, 0, false)
                .setContentTitle("Processing $title.mp4")
                .setContentText("Merging streams into mp4...")
            notificationManager.notify(notificationId, notificationBuilder.build())
            mergeToMp4(audioOutputFile, videoOutputFile, outputFile)
        }
    }

    private fun startCompletionWatcher() {
        if (restartWatcher) {
            restartWatcher = false
        }
        scope.launch {
            while (true) {
                if (restartWatcher) break
                delay(1000)

                val progress = (audioFiles.size + videoFiles.size).toDouble() / (audioSegToDownload + videoSegToDownload).toDouble()*100
                notificationBuilder.setProgress(100, progress.toInt(), false)
                notificationManager.notify(notificationId, notificationBuilder.build())
                Log.d(
                    "AUDIO/VIDEO DL:",
                    "${audioFiles.size}/$audioSegToDownload ${videoFiles.size}/$videoSegToDownload"
                )

                if (audioFiles.size == audioSegToDownload && videoFiles.size == videoSegToDownload && audioSegToDownload != 0 && videoSegToDownload != 0) {
                    Log.d("HLS", "Download complete → merging streams")
                    buildFinalStreams()
                    break
                }
            }
        }
    }


    fun mergeToMp4(audioInputFile: File, videoInputFile: File, outputMp4: File) {
        scope.launch(Dispatchers.IO) {


            if (!audioOutputFile.exists() || !videoOutputFile.exists()) {
                Log.e("FFMPEG", "Missing TS files")
                return@launch
            }

            val cmd = """
            -y
            -i '${audioInputFile.path}' 
            -i '${videoInputFile.path}'
            -c:v copy
            -c:a copy
            -shortest 
            '$outputMp4'
        """.trimIndent().replace("\n", " ")

            FFmpegKit.executeAsync(
                cmd,
                { session ->
                    if (ReturnCode.isSuccess(session.returnCode)) {
                        Log.d("FFMPEG", "Done")
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.provider",
                            outputMp4
                        )

                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }

                        val pendingIntent = PendingIntent.getActivity(
                            context,
                            0,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )


                        isDownloading = false
                        notificationBuilder
                            .setProgress(0, 0, false)
                            .setContentTitle("$title.mp4 completed!")
                            .setContentText(outputMp4.name)
                            .setContentIntent(pendingIntent)
                            .setProgress(0, 0, false)
                        notificationManager.notify(notificationId, notificationBuilder.build())
                        resetState()
                    } else {
                        Log.e("FFMPEG", "Failed")
                    }
                    resetState()
                },
                { log ->
                    Log.d("FFMPEG", log.message)
                },
                { statistics ->
                    val totalSize = videoInputFile.length() + audioInputFile.length()
                    val processed = statistics.size
                    val percent = (processed.toDouble() / totalSize * 100).toInt()
                    notificationBuilder
                        .setProgress(0, 0, false)
                        .setContentTitle("Processing $title.mp4")
                        .setContentText("Merging in progress...")
                        .setProgress(100, percent, false)
                    notificationManager.notify(notificationId, notificationBuilder.build())
                }
            )
        }
    }
}