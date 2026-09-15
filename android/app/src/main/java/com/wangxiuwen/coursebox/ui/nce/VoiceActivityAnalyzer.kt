package com.wangxiuwen.coursebox.ui.nce

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.wangxiuwen.coursebox.core.cx.CxDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import kotlin.math.ceil

@Serializable
data class SpeechSegment(val startMs: Long, val endMs: Long)

enum class SentenceAnalysisState { IDLE, ANALYZING, READY, FAILED }

/**
 * Fully-offline speech segmentation for course audio.
 *
 * Text is deliberately not used here: imported lesson text is sometimes a
 * different edition from the recording. Silero VAD supplies speech
 * probabilities, then [VadPostProcessor] turns pauses into replayable chunks.
 * Results are cached by media URI so analysis happens only once per lesson.
 */
class VoiceActivityAnalyzer(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheDir = File(context.filesDir, "sentence_boundaries/v2")

    suspend fun analyze(mediaPath: String): List<SpeechSegment> = withContext(Dispatchers.Default) {
        val checkActive = { ensureActive() }
        val cache = File(cacheDir, cacheKey(mediaPath) + ".json")
        runCatching {
            if (cache.isFile) json.decodeFromString<List<SpeechSegment>>(cache.readText()) else null
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return@withContext it }

        val analysis = decodeAndInfer(mediaPath, checkActive)
        if (analysis.probabilities.isEmpty()) return@withContext emptyList()
        val segments = VadPostProcessor.toSegments(
            probabilities = analysis.probabilities,
            audioDurationMs = analysis.durationMs,
        )
        if (segments.isNotEmpty()) {
            cacheDir.mkdirs()
            cache.writeText(json.encodeToString(segments))
        }
        segments
    }

    private class Analysis(val probabilities: FloatArray, val durationMs: Long)

    /**
     * Decode, resample and run the VAD in one streaming pass.
     *
     * Holding the whole track in memory is what this avoids: at
     * [MAX_ANALYSIS_SECONDS] and 44.1 kHz the mono float array alone is
     * ~317 MB, before the doubling inside [FloatCollector] and the copy out
     * of it — hopeless against the 128 MB heap cap on a 32-bit learning
     * tablet, and uncomfortable even on a 64-bit phone. Silero is already
     * frame-by-frame (512 samples, carrying state), so samples can be fed
     * through as they are decoded and dropped immediately after. What
     * survives the pass is one float of probability per 32 ms of audio.
     */
    private fun decodeAndInfer(mediaPath: String, checkActive: () -> Unit): Analysis {
        val extractor = MediaExtractor()
        var pfd: ParcelFileDescriptor? = null
        try {
            val uri = Uri.parse(mediaPath)
            when (uri.scheme) {
                CxDataSource.SCHEME -> {
                    val (file, entry) = CxDataSource.resolveEntry(uri)
                    pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    extractor.setDataSource(pfd.fileDescriptor, entry.dataOffset, entry.size)
                }
                "http", "https" -> extractor.setDataSource(mediaPath, emptyMap())
                "file" -> extractor.setDataSource(uri.path ?: error("音频路径无效"))
                else -> extractor.setDataSource(mediaPath)
            }

            var track = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    inputFormat = format
                    break
                }
            }
            require(track >= 0 && inputFormat != null) { "媒体中没有音轨" }
            extractor.selectTrack(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("音轨格式缺失")
            inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()
                return VadRunner(checkActive).use { vad ->
                    drainDecoder(codec, extractor, inputFormat, checkActive, vad)
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
            pfd?.close()
        }
    }

    private fun drainDecoder(
        codec: MediaCodec,
        extractor: MediaExtractor,
        initialFormat: MediaFormat,
        checkActive: () -> Unit,
        vad: VadRunner,
    ): Analysis {
        var sampleRate = initialFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, MODEL_SAMPLE_RATE)
        var channels = initialFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        val out = Resampler(sampleRate, MODEL_SAMPLE_RATE) { vad.push(it) }
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false

        while (!outputEnded && out.inputCount < sampleRate.toLong() * MAX_ANALYSIS_SECONDS) {
            checkActive()
            if (!inputEnded) {
                val index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index) ?: error("解码输入缓冲区缺失")
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        codec.queueInputBuffer(index, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                        extractor.advance()
                    }
                }
            }

            when (val index = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    sampleRate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                    channels = f.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                    pcmEncoding = f.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    // Arrives before any output buffer in practice, so the
                    // ratio is settled before a single sample is pushed.
                    out.sourceRate = sampleRate
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (index >= 0) {
                    codec.getOutputBuffer(index)?.let { buffer ->
                        if (info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            appendPcm(buffer.slice().order(ByteOrder.LITTLE_ENDIAN), channels, pcmEncoding, out)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                }
            }
        }
        out.finish()
        val probabilities = vad.finish()
        return Analysis(
            probabilities = probabilities,
            durationMs = out.outputCount * 1000L / MODEL_SAMPLE_RATE,
        )
    }

    private fun appendPcm(
        buffer: ByteBuffer,
        channels: Int,
        encoding: Int,
        out: Resampler,
    ) {
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = buffer.asFloatBuffer()
            while (floats.remaining() >= channels) {
                var sum = 0f
                repeat(channels) { sum += floats.get() }
                out.push((sum / channels).coerceIn(-1f, 1f))
            }
        } else {
            val shorts = buffer.asShortBuffer()
            while (shorts.remaining() >= channels) {
                var sum = 0f
                repeat(channels) { sum += shorts.get() / 32768f }
                out.push((sum / channels).coerceIn(-1f, 1f))
            }
        }
    }

    /**
     * Silero VAD driven one frame at a time. Owns the ORT session so the
     * model is loaded once per analysis, and keeps the recurrent state and
     * the 64-sample context between frames — which is what lets samples be
     * consumed as they decode instead of after the whole track is in memory.
     */
    private inner class VadRunner(private val checkActive: () -> Unit) : AutoCloseable {
        private val env = OrtEnvironment.getEnvironment()
        private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
        private val session = context.assets.open(MODEL_ASSET)
            .use { model -> env.createSession(model.readBytes(), options) }
        private val srTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf(MODEL_SAMPLE_RATE.toLong())),
            longArrayOf(),
        )

        private var state = FloatArray(2 * 1 * 128)
        private var contextSamples = FloatArray(CONTEXT_SAMPLES)
        private val frame = FloatArray(FRAME_SAMPLES)
        private var fill = 0
        private var framesRun = 0
        private val probabilities = FloatCollector(1024)

        fun push(sample: Float) {
            frame[fill++] = sample
            if (fill == FRAME_SAMPLES) runFrame()
        }

        /**
         * Zero-pad and run whatever is left, so a track that does not divide
         * evenly still reports a probability for its final partial frame —
         * the ceil() the array version used to do.
         */
        fun finish(): FloatArray {
            if (fill > 0) {
                java.util.Arrays.fill(frame, fill, FRAME_SAMPLES, 0f)
                runFrame()
            }
            return probabilities.toArray()
        }

        private fun runFrame() {
            if (framesRun++ % 32 == 0) checkActive()
            // Silero's public ONNX graph expects the previous 64 samples
            // prepended to each 512-sample frame. Its Python wrapper performs
            // this concatenation; mobile callers must do it explicitly.
            val modelInput = FloatArray(CONTEXT_SAMPLES + FRAME_SAMPLES)
            contextSamples.copyInto(modelInput, 0)
            frame.copyInto(modelInput, CONTEXT_SAMPLES)
            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(modelInput),
                longArrayOf(1, modelInput.size.toLong()),
            ).use { input ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { stateTensor ->
                    session.run(mapOf("input" to input, "state" to stateTensor, "sr" to srTensor)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val output = result[0].value as Array<FloatArray>
                        probabilities.add(output[0][0])
                        @Suppress("UNCHECKED_CAST")
                        val next = result[1].value as Array<Array<FloatArray>>
                        state = FloatArray(256).also { flattened ->
                            var p = 0
                            next.forEach { batch -> batch.forEach { row -> row.forEach { flattened[p++] = it } } }
                        }
                        contextSamples = frame.copyOfRange(
                            FRAME_SAMPLES - CONTEXT_SAMPLES,
                            FRAME_SAMPLES,
                        )
                    }
                }
            }
            fill = 0
        }

        override fun close() {
            runCatching { srTensor.close() }
            runCatching { session.close() }
            runCatching { options.close() }
        }
    }

    /**
     * Streaming linear resampler feeding [sink] one target-rate sample at a
     * time. Deliberately reproduces the array version's edge behaviour: the
     * trailing outputs repeat the final input sample instead of
     * interpolating past it, because that version clamped its right index.
     */
    private class Resampler(
        var sourceRate: Int,
        private val targetRate: Int,
        private val sink: (Float) -> Unit,
    ) {
        var inputCount = 0L
            private set
        var outputCount = 0L
            private set
        private var previous = 0f

        fun push(sample: Float) {
            // Outputs landing in [inputCount-1, inputCount) interpolate
            // between the previous sample and this one.
            if (inputCount > 0) {
                var position = outputCount.toDouble() * sourceRate / targetRate
                while (position < inputCount) {
                    val fraction = (position - (inputCount - 1)).toFloat()
                    sink(previous + (sample - previous) * fraction)
                    outputCount++
                    position = outputCount.toDouble() * sourceRate / targetRate
                }
            }
            previous = sample
            inputCount++
        }

        fun finish() {
            if (inputCount == 0L) return
            val total = inputCount * targetRate / sourceRate
            while (outputCount < total) {
                sink(previous)
                outputCount++
            }
        }
    }

    private fun cacheKey(mediaPath: String): String = MessageDigest.getInstance("SHA-256")
        .digest(mediaPath.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    private class FloatCollector(initialCapacity: Int) {
        private var values = FloatArray(initialCapacity.coerceAtLeast(1024))
        var size: Int = 0
            private set

        fun add(value: Float) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = value
        }

        fun toArray(): FloatArray = values.copyOf(size)
    }

    companion object {
        private const val MODEL_ASSET = "silero_vad.onnx"
        private const val MODEL_SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 512
        private const val CONTEXT_SAMPLES = 64
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val MAX_ANALYSIS_SECONDS = 30 * 60
    }
}

