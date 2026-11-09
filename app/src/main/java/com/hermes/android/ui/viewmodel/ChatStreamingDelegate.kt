package com.hermes.android.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

internal class ChatStreamingDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ChatUiState>,
) {
    private var activeAssistantMessageId: String? = null

    /**
     * Guards the two delta buffers.
     *
     * [enqueueDelta] runs on whatever thread the gateway event collector is on
     * (Dispatchers.IO, via the OkHttp reader), while [flushBuffer] runs on the
     * ViewModel scope (Main). `StringBuilder` is not thread-safe, and the read
     * path here is a non-atomic `toString()` then `setLength(0)` pair: a delta
     * that lands between those two calls is appended to a builder that is about
     * to be cleared, so it never reaches the screen. The user sees a reply with
     * a few characters missing out of the middle, which is both invisible in
     * testing and impossible to reproduce on demand.
     */
    private val bufferLock = Any()
    private val streamingBuffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()
    private val sealedInterimTexts = mutableListOf<String>()

    /**
     * Live throughput of the two streams, in characters per second, measured on
     * the raw gateway deltas before any buffering. The app only ever sees the
     * streamed text — the gateway's real token count only arrives (when it
     * arrives at all) in message.complete's usage — so the honest unit is
     * chars/s, shown as "c/s". On Latin prose it lands within ~25% of the
     * server's tok/s; the label says what was actually measured.
     */
    private class RateMeter {
        var tps: Double = 0.0
            private set
        private var startedAt = 0L
        private var lastAt = 0L
        private var chars = 0L

        fun onChars(count: Int, now: Long) {
            if (startedAt == 0L) startedAt = now
            lastAt = now
            chars += count
            val seconds = (lastAt - startedAt) / 1000.0
            if (seconds >= 0.2) tps = chars / seconds
        }

        fun reset() {
            tps = 0.0
            startedAt = 0L
            lastAt = 0L
            chars = 0L
        }
    }

    private val reasoningRate = RateMeter()
    private val generationRate = RateMeter()
    private var lastRatePublishAt = 0L

    /** Push the live rates into the UI state, throttled so the chat list isn't
     *  recomposed on every delta. */
    private fun publishRates(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRatePublishAt < RATE_PUBLISH_INTERVAL_MS) return
        lastRatePublishAt = now
        val r = (reasoningRate.tps * 10).toLong() / 10.0
        val g = (generationRate.tps * 10).toLong() / 10.0
        state.update {
            if (it.reasoningRateTps == r && it.generationRateTps == g) it
            else it.copy(reasoningRateTps = r, generationRateTps = g)
        }
    }

    @Volatile
    private var streamingFlushJob: Job? = null

    val currentAssistantMessageId: String? get() = activeAssistantMessageId

    fun onMessageStart(): String {
        streamingFlushJob?.cancel()
        streamingFlushJob = null
        synchronized(bufferLock) {
            streamingBuffer.setLength(0)
            reasoningBuffer.setLength(0)
        }
        reasoningRate.reset()
        generationRate.reset()
        publishRates(force = true)
        val msgId = java.util.UUID.randomUUID().toString()
        activeAssistantMessageId = msgId
        return msgId
    }

    fun enqueueDelta(text: String, isReasoning: Boolean = false) {
        if (text.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(bufferLock) {
            (if (isReasoning) reasoningBuffer else streamingBuffer).append(text)
        }
        if (isReasoning) reasoningRate.onChars(text.length, now) else generationRate.onChars(text.length, now)
        publishRates()
        if (streamingFlushJob?.isActive == true) return
        streamingFlushJob = scope.launch {
            delay(STREAM_FLUSH_INTERVAL_MS)
            // Clear the handle BEFORE flushing. The other order left a window
            // between flushBuffer() returning and the field going null in which
            // an arriving delta saw isActive == true, skipped scheduling, and
            // then sat in the buffer with nothing queued to drain it — so the
            // last few tokens of a turn only appeared if another delta happened
            // to follow.
            streamingFlushJob = null
            flushBuffer()
        }
    }

    fun flushBuffer() {
        val chunk: String
        val reasoningChunk: String
        synchronized(bufferLock) {
            if (streamingBuffer.isEmpty() && reasoningBuffer.isEmpty()) return
            chunk = streamingBuffer.toString()
            streamingBuffer.setLength(0)
            reasoningChunk = reasoningBuffer.toString()
            reasoningBuffer.setLength(0)
        }
        val targetId = activeAssistantMessageId
        state.update { it.copy(
            messages = it.messages.updateFirst({ msg ->
                msg is ChatMessage.Assistant && msg.isStreaming &&
                    (targetId == null || msg.id == targetId)
            }) { msg ->
                (msg as ChatMessage.Assistant).copy(
                    text = msg.text + chunk,
                    reasoning = if (reasoningChunk.isEmpty()) msg.reasoning
                        else (msg.reasoning ?: "") + reasoningChunk,
                )
            }
        ) }
    }

    /**
     * Seal the live bubble as a finished message and hand back the id of the
     * bubble the rest of the turn streams into, or null when there is nothing
     * to seal. [text] is authoritative: the gateway does not always stream
     * every token it later reports as interim.
     */
    fun sealInterim(text: String): String? {
        flushBuffer()
        val sealedId = activeAssistantMessageId ?: return null
        val authoritative = text.trimStart()
        if (authoritative.isBlank()) return null
        var sealed = false
        state.update { it.copy(
            messages = it.messages.updateFirst({ msg ->
                msg is ChatMessage.Assistant && msg.isStreaming && msg.id == sealedId
            }) { msg ->
                sealed = true
                (msg as ChatMessage.Assistant).copy(text = authoritative, isStreaming = false)
            }
        ) }
        if (!sealed) return null
        sealedInterimTexts += authoritative
        val nextId = java.util.UUID.randomUUID().toString()
        activeAssistantMessageId = nextId
        return nextId
    }

    /**
     * Close the live bubble where it stands and hand back the id the rest of the
     * turn streams into, or null when no bubble is live. Unlike [sealInterim] a
     * bubble with nothing in it yet is kept, so the tools before it stay folded.
     */
    fun continueBelow(): String? {
        flushBuffer()
        val closedId = activeAssistantMessageId ?: return null
        var closedText: String? = null
        state.update { it.copy(
            messages = it.messages.updateFirst({ msg ->
                msg is ChatMessage.Assistant && msg.isStreaming && msg.id == closedId
            }) { msg ->
                closedText = (msg as ChatMessage.Assistant).text.trimStart()
                msg.copy(isStreaming = false)
            }
        ) }
        val text = closedText ?: return null
        if (text.isNotBlank()) sealedInterimTexts += text
        val nextId = java.util.UUID.randomUUID().toString()
        activeAssistantMessageId = nextId
        return nextId
    }

    /** Drop the lead-in that [sealInterim] already put on screen this turn. */
    fun withoutSealedInterims(finalText: String): String {
        var tail = finalText.trimStart()
        for (sealed in sealedInterimTexts) {
            if (tail.startsWith(sealed)) tail = tail.removePrefix(sealed).trimStart()
        }
        return tail
    }

    fun reset() {
        streamingFlushJob?.cancel()
        streamingFlushJob = null
        synchronized(bufferLock) {
            streamingBuffer.setLength(0)
            reasoningBuffer.setLength(0)
        }
        sealedInterimTexts.clear()
        activeAssistantMessageId = null
        reasoningRate.reset()
        generationRate.reset()
        publishRates(force = true)
    }

    fun finalizeOrphanedMessage(marker: String) {
        flushBuffer()
        val orphanedId = activeAssistantMessageId ?: return
        var found = false
        state.update { it.copy(
            messages = it.messages.updateFirst({ msg ->
                msg is ChatMessage.Assistant && msg.isStreaming && msg.id == orphanedId
            }) { msg ->
                found = true
                (msg as ChatMessage.Assistant).copy(
                    isStreaming = false,
                    text = if (msg.text.isBlank()) marker else "${msg.text}\n\n$marker",
                )
            },
            isSending = false,
        ) }
        if (found) {
            Timber.w("[Chat] Finalized orphaned streaming message $orphanedId with marker: $marker")
        }
        activeAssistantMessageId = null
        reset()
    }

    private companion object {
        private const val STREAM_FLUSH_INTERVAL_MS = 80L
        private const val RATE_PUBLISH_INTERVAL_MS = 250L
    }
}
