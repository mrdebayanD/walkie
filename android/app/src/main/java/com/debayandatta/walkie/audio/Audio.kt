package com.debayandatta.walkie.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder
import org.concentus.OpusSignal
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Opus mono 48 kHz, 20 ms frames, 16 kbps — same as the iPhone app. */
object OpusFormat {
    const val SAMPLE_RATE = 48_000
    const val FRAME = 960
    const val BITRATE = 16_000
}

/**
 * Microphone -> Opus packets (batches of 3 = 60 ms, fewer radio wake-ups),
 * and Opus packets -> speaker with a small jitter buffer.
 * Threads only run while talking or listening, so idle cost is zero.
 */
class Audio(private val context: Context) {
    var onPackets: ((List<ByteArray>) -> Unit)? = null
    var onLevel: ((Float) -> Unit)? = null
    @Volatile var speakerMuted = false

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var capturing = false
    private var captureThread: Thread? = null

    private val playQueue = LinkedBlockingQueue<ShortArray>()
    @Volatile private var playing = false
    private var playThread: Thread? = null
    private val decoderLock = Any()
    private var decoder: OpusDecoder? = null
    private var focus: AudioFocusRequest? = null
    @Volatile var onPlaybackIdle: (() -> Unit)? = null

    // ---- capture ----

    @SuppressLint("MissingPermission") // checked by the caller before talking
    fun startCapture() {
        if (capturing) return
        capturing = true
        captureThread = Thread({ captureLoop() }, "walkie-capture").apply { start() }
    }

