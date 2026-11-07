package app.kujira.android.domain

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine-context tag that says which conversation the running generation
 * belongs to.
 *
 * Several conversations can now generate at the same time, so anything that is
 * called from inside the chat loop (tool confirmation, audit logging, ...) and
 * needs to know "which session am I in?" reads it from the coroutine context
 * instead of a single global field:
 *
 * ```
 * val id = currentCoroutineContext()[ConversationContext]?.conversationId
 * ```
 */
class ConversationContext(val conversationId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ConversationContext>
}
