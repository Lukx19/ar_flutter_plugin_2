package com.uhg0.ar_flutter_plugin_2.capture

/** Strict scalar-only platform-channel codec for the V2 capture owner. */
internal object NativeCaptureWireV2 {
    fun decodeAdmission(value: Any?): CaptureCommitRequest {
        val root = value.map("admission")
        root.exactKeys(setOf("wireVersion", "accepted", "poseRecordHash", "cameraModelHash", "validationRecordHash", "ledgerRecordHash", "recoveryContext"))
        require(root.string("wireVersion") == "native_capture_v2")
        val accepted = decodeAccepted(root.getValue("accepted"))
        return CaptureCommitRequest(
            accepted = accepted,
            components = emptyList(),
            exposureTimestampNanoseconds = 0L,
            poseRecordHash = root.digest("poseRecordHash"),
            cameraModelHash = root.digest("cameraModelHash"),
            validationRecordHash = root.digest("validationRecordHash"),
            ledgerRecordHash = root.digest("ledgerRecordHash"),
            recoveryContext = decodeRecoveryContext(root.getValue("recoveryContext")),
        )
    }

    fun event(value: NativeCaptureEventV2): Map<String, Any?> = buildMap {
        put("wireVersion", "native_capture_v2")
        put(
            "kind",
            if (value.kind == NativeCaptureEventKindV2.RECOVERY_FAILED) "recoveryFailed"
            else value.kind.name.lowercase(),
        )
        value.attemptId?.let { put("attemptId", it) }
        value.captureId?.let { put("captureId", it) }
        value.captureRevision?.let { put("captureRevision", it) }
        value.manifestId?.let { put("manifestId", it) }
        value.reason?.let { put("reason", it) }
        value.recoveryContext?.let { put("recoveryContext", encodeRecoveryContext(it)) }
        value.resources?.let { resources ->
            put("health", mapOf(
                "exposures" to resources.exposures,
                "lateCallbacks" to resources.lateCallbacks,
                "closedComponents" to resources.closedComponents,
                "committed" to resources.committed,
                "abandoned" to resources.abandoned,
                "unknownQueries" to resources.unknownQueries,
                "running" to resources.running,
                "fundedWaiting" to resources.fundedWaiting,
                "retainedImageBytes" to 0L,
                "readyPictureSets" to 0,
            ))
        }
    }

    private fun decodeRecoveryContext(value: Any?): NativeCaptureRecoveryContextV2 {
        val map = value.map("recoveryContext")
        map.exactKeys(setOf("sessionId", "groupId", "groupIndex", "groupGeneration", "trigger", "requestedAtMs", "coverageRevision", "timestampMs", "position", "rotation", "viewMatrix", "projectionMatrix", "groupFromWorld", "worldFromGroup"))
        return NativeCaptureRecoveryContextV2(
            map.string("sessionId"), map.string("groupId"), map.long("groupIndex"), map.long("groupGeneration"),
            map.string("trigger"), map.long("requestedAtMs"), map.long("coverageRevision"), map.long("timestampMs"),
            map.doubles("position"), map.doubles("rotation"), map.doubles("viewMatrix"), map.doubles("projectionMatrix"),
            map.doubles("groupFromWorld"), map.doubles("worldFromGroup"),
        )
    }

    private fun encodeRecoveryContext(value: NativeCaptureRecoveryContextV2) = mapOf(
        "sessionId" to value.sessionId, "groupId" to value.groupId, "groupIndex" to value.groupIndex,
        "groupGeneration" to value.groupGeneration, "trigger" to value.trigger, "requestedAtMs" to value.requestedAtMs,
        "coverageRevision" to value.coverageRevision, "timestampMs" to value.timestampMs, "position" to value.position,
        "rotation" to value.rotation, "viewMatrix" to value.viewMatrix, "projectionMatrix" to value.projectionMatrix,
        "groupFromWorld" to value.groupFromWorld, "worldFromGroup" to value.worldFromGroup,
    )

