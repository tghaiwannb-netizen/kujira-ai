package app.kujira.android.domain

import app.kujira.android.data.db.MessageUsageRow
import app.kujira.android.data.model.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class UsageAggregatorTest {

    @Test
    fun `aggregates actual cost estimates and current month`() {
        val now = System.currentTimeMillis()
        val lastMonth = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.MONTH, -1)
            set(Calendar.DAY_OF_MONTH, 15)
        }.timeInMillis
        val rows = listOf(
            MessageUsageRow(
                providerId = "opencode-go",
                modelId = "deepseek-v4.1-flash",
                usage = Usage(
                    promptTokens = 1000,
                    completionTokens = 500,
                    totalTokens = 1500,
                    cacheReadTokens = 800,
                    costUsd = 0.01
                ),
                createdAt = now
            ),
            MessageUsageRow(
                providerId = "opencode-go",
                modelId = "deepseek-v4.1-flash",
                usage = Usage(promptTokens = 2000, completionTokens = 1000, totalTokens = 3000),
                createdAt = now
            ),
            MessageUsageRow(
                providerId = "deepseek",
                modelId = "deepseek-chat",
                usage = Usage(promptTokens = 100, completionTokens = 50, totalTokens = 150, costUsd = 0.001),
                createdAt = lastMonth
            )
        )
        val prices = mapOf(
            UsageAggregator.priceKey("opencode-go", "deepseek-v4.1-flash") to
                ModelPrice(inputPerMillion = 1.0, outputPerMillion = 2.0)
        )

        val report = UsageAggregator.aggregate(rows, prices, now)

        assertEquals(3, report.allTime.calls)
        assertEquals(4650, report.allTime.totalTokens)
        assertEquals(2, report.thisMonth.calls)
        assertEquals(0.011, report.allTime.actualCostUsd, 0.000001)
        assertEquals(0.004, report.allTime.estimatedCostUsd, 0.000001)
        assertEquals(0.015, report.allTime.costUsd, 0.000001)
        assertEquals(1, report.allTime.estimatedCalls)
        assertTrue(report.allTime.hasEstimate)
        assertEquals(800, report.allTime.cacheReadTokens)
        assertEquals(800f / 3900f, report.allTime.cacheHitRate!!, 0.0001f)

        val openCode = report.byProvider.first { it.providerId == "opencode-go" }
        assertEquals(2, openCode.calls)
        assertEquals(0.014, openCode.costUsd, 0.000001)
        assertTrue(openCode.hasEstimate)
        assertEquals("deepseek-chat", report.byModel.last().modelId)
    }

    @Test
    fun `unpriced providers still count tokens`() {
        val report = UsageAggregator.aggregate(
            rows = listOf(
                MessageUsageRow(
                    providerId = "p",
                    modelId = "m",
                    usage = Usage(10, 5, 15),
                    createdAt = System.currentTimeMillis()
                )
            )
        )
        assertEquals(15, report.allTime.totalTokens)
        assertFalse(report.allTime.hasCost)
        assertEquals(0.0, report.allTime.costUsd, 0.000001)
    }
}
