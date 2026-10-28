package ch.r1ad.phasewake.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeartRateAccumulatorTest {

    @Test
    fun meanOfValidValues() {
        val acc = HeartRateAccumulator()
        listOf(50.0, 54.0, 0.0, -3.0, 300.0, Double.NaN).forEach(acc::add)
        assertEquals(52.0, acc.closeEpoch())
    }

    @Test
    fun emptyEpochIsNullAndResets() {
        val acc = HeartRateAccumulator()
        acc.add(60.0)
        acc.closeEpoch()
        assertNull(acc.closeEpoch())
    }
}
