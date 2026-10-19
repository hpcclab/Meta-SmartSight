package edu.unt.smartsight.metaglassestest

internal object CaptureSettings {
    const val PREFS = "smartsight"

    // Kept so a rate saved before video streaming still applies. The value is how often
    // YOLO runs, not how often the glasses send frames.
    const val KEY_RATE = "images_per_second"
    const val KEY_GAP = "announce_gap_seconds"

    const val MIN_DETECTIONS_PER_SECOND = 0.2f
    const val MAX_DETECTIONS_PER_SECOND = 2f
    const val DEFAULT_DETECTIONS_PER_SECOND = 1f

    const val MIN_GAP_SECONDS = 1
    const val MAX_GAP_SECONDS = 30
    const val DEFAULT_GAP_SECONDS = 5

    fun intervalMs(detectionsPerSecond: Float): Long {
        val rate = detectionsPerSecond.coerceIn(MIN_DETECTIONS_PER_SECOND, MAX_DETECTIONS_PER_SECOND)
        return (1000f / rate).toLong().coerceAtLeast(1L)
    }
}
