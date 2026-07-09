package com.streamflixreborn.streamflix.utils.download

import android.content.Context
import android.os.Build
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.format
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Resolves provider streams and downloads them one at a time. */
object VideoDownloadQueue {
    private data class Request(
        val context: Context,
        val id: String,
        val type: Video.Type,
        val title: String,
        val subtitle: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Request>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (request in requests) download(request)
        }
    }

    fun enqueueMovie(context: Context, movie: Movie) = enqueue(
        context,
        Request(
            context = context.applicationContext,
            id = movie.id,
            type = Video.Type.Movie(
                id = movie.id,
                title = movie.title,
                releaseDate = movie.released?.format("yyyy-MM-dd").orEmpty(),
                poster = movie.poster ?: movie.banner.orEmpty(),
                imdbId = movie.imdbId,
            ),
            title = movie.title,
            subtitle = movie.released?.format("yyyy").orEmpty(),
        ),
    )

    fun enqueueEpisode(context: Context, episode: Episode) = enqueue(
        context,
        Request(
            context = context.applicationContext,
            id = episode.id,
            type = Video.Type.Episode(
                id = episode.id,
                number = episode.number,
                title = episode.title,
                poster = episode.poster,
                overview = episode.overview,
                tvShow = Video.Type.Episode.TvShow(
                    id = episode.tvShow?.id.orEmpty(),
                    title = episode.tvShow?.title.orEmpty(),
                    poster = episode.tvShow?.poster,
                    banner = episode.tvShow?.banner,
                    releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                    imdbId = episode.tvShow?.imdbId,
                ),
                season = Video.Type.Episode.Season(
                    number = episode.season?.number ?: 0,
                    title = episode.season?.title,
                ),
            ),
            title = episode.tvShow?.title.orEmpty(),
            subtitle = " S${episode.season?.number ?: 0} E${episode.number} ${episode.title.orEmpty()}",
        ),
    )

    fun enqueueEpisodes(context: Context, episodes: List<Episode>) {
        episodes.forEach { enqueueEpisode(context, it) }
    }

    private fun enqueue(context: Context, request: Request) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        scope.launch {
            requests.send(request.copy())
        }
    }

    private suspend fun download(request: Request) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val provider = UserPreferences.currentProvider ?: return
        val server = provider.getServers(request.id, request.type).firstOrNull() ?: return
        val video = provider.getVideo(server).takeIf { it.source.isNotBlank() } ?: return
        val completion = CompletableDeferred<Boolean>()
        VideoDownloader(
            context = request.context,
            getCurrentVideo = { video },
            getCurrentServer = { server },
            getArgs = { VideoDownloadArgs(request.title, request.subtitle) },
            onFinished = { completion.complete(it) },
        ).start()
        completion.await()
    }
}
