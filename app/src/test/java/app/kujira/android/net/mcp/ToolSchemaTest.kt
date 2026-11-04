package app.kujira.android.net.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSchemaTest {

    private val execSchema = """
        {
          "additionalProperties": false,
          "properties": {
            "command": {"type": "string"},
            "timeout": {"type": "integer", "default": 30},
            "host_id": {"anyOf": [{"type": "string"}, {"type": "null"}], "default": null}
          },
          "required": ["command"],
          "type": "object"
        }
    """.trimIndent()

    @Test
    fun `reads properties and required names`() {
        assertEquals(setOf("command", "timeout", "host_id"), ToolSchema.properties(execSchema).keys)
        assertEquals(setOf("command"), ToolSchema.required(execSchema))
    }

    @Test
    fun `hasParameter detects host_id`() {
        assertTrue(ToolSchema.hasParameter(execSchema, "host_id"))
        assertFalse(ToolSchema.hasParameter(execSchema, "hostId"))
        assertFalse(ToolSchema.hasParameter("{}", "host_id"))
        assertFalse(ToolSchema.hasParameter(null, "host_id"))
    }

    @Test
    fun `summary marks required params`() {
        val summary = ToolSchema.summary(execSchema)
        assertTrue(summary.startsWith("参数："))
        assertTrue(summary.contains("command*"))
        assertTrue(summary.contains("timeout"))
        assertTrue(summary.contains("host_id"))
    }

    @Test
    fun `summary handles empty schemas`() {
        assertEquals("无参数", ToolSchema.summary("""{"type":"object","properties":{}}"""))
        assertEquals("无参数", ToolSchema.summary(null))
    }

    @Test
    fun `pretty prints valid json only`() {
        val pretty = ToolSchema.pretty(execSchema)
        assertTrue(pretty.contains("\n"))
        assertTrue(pretty.contains("\"command\""))
        assertEquals("not json", ToolSchema.pretty("not json"))
        assertNull(McpJson.parseObject("not json"))
    }
}