/** Pause-based post-processing kept Android-free so boundary behaviour can be unit-tested. */
internal object VadPostProcessor {
    private const val FRAME_MS = 32L
    private const val START_THRESHOLD = 0.50f
    private const val END_THRESHOLD = 0.35f
    // A short breath inside one sentence is commonly 200-500 ms. Requiring
    // 800 ms avoids turning every clause into a separate "sentence" button,
    // while the 10 s ceiling below still keeps uninterrupted narration easy
    // to replay.
    private const val MIN_SILENCE_MS = 800L
    private const val MIN_SPEECH_MS = 180L
    private const val SPEECH_PAD_MS = 100L
    private const val MAX_SEGMENT_MS = 10_000L

    fun toSegments(probabilities: FloatArray, audioDurationMs: Long): List<SpeechSegment> {
        if (probabilities.isEmpty() || audioDurationMs <= 0) return emptyList()
        val raw = mutableListOf<SpeechSegment>()
        var speechStart = -1
        var silenceStart = -1
        val requiredSilentFrames = ceil(MIN_SILENCE_MS.toDouble() / FRAME_MS).toInt()

        probabilities.forEachIndexed { index, probability ->
            if (speechStart < 0) {
                if (probability >= START_THRESHOLD) speechStart = index
                return@forEachIndexed
            }
            if (probability < END_THRESHOLD) {
                if (silenceStart < 0) silenceStart = index
                if (index - silenceStart + 1 >= requiredSilentFrames) {
                    appendSpeech(raw, speechStart, silenceStart, audioDurationMs)
                    speechStart = -1
                    silenceStart = -1
                }
            } else {
                silenceStart = -1
            }
        }
        if (speechStart >= 0) appendSpeech(raw, speechStart, probabilities.size, audioDurationMs)
        return raw.flatMap { splitLong(it, probabilities) }.fixOverlaps(audioDurationMs)
    }

