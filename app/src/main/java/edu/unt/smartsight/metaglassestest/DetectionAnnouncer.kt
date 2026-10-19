package edu.unt.smartsight.metaglassestest

/**
 * Speaks a label when it was not seen within the last [gap][freshLabels] window.
 * A label that stays visible is recorded each time and is not spoken again.
 */
internal class DetectionAnnouncer {
    private val lastSeenMs = HashMap<String, Long>()

    fun freshLabels(detected: Set<String>, nowMs: Long, gapMs: Long): List<String> {
        val fresh = ArrayList<String>()
        for (label in detected) {
            val previous = lastSeenMs[label]
            if (previous == null || nowMs - previous >= gapMs) {
                fresh.add(label)
            }
            lastSeenMs[label] = nowMs
        }
        fresh.sort()
        return fresh
    }
}
