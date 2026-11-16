package com.kamran.stickcut

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File

// Makes a silent MP4 by drawing every frame with FrameComposer and sending it
// to the phone's H.264 encoder. Call render() away from the main thread.
object VideoRenderer {

    class Result(
        val ok: Boolean,
        val cancelled: Boolean,
        val message: String?
    )

    private class MuxState {
        var track = -1
        var started = false
        // Number of video frames written into the file so far.
        var written = 0L
    }

    private fun errorText(error: Throwable): String {
        val detail = error.message
        val first = error.stackTrace.firstOrNull()
        return error.javaClass.name +
            (if (detail.isNullOrBlank()) " (no message)" else ": $detail") +
            (if (first != null) "\n   at $first" else "")
    }

    // Takes finished frames out of the encoder and writes them into the file.
    // The encoder reads the surface with the system clock, so we give every
    // frame its own exact time (frame number / frame rate) instead.
    private fun drain(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        state: MuxState,
        info: MediaCodec.BufferInfo,
        fps: Int,
        endOfStream: Boolean
    ) {
        var waits = 0
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return
                waits++
                if (waits > 3000) {
                    throw IllegalStateException("The video encoder did not finish (waited 30 seconds).")
                }
                continue
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (state.started) throw IllegalStateException("The encoder changed its format twice.")
                state.track = muxer.addTrack(encoder.outputFormat)
                muxer.start()
                state.started = true
                continue
            }
            if (index < 0) continue

            val buffer = encoder.getOutputBuffer(index)
                ?: throw IllegalStateException("The encoder gave an empty output buffer.")
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                info.size = 0
            }
            if (info.size > 0) {
                if (!state.started) throw IllegalStateException("The file writer was not started.")
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                info.presentationTimeUs = state.written * 1_000_000L / fps
                muxer.writeSampleData(state.track, buffer, info)
                state.written++
            }
            encoder.releaseOutputBuffer(index, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    fun render(
        composer: FrameComposer,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        output: File,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean
    ): Result {
        val totalFrames = ((composer.totalMs * fps + 999L) / 1000L).toInt().coerceAtLeast(1)
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: Surface? = null
        val state = MuxState()
        var success = false
        try {
            if (output.exists()) output.delete()

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = codec
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            inputSurface = surface
            codec.start()

            val writer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            val info = MediaCodec.BufferInfo()

            for (frame in 0 until totalFrames) {
                if (isCancelled()) {
                    return Result(false, true, null)
                }
                val timeMs = frame * 1000L / fps
                val canvas = surface.lockCanvas(null)
                try {
                    composer.draw(canvas, timeMs)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                drain(codec, writer, state, info, fps, false)
                if (frame % 12 == 0) {
                    onProgress((frame * 100L / totalFrames).toInt().coerceIn(0, 99))
                }
            }

            codec.signalEndOfInputStream()
            drain(codec, writer, state, info, fps, true)
            onProgress(100)

            if (composer.failedScenes.isNotEmpty()) {
                val list = composer.failedScenes.take(8).joinToString(", ")
                return Result(
                    false, false,
                    "These pictures (scene numbers) could not be read, so the video was not saved: $list"
                )
            }
            if (state.written != totalFrames.toLong()) {
                return Result(
                    false, false,
                    "The video encoder dropped frames (${state.written} of $totalFrames written). " +
                        "The video would run too short, so it was not saved. Try a lower resolution or frame rate."
                )
            }
            success = true
            return Result(true, false, null)
        } catch (e: Throwable) {
            return Result(false, false, "Video render failed:\n" + errorText(e))
        } finally {
            try {
                inputSurface?.release()
            } catch (e: Exception) {
                // ignore
            }
            try {
                encoder?.stop()
            } catch (e: Exception) {
                // ignore
            }
            try {
                encoder?.release()
            } catch (e: Exception) {
                // ignore
            }
            try {
                if (state.started) muxer?.stop()
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
