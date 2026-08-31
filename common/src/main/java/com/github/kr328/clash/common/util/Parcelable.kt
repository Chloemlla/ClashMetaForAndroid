package com.github.kr328.clash.common.util

import android.os.Binder
import android.os.Parcel
import android.os.Parcelable

/**
 * Raised by [createListFromParcelSlice] when the sharded transfer ends before every item was
 * received (transact failure, empty window, or a server that returned fewer items than declared).
 * The caller can retry the whole query once and surface a user-visible "incomplete data" hint
 * instead of silently showing a truncated proxy/provider list.
 */
class SliceReadException(
    message: String,
    val received: Int,
    val expected: Int,
    val offset: Int,
) : IllegalStateException(message)

private class SliceParcelableListBpBinder(val list: List<Parcelable>, val flags: Int) : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, tFlags: Int): Boolean {
        if (code != TRANSACTION_GET_ITEMS) {
            // Any non-slice transaction is not ours. Forward it with the *current* transaction
            // flags (tFlags) — the parameter computed for this call — not the inbound flags, so
            // synchronous/asynchronous intent is preserved (B-128).
            return super.onTransact(code, data, reply, tFlags)
        }

        reply ?: return false

        // B-130: the offset/chunk window comes from the calling process and is untrusted.
        // Clamp it before slicing so a hostile caller cannot ask for a huge chunk (which would
        // blow the 1MB transaction limit on the writer) or a negative/out-of-range offset
        // (IndexOutOfBoundsException crashing the serving process). Out-of-range requests get an
        // empty slice instead of an exception.
        val offset = data.readInt().coerceIn(0, list.size)
        val chunk = data.readInt().coerceIn(1, MAX_CHUNK)

        val end = (offset + chunk).coerceAtMost(list.size)

        reply.writeInt(end - offset)

        for (i in offset until end) {
            list[i].writeToParcel(reply, flags)
        }

        return true
    }

    companion object {
        const val TRANSACTION_GET_ITEMS = 10

        /** Upper bound for a single slice, matching the 20/50 item chunks the callers use. */
        const val MAX_CHUNK = 50
    }
}

fun <T : Parcelable> List<T>.writeToParcelSlice(parcel: Parcel, flags: Int) {
    val bp = SliceParcelableListBpBinder(this, flags)

    parcel.writeInt(size)
    parcel.writeStrongBinder(bp)
}

fun <T : Parcelable> Parcelable.Creator<T>.createListFromParcelSlice(
    parcel: Parcel,
    flags: Int,
    chunk: Int,
): List<T> {
    val total = parcel.readInt()
    val remote = parcel.readStrongBinder()
    val result = ArrayList<T>(total)

    var offset = 0

    while (offset < total) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()

        try {
            data.writeInt(offset)
            data.writeInt(chunk)

            if (!remote.transact(
                    SliceParcelableListBpBinder.TRANSACTION_GET_ITEMS,
                    data,
                    reply,
                    flags
                )
            ) {
                // B-129: a failed transact must not be silently treated as end-of-list. The
                // partial result we already have is not a valid proxy list.
                throw SliceReadException(
                    "slice transact failed at offset $offset (received ${result.size} of $total)",
                    received = result.size,
                    expected = total,
                    offset = offset,
                )
            }

            val size = reply.readInt()

            if (size == 0 && offset < total) {
                // B-129: the server returned an empty window before delivering everything.
                // Fail loudly with the offset for diagnosis rather than truncating silently.
                throw SliceReadException(
                    "slice server returned an empty window at offset $offset (received ${result.size} of $total)",
                    received = result.size,
                    expected = total,
                    offset = offset,
                )
            }

            repeat(size) {
                result.add(createFromParcel(reply))
            }

            offset += size
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    return result
}