    fun stopCapture() {
        capturing = false
        captureThread?.join(500)
        captureThread = null
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        // 48 kHz works on nearly every phone; fall back to 16 kHz and upsample.
        var rate = OpusFormat.SAMPLE_RATE
        var rec = openRecorder(rate)
        if (rec == null) { rate = 16_000; rec = openRecorder(rate) }
        if (rec == null) { capturing = false; return }
        val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId)?.apply { setEnabled(true) } }.getOrNull() else null
        val enc = OpusEncoder(OpusFormat.SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
            setBitrate(OpusFormat.BITRATE)
            setUseVBR(true)
            setComplexity(5) // good voice quality, light on CPU
            setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
        }
        val inFrame = OpusFormat.FRAME * rate / OpusFormat.SAMPLE_RATE
        val raw = ShortArray(inFrame)
        val frame = ShortArray(OpusFormat.FRAME)
        val out = ByteArray(1275)
        val batch = ArrayList<ByteArray>(3)
        try {
            rec.startRecording()
            while (capturing) {
                var got = 0
                while (got < inFrame && capturing) {
                    val n = rec.read(raw, got, inFrame - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < inFrame) break
                if (rate == OpusFormat.SAMPLE_RATE) raw.copyInto(frame) else upsample3(raw, frame)
                var peak = 0
                for (s in frame) peak = maxOf(peak, abs(s.toInt()))
                val len = enc.encode(frame, 0, OpusFormat.FRAME, out, 0, out.size)
                if (len > 0) batch.add(out.copyOf(len))
                if (batch.size == 3) {
                    onPackets?.invoke(ArrayList(batch))
                    batch.clear()
                    onLevel?.invoke((peak / 16384f).coerceAtMost(1f))
                }
            }
        } catch (_: Throwable) {
        } finally {
            if (batch.isNotEmpty()) onPackets?.invoke(ArrayList(batch))
            runCatching { rec.stop() }
            ns?.release()
            rec.release()
            onLevel?.invoke(0f)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(rate: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val r = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5 * 2))
        }.getOrNull() ?: return null
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return null }
        return r
    }

    /** 16 kHz -> 48 kHz by linear interpolation (fallback only). */
    private fun upsample3(src: ShortArray, dst: ShortArray) {
        for (i in src.indices) {
            val a = src[i].toInt()
            val b = if (i + 1 < src.size) src[i + 1].toInt() else a
            dst[i * 3] = a.toShort()
            dst[i * 3 + 1] = ((a * 2 + b) / 3).toShort()
            dst[i * 3 + 2] = ((a + b * 2) / 3).toShort()
        }
    }

    // ---- playback ----

    /** Decodes and queues live packets. Safe to call from any thread. */
    fun play(packets: List<ByteArray>) {
        if (speakerMuted) return
        val pcm = synchronized(decoderLock) {
            val d = decoder ?: OpusDecoder(OpusFormat.SAMPLE_RATE, 1).also { decoder = it }
            packets.mapNotNull { p ->
                val buf = ShortArray(5760)
                val n = runCatching { d.decode(p, 0, p.size, buf, 0, 5760, false) }.getOrDefault(0)
                if (n > 0) buf.copyOf(n) else null
            }
        }
        pcm.forEach { playQueue.offer(it) }
        ensurePlayer()
    }

    /** Fresh decoder state for a new transmission. */
    fun newStream() = synchronized(decoderLock) { decoder = OpusDecoder(OpusFormat.SAMPLE_RATE, 1) }

    fun stopPlayback() {
        playQueue.clear()
    }

    private fun ensurePlayer() {
        if (playing) return
        playing = true
        playThread = Thread({ playLoop() }, "walkie-play").apply { start() }
    }

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA) // loudspeaker / headphones, media volume
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val fmt = AudioFormat.Builder().setSampleRate(OpusFormat.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val min = AudioTrack.getMinBufferSize(OpusFormat.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(min, OpusFormat.FRAME * 2 * 6))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        requestFocus(attrs)
        try {
            // Jitter buffer: wait for ~60 ms before starting.
            val start = System.currentTimeMillis()
            while (playQueue.size < 3 && System.currentTimeMillis() - start < 150) Thread.sleep(5)
            track.play()
            while (true) {
                val buf = playQueue.poll(400, TimeUnit.MILLISECONDS) ?: break // idle: stop
                track.write(buf, 0, buf.size)
            }
            // Let the hardware drain.
            Thread.sleep(120)
        } catch (_: InterruptedException) {
        } finally {
            runCatching { track.stop() }
            track.release()
            abandonFocus()
            playing = false
            onPlaybackIdle?.invoke()
            if (playQueue.isNotEmpty()) ensurePlayer()
        }
    }

    /** Plays a whole clip file ("WKC1"); blocks until done. Call off the main thread. */
    fun playClipBlocking(file: ByteArray, progress: (Float) -> Unit) {
        val packets = parseClip(file)
        if (packets.isEmpty()) return
        val dec = OpusDecoder(OpusFormat.SAMPLE_RATE, 1)
        val buf = ShortArray(5760)
        val wasMuted = speakerMuted
        speakerMuted = false
        packets.forEachIndexed { i, p ->
            val n = runCatching { dec.decode(p, 0, p.size, buf, 0, 5760, false) }.getOrDefault(0)
            if (n > 0) playQueue.offer(buf.copyOf(n))
            ensurePlayer()
            progress((i + 1f) / packets.size)
            // Pace decoding to playback so progress is meaningful and memory stays small.
            while (playQueue.size > 10) Thread.sleep(10)
        }
        while (playQueue.isNotEmpty()) Thread.sleep(20)
        speakerMuted = wasMuted
    }

    private fun requestFocus(attrs: AudioAttributes) {
        val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        focus = f
        am.requestAudioFocus(f)
    }

    private fun abandonFocus() { focus?.let { am.abandonAudioFocusRequest(it) }; focus = null }

    companion object {
        fun parseClip(d: ByteArray): List<ByteArray> {
            if (d.size < 10 || String(d, 0, 4) != "WKC1") return emptyList()
            val out = ArrayList<ByteArray>()
            var i = 10
            while (i + 2 <= d.size) {
                val len = ((d[i].toInt() and 0xff) shl 8) or (d[i + 1].toInt() and 0xff)
                i += 2
                if (len == 0 || i + len > d.size) break
                out.add(d.copyOfRange(i, i + len))
                i += len
            }
            return out
        }
    }
}
