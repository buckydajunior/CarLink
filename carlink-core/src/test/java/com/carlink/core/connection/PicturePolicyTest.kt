package com.carlink.core.connection
import org.junit.Assert.*
import org.junit.Test
class PicturePolicyTest {
    private val strong = DeviceSignals(7600, false, 8, listOf("arm64-v8a"))
    private val weak = DeviceSignals(1900, false, 4, listOf("armeabi-v7a"))
    private fun caps(panel: VideoSize, maxPixels: Long, hardware: Boolean = true, fps: Int = 60, signals: DeviceSignals = strong, hevc: Boolean = false): DeviceCapabilities =
        DeviceCapabilities(panel, signals, DecoderInfo("vendor.avc", hardware,
            PicturePolicy.probeSizes(panel).filter { it.pixels <= maxPixels }.associateWith { fps }),
            if (hevc) DecoderInfo("vendor.hevc", true, emptyMap()) else null)
    @Test fun strongDecoderKeepsTheNativePictureAtSixtyFps() {
        val p = PicturePolicy.choose(caps(VideoSize(1920, 1080), Long.MAX_VALUE, hevc = true))
        assertEquals(100, p.carPlayScalePercent); assertEquals(60, p.carPlayFps); assertNull(p.carPlayHevc)
    }
    @Test fun noHardwareHevcNeverRequestsSoftwareHevc() {
        assertEquals(false, PicturePolicy.choose(caps(VideoSize(1280, 720), Long.MAX_VALUE)).carPlayHevc)
    }
    @Test fun limitedDecoderStepsDownOnlyAsFarAsNeeded() {
        assertEquals(75, PicturePolicy.choose(caps(VideoSize(1920, 720), 1280L * 720, fps = 30)).carPlayScalePercent)
    }
    @Test fun slowHardwareUsesDirectVideoAndThirtyFps() {
        val p = PicturePolicy.choose(caps(VideoSize(1920, 1080), Long.MAX_VALUE, signals = weak))
        assertEquals(75, p.carPlayScalePercent); assertEquals(30, p.carPlayFps); assertEquals(true, p.carPlayDirectVideo)
    }
    @Test fun softwareDecoderAndVerySmallMemoryKeepTheExistingPixelCaps() {
        val panel = VideoSize(1920, 1080)
        assertEquals(50, PicturePolicy.choose(caps(panel, Long.MAX_VALUE, hardware = false)).carPlayScalePercent)
        assertEquals(50, PicturePolicy.choose(caps(panel, Long.MAX_VALUE, signals = weak.copy(totalRamMb = 1000))).carPlayScalePercent)
    }
    @Test fun unknownCapabilitiesFallBackWithoutInventingSupport() {
        assertEquals(100, PicturePolicy.choose(DeviceCapabilities(VideoSize(1280, 720), strong, null, null)).carPlayScalePercent)
    }
    @Test fun probeSizesContainOnlyTheCarPlayCandidatePanels() {
        val panel = VideoSize(1920, 1080)
        assertEquals(setOf(panel, VideoSize(1440, 810), VideoSize(960, 540)), PicturePolicy.probeSizes(panel))
    }
}