    private fun decodeAccepted(value: Any?): CaptureAcceptedAttempt {
        val map = value.map("accepted")
        map.exactKeys(setOf("identity", "lane", "profile", "reservation", "canonicalIntentHash", "acceptedReceiptHash"))
        return CaptureAcceptedAttempt(
            identity = decodeIdentity(map.getValue("identity")),
            lane = CaptureLane.valueOf(map.string("lane").uppercase()),
            profile = decodeProfile(map.getValue("profile")),
            reservation = decodeReservation(map.getValue("reservation")),
            canonicalIntentHash = map.digest("canonicalIntentHash"),
            acceptedReceiptHash = map.digest("acceptedReceiptHash"),
        ).also { accepted ->
            val reservation = accepted.reservation
            val profile = accepted.profile
            require(reservation.physicallyBacked)
            require(reservation.memoryBytes >= profile.maximumWorkingBytes)
            require(reservation.physicalStoreBytes >= NativeCaptureReservationBoundsV2.physicalBytes(profile.maximumComponentBytes, profile.requiredComponents.size))
            require(reservation.rollbackBytes >= NativeCaptureReservationBoundsV2.rollbackBytes(profile.maximumComponentBytes))
            require(reservation.componentEntries == profile.requiredComponents.size.toLong())
            require(reservation.terminalEntries == 1L)
        }
    }

    private fun decodeIdentity(value: Any?): CaptureAttemptIdentity {
        val map = value.map("identity")
        map.exactKeys(setOf("attemptId", "commitId", "attemptOrdinal", "lifecycleCut"))
        return CaptureAttemptIdentity(
            map.string("attemptId"), map.string("commitId"), map.long("attemptOrdinal"),
            decodeCut(map.getValue("lifecycleCut")),
        )
    }

    private fun decodeCut(value: Any?): CaptureLifecycleCut {
        val map = value.map("lifecycleCut")
        map.exactKeys(setOf("sessionId", "sessionGeneration", "groupId", "groupGeneration", "arSessionId", "viewId", "viewGeneration", "bindingToken", "lifecycleSequence", "operationGeneration"))
        return CaptureLifecycleCut(
            map.string("sessionId"), map.long("sessionGeneration"), map.string("groupId"),
            map.long("groupGeneration"), map.string("arSessionId"), map.string("viewId"),
            map.long("viewGeneration"), map.string("bindingToken"), map.long("lifecycleSequence"),
            map.long("operationGeneration"),
        )
    }

    private fun decodeProfile(value: Any?): CaptureComponentProfile {
        val map = value.map("profile")
        map.exactKeys(setOf("profileId", "requiredComponents", "maximumComponentBytes", "maximumWorkingBytes"))
        val components = (map["requiredComponents"] as? List<*>)
            ?.map { CaptureComponentKind.valueOf((it as? String ?: error("component must be a string")).uppercase()) }
            ?.toSet() ?: error("requiredComponents must be a list")
        require(components == setOf(CaptureComponentKind.JPEG) || components == setOf(CaptureComponentKind.JPEG, CaptureComponentKind.DNG))
        return CaptureComponentProfile(map.string("profileId"), components, map.long("maximumComponentBytes"), map.long("maximumWorkingBytes"))
    }

    private fun decodeReservation(value: Any?): CaptureReservationLiability {
        val map = value.map("reservation")
        map.exactKeys(setOf("memoryBytes", "physicalStoreBytes", "componentEntries", "terminalEntries", "rollbackBytes", "physicallyBacked"))
        return CaptureReservationLiability(
            map.long("memoryBytes"), map.long("physicalStoreBytes"), map.long("componentEntries"),
            map.long("terminalEntries"), map.long("rollbackBytes"), map["physicallyBacked"] as? Boolean
                ?: error("physicallyBacked must be a bool"),
        ).also { require(it.physicallyBacked) }
    }

    private fun Any?.map(name: String): Map<String, Any?> =
        (this as? Map<*, *>)?.entries?.associate { (key, value) ->
            (key as? String ?: error("$name keys must be strings")) to value
        } ?: error("$name must be a map")

    private fun Map<String, Any?>.exactKeys(expected: Set<String>) {
        require(keys == expected) { "Unexpected wire keys: expected=$expected actual=$keys" }
    }
    private fun Map<String, Any?>.string(name: String) = this[name] as? String ?: error("$name must be a string")
    private fun Map<String, Any?>.long(name: String): Long = when (val value = this[name]) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> error("$name must be an integer")
    }
    private fun Map<String, Any?>.digest(name: String): List<Int> =
        (this[name] as? List<*>)?.map { value ->
            val byte = when (value) {
                is Byte -> value.toInt()
                is Short -> value.toInt()
                is Int -> value
                is Long -> value.toInt().takeIf { it.toLong() == value }
                else -> null
            } ?: error("$name must contain integers")
            require(byte in 0..255) { "$name must contain bytes" }
            byte
        }
            ?: error("$name must be a list")
    private fun Map<String, Any?>.doubles(name: String): List<Double> =
        (this[name] as? List<*>)?.map { (it as? Number)?.toDouble() ?: error("$name must contain numbers") }
            ?: error("$name must be a list")
}
