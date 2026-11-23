package com.carlink.core.connection
import com.carlink.core.settings.*
import org.junit.Assert.*
import org.junit.Test
class CarPlaySettingsTest {
    @Test fun customSizeAndPictureKeepTheDriversExactEngineValues() {
        val mapped = CarPlaySettingsPolicy.map(UnifiedSettings(interfaceSize = InterfaceSize.CUSTOM, picture = Picture.CUSTOM))
        assertNull(mapped.widthPhysicalMm); assertNull(mapped.displayScalePercent); assertNull(mapped.fps)
        assertNull(mapped.hevc); assertNull(mapped.directVideoOutput)
    }
    @Test fun largerPresetsReportASmallerPhysicalWidthToTheIphone() {
        assertTrue(CarPlaySettingsPolicy.widthMm(InterfaceSize.LARGE)!! < CarPlaySettingsPolicy.widthMm(InterfaceSize.MEDIUM)!!)
        assertTrue(CarPlaySettingsPolicy.widthMm(InterfaceSize.MEDIUM)!! < CarPlaySettingsPolicy.widthMm(InterfaceSize.SMALL)!!)
    }
    @Test fun automaticPictureUsesDecoderPolicyAndWiredAudioKeepsItsOriginalDelays() {
        val choice = PictureChoice(75, 30, false, "test", true)
        val mapped = CarPlaySettingsPolicy.map(UnifiedSettings(), choice)
        assertEquals(75, mapped.displayScalePercent); assertEquals(false, mapped.hevc); assertEquals(true, mapped.directVideoOutput)
        assertEquals(100, CarPlaySettingsPolicy.map(UnifiedSettings(audioDelay = AudioDelay.LOW)).audioBufferMillis)
        assertEquals(300, mapped.audioBufferMillis)
        assertEquals(500, CarPlaySettingsPolicy.map(UnifiedSettings(audioDelay = AudioDelay.HIGH)).audioBufferMillis)
    }
}
