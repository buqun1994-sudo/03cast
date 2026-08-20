package com.ninepointnine.desktopcast.dlna

import java.net.URI
import java.util.UUID

data class GenaSubscription(
    val sid: String,
    val service: DlnaService,
    val callback: URI,
    val expiresAtMs: Long,
    val sequence: Long,
)

class GenaSubscriptionRegistry(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val subscriptions = linkedMapOf<String, GenaSubscription>()

    @Synchronized
    fun subscribe(service: DlnaService, callbackHeader: String, timeoutHeader: String?): GenaSubscription {
        purgeExpired()
        val callback = CALLBACK_PATTERN.find(callbackHeader)?.groupValues?.get(1)
            ?.let(::validatedCallback)
            ?: throw DlnaControlException(412, "Invalid event callback")
        val timeoutSeconds = parseTimeout(timeoutHeader)
        val subscription = GenaSubscription(
            sid = "uuid:${UUID.randomUUID()}",
            service = service,
            callback = callback,
            expiresAtMs = nowMs() + timeoutSeconds * 1000,
            sequence = 0,
        )
        subscriptions[subscription.sid] = subscription
        return subscription
    }

    @Synchronized
    fun renew(sid: String, timeoutHeader: String?): GenaSubscription {
        purgeExpired()
        val current = subscriptions[sid] ?: throw DlnaControlException(412, "Unknown subscription")
        val renewed = current.copy(expiresAtMs = nowMs() + parseTimeout(timeoutHeader) * 1000)
        subscriptions[sid] = renewed
        return renewed
    }

    @Synchronized
    fun unsubscribe(sid: String) {
        if (subscriptions.remove(sid) == null) {
            throw DlnaControlException(412, "Unknown subscription")
        }
    }

    @Synchronized
    fun nextFor(service: DlnaService): List<GenaSubscription> {
        purgeExpired()
        return subscriptions.values.filter { it.service == service }.map(::advance)
    }

    @Synchronized
    fun next(sid: String): GenaSubscription? {
        purgeExpired()
        return subscriptions[sid]?.let(::advance)
    }

    @Synchronized
    fun clear() {
        subscriptions.clear()
    }

    @Synchronized
    fun size(): Int {
        purgeExpired()
        return subscriptions.size
    }

    fun timeoutHeader(subscription: GenaSubscription): String {
        val remainingSeconds = ((subscription.expiresAtMs - nowMs()) / 1000).coerceAtLeast(1)
        return "Second-$remainingSeconds"
    }

    private fun purgeExpired() {
        val now = nowMs()
        subscriptions.entries.removeAll { it.value.expiresAtMs <= now }
    }

    private fun advance(current: GenaSubscription): GenaSubscription {
        subscriptions[current.sid] = current.copy(sequence = (current.sequence + 1) and UINT32_MASK)
        return current
    }

    private fun parseTimeout(header: String?): Long {
        if (header.isNullOrBlank()) return DEFAULT_TIMEOUT_SECONDS
        if (header.equals("infinite", ignoreCase = true) ||
            header.equals("second-infinite", ignoreCase = true)
        ) {
            return MAX_TIMEOUT_SECONDS
        }
        return header.substringAfter('-', "").toLongOrNull()
            ?.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
            ?: DEFAULT_TIMEOUT_SECONDS
    }

    private fun validatedCallback(value: String): URI {
        val uri = URI(value.trim())
        if (!uri.scheme.equals("http", ignoreCase = true) || uri.host.isNullOrBlank()) {
            throw DlnaControlException(412, "Only HTTP callbacks are supported")
        }
        return uri
    }

    private companion object {
        val CALLBACK_PATTERN = Regex("<([^>]+)>")
        const val DEFAULT_TIMEOUT_SECONDS = 1800L
        const val MIN_TIMEOUT_SECONDS = 60L
        const val MAX_TIMEOUT_SECONDS = 86400L
        const val UINT32_MASK = 0xffffffffL
    }
}
