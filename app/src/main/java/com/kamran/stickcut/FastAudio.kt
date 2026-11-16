package com.kamran.stickcut

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

// Fast path: when there are no fades and the voiceover is one AAC file,
// the sound goes into the MP4 as it is (no re-encoding, so it is quick).
object FastAudio {

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    fun canCopyDirectly(
        context: Context,
        audios: List<ImportedAudio>,
        fades: SceneFadeState,
        totalMs: Long
    ): Boolean {
        if (audios.size != 1) return false
        val effective = SceneFadeEngine.effective(fades, totalMs)
        if (effective.first > 0L || effective.second > 0L) return false
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, audios[0].uri, null)
            var found = false
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                if (mime == "audio/mp4a-latm") found = true
            }
            found
        } catch (e: Throwable) {
            false
        } finally {
            try {
                extractor.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    // Returns null on success, or an error text.
    fun merge(context: Context, video: File, audioUri: Uri, output: File): String? {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        var success = false
        try {
            if (output.exists()) output.delete()
            videoExtractor.setDataSource(video.absolutePath)
            audioExtractor.setDataSource(context, audioUri, null)
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
