package com.uhg0.ar_flutter_plugin_2.m0

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Strict executable lock for the canonical Issue 98 VGS2 recovery policies. */
object M0aVgs2RecoveryCorpus {
    const val SHA256 = "356d614ffa5861a367ed7358315d5b01832798eb5e52bbb42f1527bcd3d3b2bb"
    private val policyIds = setOf(4, 8, 48, 142, 144)
    private val rootKeys = setOf("format", "policies", "freshBinding")
    private val policyKeys = setOf(
        "errorId", "resultFlags", "disposition", "validationPhase", "recoveryAction",
        "fieldId", "sequenceDisposition", "requestSequence", "nextExpectedRequestSequence", "scope",
        "authorityKind", "diagnosticBytes", "geometryRevision", "lineageRevision",
        "captureRevision", "coverageRevision", "acceptedStyleRevision",
        "regionManifestRevision", "nextSurfaceIdHighWater", "schemaRootRevision",
        "expectedValue", "observedValue",
    )
    private val freshKeys = setOf(
        "oldTransactionId", "oldRequestSequence", "freshTransactionId", "freshRequestSequence",
        "nextTransactionId", "semanticEffectCount", "oldTokenPublicationCount", "geometryRevision",
        "lineageRevision", "captureRevision", "coverageRevision", "acceptedStyleRevision",
        "regionManifestRevision", "nextSurfaceIdHighWater", "schemaRootRevision",
    )

    data class Receipt(val policyCases: Int, val freshBindingCases: Int)

    fun run(bytes: ByteArray, expectedSha256: String = SHA256): Receipt {
        require(sha256(bytes) == expectedSha256) { "Issue 98 corpus SHA-256 mismatch" }
        val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        root.requireExactKeys(rootKeys, "root")
        require(root.getValue("format").jsonPrimitive.content ==
            "proposal08_m1_issue98_vgs2_recovery_corpus_v1")
        val policies = root.getValue("policies").jsonArray.map { it.jsonObject }
        require(policies.map { it.int("errorId") }.toSet() == policyIds && policies.size == policyIds.size)
        policies.forEach { executePolicy(it) }
        val fresh = root.getValue("freshBinding").jsonObject
        fresh.requireExactKeys(freshKeys, "freshBinding")
        executeFreshBinding(fresh)
        return Receipt(policies.size, 1)
    }

    private fun executePolicy(row: JsonObject) {
        row.requireExactKeys(policyKeys, "policy")
        val id = row.int("errorId")
        val policy = M0aPacketCodec.errorPolicy(id)
        require(policy.resultFlags == row.int("resultFlags"))
        require(policy.disposition == row.int("disposition"))
        require(policy.validationPhase == row.int("validationPhase"))
        require(policy.recoveryAction == row.int("recoveryAction"))
        require(policy.fieldId == row.int("fieldId"))
        require(policy.sequenceDisposition.name.lowercase().replace("_", "") ==
            row.string("sequenceDisposition").lowercase())
        val authority = M0aPacketCodec.ErrorAuthority(
            row.long("geometryRevision"), row.long("lineageRevision"),
            row.long("captureRevision"), row.long("coverageRevision"),
            row.long("acceptedStyleRevision"), row.long("regionManifestRevision"),
            row.long("nextSurfaceIdHighWater"), row.long("schemaRootRevision"),
        )
        val requestSequence = row.long("requestSequence")
        require(row.long("expectedValue") == requestSequence)
        require(row.long("observedValue") == requestSequence - 1)
        val response = M0aPacketCodec.decodeResponse(M0aPacketCodec.encodeResponse(
            M0aPacketCodec.error(
                streamToken = 7, requestSequence = requestSequence,
                nextExpectedRequestSequence = row.long("expectedValue"),
                errorId = id, authority = authority,
                expectedValue = row.long("expectedValue"), observedValue = row.long("observedValue"),
            ), 4096,
        ))
        val detail = M0aControlCodec.decodeErrorDetail(response.payload)
        require(response.resultFlags == row.int("resultFlags"))
        require(response.nextExpectedRequestSequence == row.long("nextExpectedRequestSequence"))
        require(detail.scope == row.int("scope") && detail.authorityKind == row.int("authorityKind"))
        require(detail.fieldId == row.int("fieldId"))
        require(detail.diagnosticBytes == row.int("diagnosticBytes"))
        require(detail.geometryRevision == authority.geometryRevision)
        require(detail.lineageRevision == authority.lineageRevision)
        require(detail.captureRevision == authority.captureRevision)
        require(detail.coverageRevision == authority.coverageRevision)
        require(detail.acceptedStyleRevision == authority.acceptedStyleRevision)
        require(detail.regionManifestRevision == authority.regionManifestRevision)
        require(detail.nextSurfaceIdHighWater == authority.nextSurfaceIdHighWater)
        require(detail.schemaRootRevision == authority.schemaRootRevision)
        require(detail.expectedValue == row.long("expectedValue"))
        require(detail.observedValue == row.long("observedValue"))
    }

    private fun executeFreshBinding(row: JsonObject) {
        val old = M0aCommittedBaselineV1(
            row.long("oldTransactionId"), row.long("geometryRevision"), row.long("lineageRevision"),
            row.long("acceptedStyleRevision"), captureRevision = row.long("captureRevision"),
            coverageRevision = row.long("coverageRevision"),
            regionManifestRevision = row.long("regionManifestRevision"),
            schemaRootRevision = row.long("schemaRootRevision"),
            nextSurfaceIdHighWater = row.long("nextSurfaceIdHighWater"),
        )
        val fresh = M0aCommittedBaselineV1.forFreshBinding(old)
        require(row.long("oldRequestSequence") == Long.MAX_VALUE)
        val request = M0aControlRequest(
            operation = M0aControlOperation.START,
            flags = 0,
            controlRequestId = uuid(1),
            sessionId = uuid(17),
            captureGroupId = uuid(33),
            sessionGeneration = 1,
            groupGeneration = 1,
            coverageEpoch = 1,
            streamToken = 0,
            payload = M0aStartRequestCodecV2.defaultPayload(),
        )
        val lifecycle = M0aControlLifecycle(initialCommittedBaseline = old)
        val response = M0aControlCodec.decodeResponse(
            lifecycle.handle(request, M0aControlCodec.encodeRequest(request)),
        )
        require(response.outcome == 0)
        require(response.nativeTransactionId == row.long("freshTransactionId"))
        require(response.nextExchangeRequestSequence == row.long("freshRequestSequence"))
        require(lifecycle.committedBaseline() == fresh)
        val frames = M0aStructuralTransactionProducerV1.produce(
            transactionId = row.long("nextTransactionId"),
            baseGeometryRevision = fresh.geometryRevision,
            targetGeometryRevision = fresh.geometryRevision + 1,
            targetLineageRevision = fresh.lineageRevision + 1,
            bytes = byteArrayOf(),
        )
        require(frames.first() is M0aTransactionBeginFrameV1)
        require(frames.last() is M0aTransactionCommitFrameV1)
        require(fresh.copy(transactionId = old.transactionId) == old)
        require(row.int("semanticEffectCount") == 1 && row.int("oldTokenPublicationCount") == 0)
    }

    private fun uuid(seed: Int): M0aUuid {
        val bytes = ByteArray(16) { (seed + it).toByte() }
        bytes[6] = 0x40
        bytes[8] = 0x80.toByte()
        return M0aUuid(bytes)
    }

    private fun JsonObject.requireExactKeys(expected: Set<String>, label: String) {
        require(keys == expected) { "$label has missing or unknown fields: expected=$expected actual=$keys" }
    }
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
