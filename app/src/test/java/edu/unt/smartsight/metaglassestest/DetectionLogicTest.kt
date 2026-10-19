package edu.unt.smartsight.metaglassestest

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionLogicTest {
    @Test
    fun defaultRateIsOneDetectionPerSecond() {
        assertEquals(1000L, CaptureSettings.intervalMs(1f))
    }

    @Test
    fun fasterRateShortensTheInterval() {
        assertEquals(500L, CaptureSettings.intervalMs(2f))
    }

    @Test
    fun rateIsClampedToTheSliderRange() {
        assertEquals(1000L / 2, CaptureSettings.intervalMs(9f))
        assertEquals(5000L, CaptureSettings.intervalMs(0.01f))
    }

    @Test
    fun labelIsSpokenOnFirstSightAndAfterTheGap() {
        val announcer = DetectionAnnouncer()

        assertEquals(listOf("person"), announcer.freshLabels(setOf("person"), nowMs = 0, gapMs = 5_000))
        assertTrue(announcer.freshLabels(setOf("person"), nowMs = 1_000, gapMs = 5_000).isEmpty())
        assertEquals(listOf("cup"), announcer.freshLabels(setOf("person", "cup"), nowMs = 2_000, gapMs = 5_000))
        assertEquals(listOf("person"), announcer.freshLabels(setOf("person"), nowMs = 7_000, gapMs = 5_000))
    }

    @Test
    fun absenceShorterThanTheGapDoesNotRepeat() {
        val announcer = DetectionAnnouncer()
        announcer.freshLabels(setOf("bottle"), nowMs = 0, gapMs = 5_000)

        assertTrue(announcer.freshLabels(setOf("bottle"), nowMs = 4_999, gapMs = 5_000).isEmpty())
        assertEquals(listOf("bottle"), announcer.freshLabels(setOf("bottle"), nowMs = 10_000, gapMs = 5_000))
    }

    @Test
    fun i420BlackAndWhiteUseVideoRange() {
        assertEquals(0xFF000000.toInt(), I420Frames.limitedRangeArgb(16, 128, 128))
        assertEquals(0xFFFFFFFF.toInt(), I420Frames.limitedRangeArgb(235, 128, 128))
    }

    @Test
    fun i420BufferPaintsEachPixel() {
        val buffer = ByteBuffer.wrap(
            byteArrayOf(
                16.toByte(),
                235.toByte(),
                16.toByte(),
                235.toByte(),
                128.toByte(),
                128.toByte(),
                128.toByte(),
                128.toByte(),
            ),
        )

        val pixels = I420Frames.argb(buffer, 2, 2)

        assertEquals(0xFF000000.toInt(), pixels!![0])
        assertEquals(0xFFFFFFFF.toInt(), pixels[1])
        assertEquals(0xFF000000.toInt(), pixels[2])
        assertEquals(0xFFFFFFFF.toInt(), pixels[3])
    }

    @Test
    fun shortI420BufferIsRejected() {
        assertNull(I420Frames.argb(ByteBuffer.wrap(ByteArray(4)), 2, 2))
    }

    @Test
    fun rawYoloTensorKeepsClassesAboveTheThreshold() {
        val anchors = 2
        val features = 6
        val flat = FloatArray(features * anchors)
        flat[0 * anchors + 0] = 10f
        flat[1 * anchors + 0] = 10f
        flat[2 * anchors + 0] = 20f
        flat[3 * anchors + 0] = 20f
        flat[4 * anchors + 0] = 0.9f
        flat[5 * anchors + 0] = 0.1f
        flat[4 * anchors + 1] = 0.2f
        flat[5 * anchors + 1] = 0.8f

        val labels = YoloPostprocess.labels(
            flat = flat,
            dim1 = features,
            dim2 = anchors,
            classNames = listOf("person", "cup"),
            confidenceThreshold = 0.45f,
        )

        assertEquals(setOf("person", "cup"), labels)
    }

    @Test
    fun endToEndTensorUsesConfidenceAndClassId() {
        val flat = FloatArray(8 * 6)
        flat[4] = 0.2f
        flat[6 + 4] = 0.95f
        flat[6 + 5] = 1f

        val labels = YoloPostprocess.labels(
            flat = flat,
            dim1 = 8,
            dim2 = 6,
            classNames = listOf("person", "cup"),
            confidenceThreshold = 0.45f,
        )

        assertEquals(setOf("cup"), labels)
    }
}
