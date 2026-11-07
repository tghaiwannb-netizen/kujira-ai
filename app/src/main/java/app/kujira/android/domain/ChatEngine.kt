package app.kujira.android.domain

import app.kujira.android.data.model.AppSettings
import app.kujira.android.data.model.ContentBlock
import app.kujira.android.data.model.Message
import app.kujira.android.data.model.MessageStatus
import app.kujira.android.data.model.ProviderConfig
import app.kujira.android.data.model.Role
import app.kujira.android.data.model.ToolDescriptor
import app.kujira.android.data.model.Usage
import app.kujira.android.net.llm.LlmEvent
import app.kujira.android.net.llm.LlmJson
import app.kujira.android.net.llm.LlmRequest
import app.kujira.android.net.llm.LlmStreamer
import app.kujira.android.net.llm.LlmToolCall
import app.kujira.android.net.llm.LlmToolSpec
import app.kujira.android.net.llm.LlmTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Everything needed to produce one assistant answer.
 *
 * [history] is the conversation transcript *before* [userText]; the engine
 * converts it to provider-neutral turns, so callers only deal with persisted
 * [Message]s. [settings] supplies sampling parameters and the tool-loop limit.
 */
data class ChatRequest(
    val conversationId: String,
    val history: List<Message>,
    val userText: String,
    val provider: ProviderConfig,
    val apiKey: String,
    val modelId: String,
    val settings: AppSettings,
    /** Conversation-level override; falls back to [AppSettings.systemPrompt]. */
    val systemPrompt: String? = null,
    /** When false, no tools are advertised and no tool loop happens. */
    val toolsEnabled: Boolean = true,
)

/**
 * The chat/agent loop: streams one model turn, executes requested tools, feeds
 * the results back and repeats until the model answers without tool calls or
 * [AppSettings.toolLoopLimit] rounds have been used.
 *
 * The engine is transport-agnostic: it talks to an [LlmStreamer] (the router in
 * production, a fake in tests) and to [ToolExecutor] for tools. It never throws
 * for model/network failures — they surface as [ChatEvent.Failed] — and
 * cancelling collection stops the in-flight HTTP stream because the underlying
 * flow is cancelled.
 */
