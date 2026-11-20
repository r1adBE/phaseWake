package com.phasewake.app.wear.wake

/**
 * Gemessene Epochen in Stichproben für [OnsetRules.find] und [OnsetRules.settlingSampled]: [motions] je Epoche,
 * [measured] ob in einer Stichprobe, ruhig unter [threshold].
 */
internal class SampledStretch(val motions: List<Double?>, val measured: List<Boolean>, val threshold: Double) {
    fun isMeasured(index: Int): Boolean = index in motions.indices && measured.getOrElse(index) { false }
    fun quiet(index: Int): Boolean = motions[index].let { it != null && it < threshold }
    val indices = motions.indices.filter(::isMeasured)

    /** Bestätigt: ruhige Epoche [end], mindestens [OnsetRules.SAMPLED_REQUIRED_EPOCHS] ab [start]. */
    fun confirmed(start: Int, end: Int): Boolean = quiet(end) && end - start + 1 >= OnsetRules.SAMPLED_REQUIRED_EPOCHS

    /**
     * Strecke ab der gemessenen Epoche `indices[position]`: Ende bei der ersten Epoche, die sie bestätigt
     * ([confirmed]), sonst bei der letzten gemessenen; `null`, wenn sie nicht ruhig beginnt oder vorher bricht.
     */
    fun stretchEnd(position: Int): Int? {
        val start = indices[position]
        if (!quiet(start)) return null
        var last = start
        var twitches = 0
        for (index in indices.subList(position + 1, indices.size)) {
            if (index - last - 1 > OnsetRules.MAX_GAP_EPOCHS) return null
            last = index
            if (quiet(index)) {
                if (confirmed(start, index)) return index
                continue
            }
            twitches++
            val neighbourMoved = (index - 1 >= start && isMeasured(index - 1) && !quiet(index - 1)) ||
                (isMeasured(index + 1) && !quiet(index + 1))
            if (motions[index] == null || neighbourMoved || twitches > OnsetRules.SAMPLED_MAX_TWITCHES) return null
        }
        return last
    }
}
