package com.uhg0.ar_flutter_plugin_2.visibilityprotocol


import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long

class VisibilityProtocolResyncCommandTest {
    @Test
    fun `resync command matches the locked 50-byte golden vector`() {
        val command = ResyncCommandV1(
            ResyncPayloadV1(
                lastCommittedTransactionId = 0x0102030405060708,
                lastCommittedGeometryRevision = 0x1112131415161718,
                lastCommittedLineageRevision = 0x2122232425262728,
                failedTransactionId = 0x3132333435363738,
                reason = ResyncReason.INVALID_PAYLOAD_CRC,
                flags = 1,
                failedResponseRequestSequence = 0x4142434445464748,
            ),
        )
        assertEquals(
            "0401080706050403020118171615141312112827262524232221383736353433323102000100000000004847464544434241",
            command.encode().toHex(),
        )
        val decoded = ResyncCommandV1.decode(command.encode())
        assertEquals(ResyncReason.INVALID_PAYLOAD_CRC, decoded.payload.reason)
        assertTrue(decoded.payload.failedResponseHeaderReadable)
    }

    @Test
    fun `resync payload rejects reserved bits and bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            ResyncPayloadV1(
                lastCommittedTransactionId = 7,
                lastCommittedGeometryRevision = 0,
                lastCommittedLineageRevision = 0,
                failedTransactionId = 7,
                reason = ResyncReason.INVALID_TRANSACTION_ORDER,
            )
        }
        val command = ResyncCommandV1(
            ResyncPayloadV1(
                lastCommittedTransactionId = 7,
                lastCommittedGeometryRevision = 8,
                lastCommittedLineageRevision = 9,
                failedTransactionId = 10,
                reason = ResyncReason.ABANDONED_STAGING,
            ),
        ).encode()
        command[2 + 36] = 1
        assertThrows(IllegalArgumentException::class.java) {
            ResyncCommandV1.decode(command)
        }
    }

    @Test
    fun `resync command matches the checked-in cross-language fixture`() {
        val root = Json.parseToJsonElement(
            requireNotNull(javaClass.classLoader?.getResourceAsStream("visibility_protocol_resync_command_v1.json"))
                .bufferedReader().use { it.readText() },
        ).jsonObject
        val command = ResyncCommandV1.decode(hex(root.getValue("commandHex").jsonPrimitive.content))
        assertEquals(root.getValue("reasonId").jsonPrimitive.int, command.payload.reason.id)
        assertEquals(root.getValue("flags").jsonPrimitive.int, command.payload.flags)
        assertEquals(
            root.getValue("lastCommittedTransactionId").jsonPrimitive.long,
            command.payload.lastCommittedTransactionId,
        )
        assertEquals(
            root.getValue("failedResponseRequestSequence").jsonPrimitive.long,
            command.payload.failedResponseRequestSequence,
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
