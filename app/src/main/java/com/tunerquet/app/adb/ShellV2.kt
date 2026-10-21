package com.tunerquet.app.adb

import com.tunerquet.app.inventory.ShellResult
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Parse only complete shell-v2 streams, preserving exit status and stderr independently. */
internal object ShellV2 {
    fun decode(bytes: ByteArray, maxBytes: Int): ShellResult {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val stdout = java.io.ByteArrayOutputStream()
        val stderr = java.io.ByteArrayOutputStream()
        var exit: Int? = null
        while (input.hasRemaining()) {
            if (input.remaining() < 5 || exit != null) throw IOException("Truncated or trailing shell-v2 frame")
            val type = input.get().toInt() and 255
            val size = input.int
            if (size < 0 || size > input.remaining() || size > maxBytes) throw IOException("Invalid shell-v2 length")
            when (type) {
                1, 2 -> {
                    if (stdout.size().toLong() + stderr.size() + size > maxBytes) throw IOException("Inventory output limit exceeded")
                    val payload = ByteArray(size).also(input::get)
                    (if (type == 1) stdout else stderr).write(payload)
                }
                3 -> {
                    if (size != 1) throw IOException("Invalid shell exit status")
                    exit = input.get().toInt() and 255
                }
                else -> throw IOException("Unexpected shell-v2 channel")
            }
        }
        return ShellResult(stdout.toString("UTF-8"), stderr.toString("UTF-8"), exit ?: throw IOException("Missing shell exit status"))
    }
}
