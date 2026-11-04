package app.kujira.android.net.mcp

import app.kujira.android.data.model.ToolDescriptor

/*
 * MCP (Model Context Protocol) client contract.
 *
 * Transport is Streamable HTTP: JSON-RPC 2.0 frames POSTed to the server URL,
 * with responses that are either application/json or text/event-stream. The
 * MCP session id returned by initialize must be echoed on later requests.
 */

interface McpClient {
    /** Runs initialize + notifications/initialized and returns server info. */
    suspend fun connect(): McpConnection

    /** tools/list — discovered tools for this session. */
    suspend fun listTools(): List<ToolDescriptor>

    /** tools/call — executes one tool and returns its textual result. */
    suspend fun callTool(name: String, argumentsJson: String): McpToolResult

    /** ping — cheap liveness check. */
    suspend fun ping(): Boolean

    fun close()
}

data class McpConnection(
    val protocolVersion: String,
    val serverName: String?,
    val serverVersion: String?,
    val instructions: String?,
    val sessionId: String?
)

data class McpToolResult(
    val output: String,
    val isError: Boolean,
    /** Raw structuredContent JSON when the server returns one. */
    val structuredJson: String? = null
)

/** Thrown for transport/protocol failures, carrying a human-readable cause. */
class McpException(message: String, cause: Throwable? = null) : Exception(message, cause)

class McpAuthRequiredException(message: String = "Authorization required") : Exception(message)
