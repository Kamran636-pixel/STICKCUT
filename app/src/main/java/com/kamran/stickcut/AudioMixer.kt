package com.kamran.stickcut

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume

// Changes the volume of the voiceover along the whole video:
// fade in at the start, fade out at the end (Scene fades).
// offsetMs = where this voiceover part starts inside the whole video.
@UnstableApi
class FadeAudioProcessor(
    private val offsetMs: Long,
    private val totalMs: Long,
    fades: SceneFadeState
) : BaseAudioProcessor() {

    private val effective = SceneFadeEngine.effective(fades, totalMs)
    private val fadeInMs = effective.first
    private val fadeOutMs = effective.second
    private var framesDone = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    private fun gainAt(timeMs: Long): Float {
        var gain = 1f
        if (fadeInMs > 0L && timeMs < fadeInMs) {
            gain = minOf(gain, timeMs.toFloat() / fadeInMs.toFloat())
        }
        if (fadeOutMs > 0L && timeMs > totalMs - fadeOutMs) {
            gain = minOf(gain, (totalMs - timeMs).toFloat() / fadeOutMs.toFloat())
        }
        return gain.coerceIn(0f, 1f)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val frameBytes = format.bytesPerFrame
        val frames = inputBuffer.remaining() / frameBytes
        val output = replaceOutputBuffer(frames * frameBytes)
        val rate = format.sampleRate
        val channels = format.channelCount
        for (i in 0 until frames) {
            val timeMs = offsetMs + framesDone * 1000L / rate
            val gain = gainAt(timeMs)
            for (c in 0 until channels) {
                val sample = inputBuffer.short.toInt()
                val scaled = (sample * gain).toInt().coerceIn(-32768, 32767)
                output.putShort(scaled.toShort())
            }
            framesDone++
        }
        output.flip()
    }

    override fun onFlush() {
        framesDone = 0L
    }
}

// Joins all voiceover parts into one sound file (with the fades).
@UnstableApi
object AudioMixer {

    const val CANCELLED = "__cancelled__"

    private fun errorText(error: Throwable): String {
        val detail = error.message
        val first = error.stackTrace.firstOrNull()
        return error.javaClass.name +
            (if (detail.isNullOrBlank()) " (no message)" else ": $detail") +
            (if (first != null) "\n   at $first" else "")
    }

    // Returns null when the sound file is ready, CANCELLED when stopped,
    // or an error text. Media3 must be used on the main thread, so this
    // function switches to it by itself.
    suspend fun exportVoiceover(
        context: Context,
        audios: List<ImportedAudio>,
        fades: SceneFadeState,
        totalMs: Long,
        output: File,
        isCancelled: () -> Boolean
    ): String? {
        return withContext(Dispatchers.Main) {
            var transformer: Transformer? = null
            var continuation: CancellableContinuation<String?>? = null

            val watcher = launch {
                while (true) {
                    delay(250)
                    if (isCancelled()) {
                        try {
                            transformer?.cancel()
                        } catch (e: Exception) {
                            // ignore
                        }
                        val waiting = continuation
                        if (waiting != null && waiting.isActive) waiting.resume(CANCELLED)
                        break
                    }
                }
            }

            val result = suspendCancellableCoroutine<String?> { cont ->
                continuation = cont
                try {
                    if (output.exists()) output.delete()
                    val effective = SceneFadeEngine.effective(fades, totalMs)
                    val fadesOn = effective.first > 0L || effective.second > 0L

                    var offset = 0L
                    val items = audios.map { audio ->
                        val builder = EditedMediaItem.Builder(MediaItem.fromUri(audio.uri))
                        if (fadesOn) {
                            val processor: AudioProcessor = FadeAudioProcessor(offset, totalMs, fades)
                            builder.setEffects(Effects(listOf(processor), emptyList()))
                        }
                        offset += audio.durationMs
                        builder.build()
                    }
                    val composition = Composition.Builder(
                        mutableListOf(EditedMediaItemSequence(items))
                    ).build()

                    val listener = object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (cont.isActive) {
                                cont.resume(
                                    "Voiceover export failed (code ${exportException.errorCodeName}).\n" +
                                        errorText(exportException)
                                )
                            }
                        }
                    }
                    val t = Transformer.Builder(context)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(listener)
                        .build()
                    transformer = t
                    t.start(composition, output.absolutePath)
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resume("Could not start the voiceover export:\n" + errorText(e))
                }
            }
            watcher.cancel()
            result
        }
    }
}

// Puts a silent video file and a sound file into one MP4 without re-encoding.
object MediaMerger {

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    // Returns null on success, or an error text.
    fun merge(video: File, audio: File, output: File): String? {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        var success = false
        try {
            if (output.exists()) output.delete()
            videoExtractor.setDataSource(video.absolutePath)
            audioExtractor.setDataSource(audio.absolutePath)
            val videoTrack = findTrack(videoExtractor, "video/")
            val audioTrack = findTrack(audioExtractor, "audio/")
            if (videoTrack < 0) return "The rendered video has no picture track."
            if (audioTrack < 0) return "The voiceover file has no sound track."
            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)

            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val videoDurationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                videoFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                Long.MAX_VALUE
            }

            val writer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            val videoOut = writer.addTrack(videoFormat)
            val audioOut = writer.addTrack(audioExtractor.getTrackFormat(audioTrack))
            writer.start()
            started = true

            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var videoDone = false
            var audioDone = false

            while (!videoDone || !audioDone) {
                val videoTime = if (videoDone) Long.MAX_VALUE else videoExtractor.sampleTime
                val audioTime = if (audioDone) Long.MAX_VALUE else audioExtractor.sampleTime
                if (!videoDone && videoTime < 0L) {
                    videoDone = true
                    continue
                }
                if (!audioDone && (audioTime < 0L || audioTime > videoDurationUs)) {
                    // Sound that runs past the end of the picture is cut off.
                    audioDone = true
                    continue
                }
                val useVideo = !videoDone && (audioDone || videoTime <= audioTime)
                val extractor = if (useVideo) videoExtractor else audioExtractor
                val track = if (useVideo) videoOut else audioOut

                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    if (useVideo) videoDone = true else audioDone = true
                    continue
                }
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                info.set(0, size, extractor.sampleTime, flags)
                writer.writeSampleData(track, buffer, info)
                extractor.advance()
            }
            success = true
            return null
        } catch (e: Throwable) {
            val detail = e.message
            return "Joining sound and video failed: " + e.javaClass.name +
                (if (detail.isNullOrBlank()) "" else ": $detail")
        } finally {
            try {
                videoExtractor.release()
            } catch (e: Exception) {
                // ignore
            }
            try {
                audioExtractor.release()
            } catch (e: Exception) {
                // ignore
            }
            try {
                if (started) muxer?.stop()
            } catch (e: Exception) {
                // ignore
            }
            try {
                muxer?.release()
            } catch (e: Exception) {
                // ignore
            }
            if (!success) {
                try {
                    output.delete()
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
    }
}
