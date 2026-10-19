package edu.unt.smartsight.metaglassestest

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Reads a YOLO detection tensor.
 *
 * Raw Ultralytics exports are `[features, anchors]` with features laid out as
 * `cx, cy, w, h, class scores`. End-to-end exports are `[detections, 6]` as
 * `x1, y1, x2, y2, confidence, class`.
 */
internal object YoloPostprocess {
    fun labels(
        flat: FloatArray,
        dim1: Int,
        dim2: Int,
        classNames: List<String>,
        confidenceThreshold: Float,
    ): Set<String> {
        if (dim1 <= 0 || dim2 <= 0 || flat.size < dim1 * dim2) {
            return emptySet()
        }
        return if (dim2 in END_TO_END_ROW && dim1 > dim2) {
            endToEndLabels(flat, dim1, dim2, classNames, confidenceThreshold)
        } else {
            rawLabels(flat, dim1, dim2, classNames, confidenceThreshold)
        }
    }

    private fun endToEndLabels(
        flat: FloatArray,
        rows: Int,
        rowLength: Int,
        classNames: List<String>,
        confidenceThreshold: Float,
    ): Set<String> {
        val found = LinkedHashSet<String>()
        for (row in 0 until rows) {
            val base = row * rowLength
            val confidence = probability(flat[base + 4])
            if (confidence < confidenceThreshold) {
                continue
            }
            val classId = flat[base + 5].roundToInt()
            val name = classNames.getOrNull(classId) ?: continue
            found.add(name)
        }
        return found
    }

    private fun rawLabels(
        flat: FloatArray,
        features: Int,
        anchors: Int,
        classNames: List<String>,
        confidenceThreshold: Float,
    ): Set<String> {
        val classCount = features - BOX_FIELDS
        if (classCount <= 0) {
            return emptySet()
        }
        val found = LinkedHashSet<String>()
        for (anchor in 0 until anchors) {
            var bestScore = confidenceThreshold
            var bestClass = -1
            for (classIndex in 0 until classCount) {
                val score = probability(flat[(BOX_FIELDS + classIndex) * anchors + anchor])
                if (score >= bestScore) {
                    bestScore = score
                    bestClass = classIndex
                }
            }
            if (bestClass >= 0) {
                classNames.getOrNull(bestClass)?.let(found::add)
            }
        }
        return found
    }

    private fun probability(score: Float): Float {
        if (score in 0f..1f) {
            return score
        }
        return 1f / (1f + exp(-score))
    }

    private const val BOX_FIELDS = 4
    private val END_TO_END_ROW = 6..7
}