    private fun appendSpeech(out: MutableList<SpeechSegment>, startFrame: Int, endFrame: Int, durationMs: Long) {
        val rawStart = startFrame * FRAME_MS
        val rawEnd = minOf(durationMs, endFrame * FRAME_MS)
        if (rawEnd - rawStart >= MIN_SPEECH_MS) {
            out += SpeechSegment(
                startMs = (rawStart - SPEECH_PAD_MS).coerceAtLeast(0L),
                endMs = (rawEnd + SPEECH_PAD_MS).coerceAtMost(durationMs),
            )
        }
    }

    private fun splitLong(segment: SpeechSegment, probabilities: FloatArray): List<SpeechSegment> {
        if (segment.endMs - segment.startMs <= MAX_SEGMENT_MS) return listOf(segment)
        val result = mutableListOf<SpeechSegment>()
        var start = segment.startMs
        while (segment.endMs - start > MAX_SEGMENT_MS) {
            val searchFrom = ((start + 5_000L) / FRAME_MS).toInt().coerceAtLeast(0)
            val searchTo = ((start + MAX_SEGMENT_MS) / FRAME_MS).toInt().coerceAtMost(probabilities.lastIndex)
            val splitFrame = (searchFrom..searchTo).minByOrNull { probabilities[it] } ?: searchTo
            val splitMs = (splitFrame * FRAME_MS).coerceAtLeast(start + 1_000L)
            result += SpeechSegment(start, splitMs)
            start = splitMs
        }
        result += SpeechSegment(start, segment.endMs)
        return result
    }

    private fun List<SpeechSegment>.fixOverlaps(durationMs: Long): List<SpeechSegment> {
        if (size < 2) return this
        val out = toMutableList()
        for (i in 0 until out.lastIndex) {
            if (out[i].endMs > out[i + 1].startMs) {
                val midpoint = (out[i].endMs + out[i + 1].startMs) / 2
                out[i] = out[i].copy(endMs = midpoint)
                out[i + 1] = out[i + 1].copy(startMs = midpoint)
            }
        }
        return out.map { it.copy(startMs = it.startMs.coerceAtLeast(0), endMs = it.endMs.coerceAtMost(durationMs)) }
            .filter { it.endMs > it.startMs }
    }
}
