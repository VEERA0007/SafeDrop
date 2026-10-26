package com.safedrop.app

sealed class Decision {
    object Allow : Decision()
    data class AskParent(val reason: String) : Decision()
    data class Block(val reason: String) : Decision()
}

/** The single place where parental rules are applied, for both sending and receiving. */
object Rules {

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun evaluate(
        store: ParentalStore,
        outgoing: Boolean,
        fileName: String,
        size: Long,
        peer: String,
        hourNow: Int
    ): Decision {
        val mode = if (outgoing) store.sendMode else store.receiveMode
        val verb = if (outgoing) "Sending" else "Receiving"

        if (mode == Mode.BLOCK) return Decision.Block("$verb files is switched off by a parent")

        if (store.quietEnabled && inQuietHours(hourNow, store.quietStartHour, store.quietEndHour)) {
            return Decision.Block("Quiet hours are active")
        }

        val allowed = store.allowedPeers
        if (allowed.isNotEmpty() && allowed.none { it.equals(peer, ignoreCase = true) }) {
            return Decision.Block("\"$peer\" is not on the approved devices list")
        }

        val ext = extensionOf(fileName)
        val blockedCategory = store.blockedCategories.firstOrNull { ext in it.extensions }
        if (blockedCategory != null) {
            return Decision.Block("${blockedCategory.label} are blocked")
        }

        if (size <= 0) return Decision.Block("File is empty or its size is unknown")
        if (size > store.maxSizeMb * 1024L * 1024L) {
            return Decision.Block("File is larger than ${store.maxSizeMb} MB")
        }

        if (mode == Mode.ASK_PARENT) {
            if (!store.hasPin) return Decision.Block("Parent setup isn't finished (no PIN yet)")
            return Decision.AskParent("A parent must approve this file")
        }
        return Decision.Allow
    }

    fun inQuietHours(hour: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> hour in start until end
        else -> hour >= start || hour < end // wraps past midnight
    }
}