class ChatEngine(
    private val streamer: LlmStreamer,
    private val toolExecutorProvider: () -> ToolExecutor?,
    private val json: Json = LlmJson,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    fun send(request: ChatRequest): Flow<ChatEvent> = flow {
        val executor = if (request.toolsEnabled) toolExecutorProvider() else null
        val descriptors = executor?.availableTools().orEmpty()
        val toolsByName = descriptors.associateBy { it.name }
        val toolSpecs = descriptors.map { descriptor ->
            LlmToolSpec(
                name = descriptor.name,
                description = descriptor.description,
                inputSchemaJson = normalizeSchema(descriptor.inputSchemaJson)
            )
        }

        val turns = toLlmTurns(request.history).toMutableList()
        turns += LlmTurn(Role.USER, request.userText)
        val systemPrompt = effectiveSystemPrompt(request)
        val limit = request.settings.toolLoopLimit.coerceAtLeast(1)

        val produced = mutableListOf<Message>()
        var totalUsage = Usage()
        var hasUsage = false
        var round = 0

        while (true) {
            round += 1
            val outcome = runRound(request, turns, toolSpecs, systemPrompt)

            if (outcome is RoundOutcome.Failure) {
                emit(
                    ChatEvent.Failed(
                        message = outcome.event.message,
                        httpCode = outcome.event.httpCode,
                        retryable = outcome.event.retryable,
                        partialText = outcome.text,
                        partialMessages = produced.toList()
                    )
                )
                return@flow
            }

            outcome as RoundOutcome.Success
            outcome.usage?.let {
                totalUsage = totalUsage + it
                hasUsage = true
            }

            val toolLimitReached = outcome.toolCalls.isNotEmpty() && round >= limit
            val assistantMessage = Message(
                conversationId = request.conversationId,
                role = Role.ASSISTANT,
                blocks = buildList {
                    addAll(roundBlocks(outcome, toolsByName))
                    if (toolLimitReached) {
                        add(
                            ContentBlock.Text(
                                "\n\n⚠️ 已达到工具调用上限（$limit 轮），已停止继续调用工具。"
                            )
                        )
                    }
                },
                providerId = request.provider.id,
                modelId = request.modelId,
                status = MessageStatus.COMPLETE,
                usage = outcome.usage
            )
            produced += assistantMessage

            if (outcome.toolCalls.isEmpty() || toolLimitReached) {
                emit(
                    ChatEvent.Completed(
                        messages = produced.toList(),
                        usage = if (hasUsage) totalUsage else null,
                        rounds = round,
                        toolLimitReached = toolLimitReached
                    )
                )
                return@flow
            }

            // The model asked for tools: persist the call turn, execute, feed back.
            turns += LlmTurn(
                role = Role.ASSISTANT,
                text = outcome.text,
                toolCalls = outcome.toolCalls
            )

            val resultBlocks = mutableListOf<ContentBlock>()
            for (call in outcome.toolCalls) {
                val descriptor = toolsByName[call.name]
                emit(
                    ChatEvent.ToolStarted(
                        callId = call.id,
                        toolName = call.name,
                        pluginId = descriptor?.pluginId.orEmpty(),
                        pluginName = descriptor?.pluginName ?: call.name,
                        argumentsJson = call.argumentsJson
                    )
                )
                val startedAt = clock()
                val result = executeTool(executor, descriptor, call)
                val duration = if (result.durationMs > 0L) {
                    result.durationMs
                } else {
                    (clock() - startedAt).coerceAtLeast(0L)
                }
                emit(
                    ChatEvent.ToolFinished(
                        callId = call.id,
                        toolName = call.name,
                        output = result.output,
                        isError = result.isError,
                        durationMs = duration
                    )
                )
                resultBlocks += ContentBlock.ToolResult(
                    callId = call.id,
                    toolName = call.name,
                    pluginId = descriptor?.pluginId.orEmpty(),
                    output = result.output,
                    isError = result.isError,
                    durationMs = duration
                )
                turns += LlmTurn(
                    role = Role.TOOL,
                    text = if (result.isError) "工具执行失败：${result.output}" else result.output,
                    toolCallId = call.id,
                    toolName = call.name
                )
            }
            if (resultBlocks.isNotEmpty()) {
                produced += Message(
                    conversationId = request.conversationId,
                    role = Role.TOOL,
                    blocks = resultBlocks,
                    providerId = request.provider.id,
                    modelId = request.modelId,
                    status = MessageStatus.COMPLETE
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private suspend fun FlowCollector<ChatEvent>.runRound(
        request: ChatRequest,
        turns: List<LlmTurn>,
        toolSpecs: List<LlmToolSpec>,
        systemPrompt: String?,
    ): RoundOutcome {
        val llmRequest = LlmRequest(
            provider = request.provider,
            apiKey = request.apiKey,
            model = request.modelId,
            systemPrompt = systemPrompt,
            messages = turns.toList(),
            tools = toolSpecs,
            temperature = request.settings.temperature,
            topP = request.settings.topP,
            maxTokens = request.settings.maxTokens,
            sessionId = request.conversationId
        )
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var toolCalls: List<LlmToolCall> = emptyList()
        var usage: Usage? = null
        var finishReason: String? = null
        var failure: LlmEvent.Failed? = null

        try {
            streamer.stream(llmRequest).collect { event ->
                when (event) {
                    is LlmEvent.TextDelta -> {
                        text.append(event.text)
                        emit(ChatEvent.TextDelta(event.text))
                    }

                    is LlmEvent.ReasoningDelta -> {
                        reasoning.append(event.text)
                        if (request.settings.showReasoning) {
                            emit(ChatEvent.ReasoningDelta(event.text))
                        }
                    }

                    is LlmEvent.Finished -> {
                        toolCalls = event.toolCalls
                        usage = event.usage
                        finishReason = event.finishReason
                    }

                    is LlmEvent.Failed -> failure = event
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure = LlmEvent.Failed(
                "请求失败：${e.message ?: e.javaClass.simpleName}",
                null,
                false
            )
        }

        val failed = failure
        return if (failed != null) {
            RoundOutcome.Failure(failed, text.toString())
        } else {
            RoundOutcome.Success(
                text = text.toString(),
                reasoning = reasoning.toString(),
                toolCalls = toolCalls,
                usage = usage,
                finishReason = finishReason
            )
        }
    }

    private fun roundBlocks(
        outcome: RoundOutcome.Success,
        toolsByName: Map<String, ToolDescriptor>,
    ): List<ContentBlock> = buildList {
        if (outcome.reasoning.isNotBlank()) add(ContentBlock.Reasoning(outcome.reasoning))
        if (outcome.text.isNotBlank()) add(ContentBlock.Text(outcome.text))
        // An empty final turn must not render as a blank bubble: tell the user
        // why nothing arrived (reasoning models can spend the whole maxTokens
        // budget on thinking and emit no visible text at all).
        if (outcome.text.isBlank() && outcome.toolCalls.isEmpty()) {
            add(ContentBlock.Text(emptyAnswerHint(outcome.finishReason, outcome.reasoning.isNotBlank())))
        }
        for (call in outcome.toolCalls) {
            val descriptor = toolsByName[call.name]
            add(
                ContentBlock.ToolCall(
                    id = call.id,
                    name = call.name,
                    argumentsJson = call.argumentsJson,
                    pluginId = descriptor?.pluginId.orEmpty(),
                    pluginName = descriptor?.pluginName ?: call.name
                )
            )
        }
    }

    private suspend fun executeTool(
        executor: ToolExecutor?,
        descriptor: ToolDescriptor?,
        call: LlmToolCall,
    ): ToolExecResult {
        if (executor == null) return ToolExecResult("插件系统当前不可用。", isError = true)
        if (descriptor == null) return ToolExecResult("未知工具：${call.name}", isError = true)
        return try {
            executor.execute(descriptor, call.argumentsJson)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ToolExecResult("工具执行异常：${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    private fun effectiveSystemPrompt(request: ChatRequest): String? =
        request.systemPrompt?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.settings.systemPrompt.trim().takeIf { it.isNotEmpty() }

    /**
     * Explains an empty final answer instead of leaving the user with a blank
     * bubble. `finish_reason=length` with hidden reasoning is the common case:
     * the model exhausted `maxTokens` while thinking and never wrote a reply.
     */
    private fun emptyAnswerHint(finishReason: String?, hadReasoning: Boolean): String {
        val reason = finishReason?.takeIf { it.isNotBlank() }
            ?.let { "（finish_reason: $it）" }
            .orEmpty()
        return when {
            finishReason == "length" && hadReasoning ->
                "⚠️ 模型把输出额度都用在了思考过程上，没有生成正文$reason。" +
                    "请调大设置里的「最大输出长度」，或换用非推理模型后重试。"

            finishReason == "length" ->
                "⚠️ 模型达到了最大输出长度，没有生成正文$reason。" +
                    "请调大设置里的「最大输出长度」后重试。"

            else ->
                "⚠️ 模型没有返回任何内容$reason。请重试，或换一个模型。"
        }
    }

    private fun normalizeSchema(raw: String): String {
        val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        return if (parsed is JsonObject) raw else EMPTY_SCHEMA
    }

    private sealed interface RoundOutcome {
        data class Success(
            val text: String,
            val reasoning: String,
            val toolCalls: List<LlmToolCall>,
            val usage: Usage?,
            val finishReason: String?,
        ) : RoundOutcome

        data class Failure(val event: LlmEvent.Failed, val text: String) : RoundOutcome
    }

    companion object {
        private const val EMPTY_SCHEMA = """{"type":"object","properties":{}}"""

        /**
         * Converts persisted messages into provider-neutral turns.
         *
         * Assistant reasoning is intentionally not replayed (providers require
         * signatures/round-trips for thinking), and one TOOL message with several
         * results expands into several tool-result turns.
         */
        fun toLlmTurns(messages: List<Message>): List<LlmTurn> {
            val turns = mutableListOf<LlmTurn>()
            for (message in messages) {
                when (message.role) {
                    Role.SYSTEM, Role.USER -> {
                        if (message.text.isNotBlank()) turns += LlmTurn(message.role, message.text)
                    }

                    Role.ASSISTANT -> {
                        val calls = message.blocks
                            .filterIsInstance<ContentBlock.ToolCall>()
                            .map { LlmToolCall(it.id, it.name, it.argumentsJson) }
                        if (message.text.isNotBlank() || calls.isNotEmpty()) {
                            turns += LlmTurn(
                                role = Role.ASSISTANT,
                                text = message.text,
                                toolCalls = calls
                            )
                        }
                    }

                    Role.TOOL -> {
                        for (result in message.blocks.filterIsInstance<ContentBlock.ToolResult>()) {
                            turns += LlmTurn(
                                role = Role.TOOL,
                                text = if (result.isError) "工具执行失败：${result.output}" else result.output,
                                toolCallId = result.callId,
                                toolName = result.toolName
                            )
                        }
                    }
                }
            }
            return turns
        }
    }
}
