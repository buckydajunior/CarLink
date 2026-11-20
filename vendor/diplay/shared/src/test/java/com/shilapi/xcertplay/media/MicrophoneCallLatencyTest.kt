package com.shilapi.xcertplay.media

import android.media.AudioRecord
import android.media.AudioTimestamp
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class MicrophoneCallLatencyTest {
    @Test fun telephonyReadsOneNegotiatedFrameWhileSiriKeepsItsExistingBatch() {
        for ((type, rate) in listOf("telephony" to 8_000, "telephony" to 16_000,
            "telephony" to 48_000, "speechrecognition" to 16_000)) {
            val config = config(type, rate)
            val read = CountDownLatch(1)
            val requested = AtomicInteger()
            val allocated = AtomicInteger()
            val diagnostics = CopyOnWriteArrayList<String>()
            val recorder = object : Recorder() {
                override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
                    requested.set(size)
                    read.countDown()
                    Thread.sleep(5)
                    return 0
                }
            }
            val uplink = MicrophoneUplink(config, onDiagnostic = diagnostics::add,
                recorderFactory = MicrophoneRecorderFactory { _, _, _, buffer -> allocated.set(buffer); recorder })
            try {
                assertTrue(uplink.start())
                assertTrue(read.await(5, TimeUnit.SECONDS))
                assertEquals(if (type == "telephony") config.frameBytes else 2048, requested.get())
                assertTrue("Keep the recorder's existing safety capacity", allocated.get() >= config.frameBytes * 4)
                if (type == "telephony") {
                    val line = diagnostics.single { it.startsWith("Microphone: call processing ") }
                    assertTrue(line, line.contains("nativeEcho=off"))
                    assertTrue(line, line.contains("platformAec=not-attached platformNs=not-attached"))
                    assertTrue(line, line.contains("readBytes=${config.frameBytes}"))
                } else assertFalse(diagnostics.any { it.startsWith("Microphone: call processing ") })
            } finally { uplink.close() }
        }
    }

    @Test fun sameRecorderReadErrorRestartsWithAFreshCaptureTimestampEpoch() = recoversClock(AudioRecord.ERROR_INVALID_OPERATION)

    @Test fun sameRecorderNoAudioRestartAlsoResetsItsCaptureTimestampEpoch() = recoversClock(0)

    private fun recoversClock(firstFailure: Int) {
        val config = config("telephony", 16_000)
        val captured = CopyOnWriteArrayList<Int>()
        val processed = CountDownLatch(13)
        val recorder = object : Recorder() {
            private var epochFrames = 0
            override fun startRecording() { super.startRecording(); epochFrames = 0 }
            override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
                Thread.sleep(2)
                if (starts.get() == 1 && epochFrames == 1) return firstFailure
                if (epochFrames >= 12) return 0
                epochFrames++
                buffer.fill(0, offset, offset + config.frameBytes)
                return config.frameBytes
            }
            override fun timestamp(into: AudioTimestamp): Boolean {
                // Android resets framePosition on every stop/start, including this same object.
                into.framePosition = epochFrames * 320L
                val beforeThisEpoch = if (starts.get() == 1) 0L else 320L
                into.nanoTime = 1_000_000_000L + (beforeThisEpoch + into.framePosition) * 1_000_000_000L / 16_000
                return true
            }
        }
        val reference = EchoReference(16_000).also {
            val ramp = ByteArray(64_000)
            for (sample in 0 until 32_000) {
                ramp[sample * 2] = (sample + 1).toByte()
                ramp[sample * 2 + 1] = ((sample + 1) shr 8).toByte()
            }
            it.append(ramp, 0, ramp.size, 1, pendingFrames = 32_000, nowNs = 1_000_000_000)
        }
        val canceller = object : CallEchoCanceller {
            override val frameSamples = 320
            override fun process(frame: ByteArray, reference: ShortArray): Boolean {
                captured += reference.first().toInt()
                processed.countDown()
                return true
            }
            override fun close() = Unit
        }
        val uplink = MicrophoneUplink(config, echoReference = reference,
            echoCancellerFactory = { _, _, _ -> canceller }, noAudioRestartMillis = 0,
            recorderFactory = MicrophoneRecorderFactory { _, _, _, _ -> recorder })
        try {
            assertTrue(uplink.start())
            assertTrue("The same recorder must recover and process later call frames", processed.await(5, TimeUnit.SECONDS))
        } finally { uplink.close() }
        assertTrue(recorder.starts.get() >= 2)
        // With the 30ms reference lead, the first 20ms capture frame begins at ramp sample480.
        // A stale pre-restart samplesRead count would make the reference jump into the future
        // once EchoReference's clock steering settles, despite capture remaining contiguous.
        assertEquals(List(13) { 481 + it * 320 }, captured.take(13))
    }

    private open class Recorder : MicrophoneRecorder {
        val starts = AtomicInteger()
        private var recording = false
        override val state = AudioRecord.STATE_INITIALIZED
        override val recordingState get() = if (recording) AudioRecord.RECORDSTATE_RECORDING else AudioRecord.RECORDSTATE_STOPPED
        override val audioSessionId = 1
        override fun routedDeviceType(): Int? = null
        override fun startRecording() { starts.incrementAndGet(); recording = true }
        override fun stop() { recording = false }
        override fun read(buffer: ByteArray, offset: Int, size: Int) = 0
        override fun timestamp(into: AudioTimestamp) = false
        override fun release() { recording = false }
    }

    private fun config(type: String, rate: Int) = MicrophoneConfig(type, rate, 1, 100, 20,
        InetAddress.getLoopbackAddress(), 9, ByteArray(32))
}
