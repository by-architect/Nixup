package dev.nixup.manager.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import java.io.File
import java.io.InputStream

/**
 * Plays raw sound from its input with Android's audio, as music: what services.snapclient's snapclient writes (its
 * file player), since Android has no ALSA, PulseAudio or PipeWire for it. It plays through the speaker, headphones or
 * Bluetooth, as any app's music does, at Android's media volume. Run as root through app_process, in the unit (see
 * nix/snapcast.nix); it ends when its input does, or when Android's audio stops taking it.
 */
object AudioOut {
    /**
     * Plays [input], in [format] (rate:bits:channels, as snapclient's --sampleformat), and writes the time Android's
     * audio takes, in milliseconds, to [latencyFile] once it plays, for snapclient's --latency.
     */
    fun play(format: String, latencyFile: File?, input: InputStream): Int {
        val parts = format.split(':').map { it.toIntOrNull() }
        val rate = parts.getOrNull(0)?.takeIf { it in 8000..192000 }
        val bits = parts.getOrNull(1)
        val channels = parts.getOrNull(2)
        if (rate == null || bits == null || channels == null) throw Stop("audio-out: the format is rate:bits:channels, like 48000:16:2, not $format")
        val encoding = when (bits) {
            16 -> AudioFormat.ENCODING_PCM_16BIT
            24 -> if (Build.VERSION.SDK_INT >= 31) AudioFormat.ENCODING_PCM_24BIT_PACKED else throw Stop("audio-out: 24 bits need Android 12 or newer")
            32 -> if (Build.VERSION.SDK_INT >= 31) AudioFormat.ENCODING_PCM_32BIT else throw Stop("audio-out: 32 bits need Android 12 or newer")
            else -> throw Stop("audio-out: $bits bits: 16, 24 or 32")
        }
        val mask = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> throw Stop("audio-out: $channels channels: 1 or 2")
        }
        val frame = bits / 8 * channels
        val least = AudioTrack.getMinBufferSize(rate, mask, encoding)
        if (least <= 0) throw Stop("audio-out: Android's audio cannot play $format")
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(encoding).setChannelMask(mask).build())
            // About 100 ms: room for a late write without a gap, and not much more to wait for.
            .setBufferSizeInBytes(maxOf(least, rate / 10 * frame))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw Stop("audio-out: Android gave no audio output", 1)
        }
        try {
            track.play()
            val latency = latency(track, rate)
            latencyFile?.writeText("$latency\n")
            Out.line("audio-out: playing $format through Android's audio, which takes about $latency ms")
            // 10 ms at a time, whole frames only: a frame cut in two would swap the channels from then on.
            val buffer = ByteArray(frame * maxOf(rate / 100, 1))
            var kept = 0
            while (true) {
                val read = input.read(buffer, kept, buffer.size - kept)
                if (read < 0) break
                val have = kept + read
                val whole = have - have % frame
                var done = 0
                while (done < whole) {
                    val written = track.write(buffer, done, whole - done)
                    if (written < 0) throw Stop("audio-out: Android's audio stopped taking sound ($written)", 1)
                    done += written
                }
                kept = have - whole
                if (kept > 0) System.arraycopy(buffer, whole, buffer, 0, kept)
            }
            Out.line("audio-out: the sound ended")
        } finally {
            runCatching { track.stop() }
            track.release()
        }
        return 0
    }

    /**
     * How long a sample takes from here to the speaker: what Android says (AudioTrack's hidden getLatency, the
     * output's latency with the track's buffer), else the track's buffer alone.
     */
    private fun latency(track: AudioTrack, rate: Int): Int =
        runCatching { AudioTrack::class.java.getMethod("getLatency").invoke(track) as Int }.getOrNull()?.takeIf { it > 0 }
            ?: (track.bufferSizeInFrames * 1000L / rate).toInt()
}
