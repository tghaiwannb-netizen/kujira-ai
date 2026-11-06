package app.kujira.android.domain

import app.kujira.android.data.db.MessageUsageRow
import app.kujira.android.data.model.ModelInfo
import app.kujira.android.data.model.Usage
import java.util.Calendar

/** Pricing used only when a provider did not report an actual cost. */
data class ModelPrice(
    val inputPerMillion: Double,
    val outputPerMillion: Double,
    val cacheReadPerMillion: Double? = null,
)

data class UsageBucket(
    val providerId: String? = null,
    val modelId: String? = null,
    val calls: Int = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val actualCostUsd: Double = 0.0,
    val estimatedCostUsd: Double = 0.0,
    val estimatedCalls: Int = 0,
    val pricedCalls: Int = 0,
    val allowanceUsd: Double = 0.0,
) {
    val costUsd: Double get() = actualCostUsd + estimatedCostUsd
    val hasCost: Boolean get() = pricedCalls > 0
    val hasEstimate: Boolean get() = estimatedCalls > 0
    val cacheHitRate: Float?
        get() {
            val input = promptTokens + cacheReadTokens
            return if (input <= 0) null else cacheReadTokens.toFloat() / input
        }
}

data class UsageReport(
    val allTime: UsageBucket = UsageBucket(),
    val thisMonth: UsageBucket = UsageBucket(),
    val byProvider: List<UsageBucket> = emptyList(),
    val byModel: List<UsageBucket> = emptyList(),
)

/**
 * Aggregates persisted per-message usage. Actual provider-reported cost wins;
 * when it is missing, [prices] are used to produce an explicitly-labelled
 * estimate.
 */
fun ModelInfo.toModelPrice(): ModelPrice? {
    val input = inputPricePerMillion
    val output = outputPricePerMillion
    if (input == null && output == null) return null
    return ModelPrice(
        inputPerMillion = input ?: 0.0,
        outputPerMillion = output ?: 0.0,
        cacheReadPerMillion = cacheReadPricePerMillion
    )
}

object UsageAggregator {

    fun aggregate(
        rows: List<MessageUsageRow>,
        prices: Map<String, ModelPrice> = emptyMap(),
        now: Long = System.currentTimeMillis(),
    ): UsageReport {
        if (rows.isEmpty()) return UsageReport()
        val monthStart = startOfMonth(now)
        val all = MutableBucket()
        val month = MutableBucket()
        val byProvider = mutableMapOf<String?, MutableBucket>()
        val byModel = mutableMapOf<Pair<String?, String?>, MutableBucket>()

        for (row in rows) {
            val price = prices[priceKey(row.providerId, row.modelId)]
                ?: prices[priceKey(null, row.modelId)]
            val cost = row.usage.costUsd ?: price?.let { estimateCost(row.usage, it) }
            val estimated = row.usage.costUsd == null && cost != null

            all.add(row.usage, cost, estimated)
            if (row.createdAt >= monthStart) month.add(row.usage, cost, estimated)
            byProvider.getOrPut(row.providerId) { MutableBucket() }.add(row.usage, cost, estimated)
            byModel.getOrPut(row.providerId to row.modelId) { MutableBucket() }
                .add(row.usage, cost, estimated)
        }

        return UsageReport(
            allTime = all.toBucket(),
            thisMonth = month.toBucket(),
            byProvider = byProvider
                .map { (providerId, bucket) -> bucket.toBucket(providerId = providerId) }
                .sortedWith(compareByDescending<UsageBucket> { it.costUsd }.thenByDescending { it.totalTokens }),
            byModel = byModel
                .map { (key, bucket) ->
                    bucket.toBucket(providerId = key.first, modelId = key.second)
                }
                .sortedWith(compareByDescending<UsageBucket> { it.costUsd }.thenByDescending { it.totalTokens }),
        )
    }

    fun priceKey(providerId: String?, modelId: String?): String =
        "${providerId.orEmpty()}\u0000${modelId.orEmpty()}"

    fun estimateCost(usage: Usage, price: ModelPrice): Double =
        usage.promptTokens / 1_000_000.0 * price.inputPerMillion +
            usage.completionTokens / 1_000_000.0 * price.outputPerMillion

    private fun startOfMonth(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private class MutableBucket {
        var calls = 0
        var prompt = 0
        var completion = 0
        var total = 0
        var cacheRead = 0
        var cacheWrite = 0
        var reasoning = 0
        var actualCost = 0.0
        var estimatedCost = 0.0
        var estimatedCalls = 0
        var pricedCalls = 0
        var allowance = 0.0

        fun add(usage: Usage, cost: Double?, estimated: Boolean) {
            calls++
            prompt += usage.promptTokens
            completion += usage.completionTokens
            total += usage.totalTokens
            cacheRead += usage.cacheReadTokens ?: 0
            cacheWrite += usage.cacheWriteTokens ?: 0
            reasoning += usage.reasoningTokens ?: 0
            allowance += usage.allowanceUsd ?: 0.0
            if (cost != null) {
                pricedCalls++
                if (estimated) {
                    estimatedCost += cost
                    estimatedCalls++
                } else {
                    actualCost += cost
                }
            }
        }

        fun toBucket(providerId: String? = null, modelId: String? = null) = UsageBucket(
            providerId = providerId,
            modelId = modelId,
            calls = calls,
            promptTokens = prompt,
            completionTokens = completion,
            totalTokens = total,
            cacheReadTokens = cacheRead,
            cacheWriteTokens = cacheWrite,
            reasoningTokens = reasoning,
            actualCostUsd = actualCost,
            estimatedCostUsd = estimatedCost,
            estimatedCalls = estimatedCalls,
            pricedCalls = pricedCalls,
            allowanceUsd = allowance,
        )
    }
}
