package pikto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BatchStrategyTest {

    @Test
    fun `batches grow by the factor up to the ceiling`() {
        val strategy = BatchStrategy(firstBatchSize = 10, growthFactor = 3, maxBatchSize = 100)

        assertEquals(30, strategy.nextSize(10))
        assertEquals(90, strategy.nextSize(30))
        assertEquals(100, strategy.nextSize(90))
        assertEquals(100, strategy.nextSize(100))
    }

    /** The single-batch preset multiplies Int.MAX_VALUE, which would wrap round to negative. */
    @Test
    fun `growth past Int MAX_VALUE stays at the ceiling`() {
        assertEquals(Int.MAX_VALUE, BatchStrategy.SingleBatch.nextSize(Int.MAX_VALUE))
    }

    @Test
    fun `a ceiling below the first batch is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            BatchStrategy(firstBatchSize = 100, maxBatchSize = 10)
        }
    }

    @Test
    fun `an empty first batch is rejected`() {
        assertFailsWith<IllegalArgumentException> { BatchStrategy(firstBatchSize = 0) }
    }
}
