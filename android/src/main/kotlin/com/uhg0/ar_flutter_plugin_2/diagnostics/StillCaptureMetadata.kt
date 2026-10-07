package com.uhg0.ar_flutter_plugin_2.diagnostics

/** Fixed scalar tail. Portable accounting only; actual VM/codec overhead is not certified. */
internal class StillCaptureMetadata private constructor(private val release: () -> Unit) {
    companion object {
        const val RECORDS = 16
        const val SLOTS = 48
        const val PORTABLE_BYTES = 6400L
        const val MANUAL = 1
        const val DIRECT = 2
        fun create(enabled: Boolean, reserve: (Long) -> Boolean, release: () -> Unit): StillCaptureMetadata? {
            if (!enabled || !reserve(PORTABLE_BYTES)) return null
            return try { StillCaptureMetadata(release) } catch (_: OutOfMemoryError) { release(); null }
        }
        val names = arrayOf("submitElapsedNs", "startedSensorNs", "startedFrameNumber", "startedArrivalElapsedNs",
            "completedArrivalElapsedNs", "resultSensorNs", "resultFrameNumber", "exposureNs", "frameDurationNs", "rollingSkewNs",
            "imageTimestampNs", "imageArrivalElapsedNs", "imageCloseElapsedNs", "imageWidth", "imageHeight", "imageFormat",
            "requestCaptureIntent", "resultCaptureIntent", "requestFpsLower", "requestFpsUpper", "resultFpsLower", "resultFpsUpper",
            "requestAeMode", "resultAeMode", "requestAfMode", "resultAfMode", "requestAwbMode", "resultAwbMode",
            "requestZsl", "resultZsl", "requestEdge", "resultEdge", "requestNoiseReduction", "resultNoiseReduction",
            "requestAberration", "resultAberration", "requestTonemap", "resultTonemap", "requestShading", "resultShading",
            "requestJpegQuality", "resultJpegQuality", "resultSensitivity", "resultAeState", "resultAfState", "resultAwbState",
            "cropLeft", "cropTop", "cropRight", "cropBottom", "failureReason", "startedEventOrdinal", "completedEventOrdinal",
            "imageEventOrdinal", "closeEventOrdinal", "imageCloseFailed", "submitFailed")
        private fun slot(field: Int): Int = when {
            field <= 12 -> 6 + field
            field <= 14 -> 19
            field == 15 || field == 50 -> 20
            field <= 49 -> 21 + (field - 16) / 2
            field <= 54 -> 38 + field - 51
            else -> 42
        }
        private fun high(field: Int) = when(field) { 13,15,55 -> true; 14,50,56 -> false; else -> (field - 16) % 2 == 0 }
        private fun wide(field: Int) = field <= 12 || field in 51..54
    }
    private val values = LongArray(RECORDS * SLOTS)
    private var head = 0
    private var size = 0
    private var sessionGeneration = 0L
    private var sensorClockSource: Int? = null
    private var measurementEpoch = 1L
    private var nextTicket = 0L
    private var eventOrdinal = 0L
    private var overwritten = 0L
    private var rejected = 0L
    private var missingImageAssociation = 0L
    private var duplicateEvents = 0L
    private var probeErrors = 0L
    private var closing = false
    private var released = false
    private var callbacks = 0
    @Volatile var active = true; private set
    @Synchronized fun openSession(generation: Long, actualSensorClockSource: Int? = null) {
        require(generation > 0)
        if (!active) return
        sessionGeneration = generation
        sensorClockSource = actualSensorClockSource
        resetLocked()
    }
    @Synchronized fun resetMeasurements() { if (active) resetLocked() }
    private fun resetLocked() { values.fill(0); head=0; size=0; measurementEpoch++; overwritten=0; rejected=0; missingImageAssociation=0; duplicateEvents=0 }
    @Synchronized fun submitted(camera: Long, kind: Int, generation: Long, ordinal: Int, elapsed: Long): Long {
        if (!active || camera != sessionGeneration || generation <= 0 || kind !in MANUAL..DIRECT) { rejected++; return 0 }
        if (find(camera,kind,generation,ordinal) >= 0) { duplicateEvents++; return 0 }
        val i = if (size < RECORDS) { val x=(head+size)%RECORDS; size++; x } else { val x=head; head=(head+1)%RECORDS; overwritten++; x }
        val offset=i*SLOTS
        java.util.Arrays.fill(values,offset,offset+SLOTS,0)
        values[offset]=kind.toLong(); values[offset+1]=generation; values[offset+2]=ordinal.toLong()
        values[offset+3]=camera; values[offset+4]=measurementEpoch; values[offset+43]=++nextTicket
        set(i,0,elapsed)
        callbacks++
        return values[offset+43]
    }
    @Synchronized fun begin(camera: Long, kind: Int, generation: Long, ordinal: Int): Long {
        val i=if(active) find(camera,kind,generation,ordinal) else -1
        if(i<0) { rejected++; return 0 }
        callbacks++; return values[i*SLOTS+43]
    }
    /** Camera2 callback ownership query only; no frame-thread access or clock read. */
    @Synchronized fun owns(camera: Long, kind: Int, generation: Long, ordinal: Int): Boolean =
        active && find(camera, kind, generation, ordinal) >= 0

    private fun find(camera:Long,kind:Int,generation:Long,ordinal:Int):Int {
        if(camera!=sessionGeneration) return -1
        for(n in 0 until size) { val i=(head+n)%RECORDS; val o=i*SLOTS
            if(values[o]==kind.toLong() && values[o+1]==generation && values[o+2]==ordinal.toLong() && values[o+3]==camera && values[o+4]==measurementEpoch) return i }
        return -1
    }
    private fun ticketIndex(ticket:Long):Int {
        for(n in 0 until size) { val i=(head+n)%RECORDS; if(values[i*SLOTS+43]==ticket && values[i*SLOTS+4]==measurementEpoch) return i }
        return -1
    }
    @Synchronized fun put(ticket:Long,field:Int,value:Long?) {
        if(value==null || ticket==0L) return
        require(field in names.indices)
        val i=ticketIndex(ticket)
        if(!active || i<0) { rejected++; return }
        if((values[i*SLOTS+5] and (1L shl field))!=0L) { duplicateEvents++; return }
        set(i,field,value)
    }
    private fun set(i:Int,field:Int,value:Long) {
        val o=i*SLOTS; val s=o+slot(field)
        if(wide(field)) values[s]=value
        else if(high(field)) values[s]=(values[s] and 0xffffffffL) or ((value and 0xffffffffL) shl 32)
        else values[s]=(values[s] and -0x100000000L) or (value and 0xffffffffL)
        values[o+5]=values[o+5] or (1L shl field)
    }
    private fun get(i:Int,field:Int):Long? {
        val o=i*SLOTS
        if((values[o+5] and (1L shl field))==0L) return null
        val v=values[o+slot(field)]
        return if(wide(field)) v else if(high(field)) (v shr 32).toInt().toLong() else v.toInt().toLong()
    }
    @Synchronized fun event(ticket:Long,field:Int) { put(ticket,field,++eventOrdinal) }
    @Synchronized fun image(camera:Long,timestamp:Long,arrival:Long,width:Int,height:Int,format:Int):Long {
        if(!active || camera!=sessionGeneration) { rejected++; return 0 }
        var found=-1
        for(n in 0 until size) { val i=(head+n)%RECORDS
            if(get(i,1)==timestamp || get(i,5)==timestamp) { if(found>=0) { missingImageAssociation++; return 0 }; found=i } }
        if(found<0) { missingImageAssociation++; return 0 }
        val t=values[found*SLOTS+43]; callbacks++
        put(t,10,timestamp);put(t,11,arrival);put(t,13,width.toLong());put(t,14,height.toLong());put(t,15,format.toLong());event(t,53)
        return t
    }
    @Synchronized fun imageCloseBegin(camera:Long,timestamp:Long):Long {
        if(!active || camera!=sessionGeneration) { rejected++;return 0 }
        for(n in 0 until size) {val i=(head+n)%RECORDS
            if(get(i,10)==timestamp) { callbacks++;return values[i*SLOTS+43] } }
        missingImageAssociation++;return 0
    }
    fun end(ticket:Long) {
        if(ticket==0L) return
        var free=false
        synchronized(this) { check(callbacks>0);callbacks--;if(closing && callbacks==0 && !released) {released=true;free=true} }
        if(free) release()
    }
    fun close() {
        var free=false
        synchronized(this) { active=false;closing=true;if(callbacks==0 && !released) {released=true;free=true} }
        if(free) release()
    }
    @Synchronized fun probeError() { probeErrors++ }
    @Synchronized fun receipt():Map<String,Any?> = mapOf(
        "declaredPortableBytes" to PORTABLE_BYTES,"ownedPortableBytes" to if(released) 0L else PORTABLE_BYTES,
        "actualVmAndCodecBytes" to null,"byteQualification" to "portable fixed scalar model; not heap-byte certification",
        "capacity" to RECORDS,"scalarSlotsPerRecord" to SLOTS,"recordCount" to size,"overwritten" to overwritten,
        "rejected" to rejected,"probeErrors" to probeErrors,"duplicateEvents" to duplicateEvents,"unmatchedImageAssociation" to missingImageAssociation,
        "effective" to active,"activeCallbacks" to callbacks,"cameraEpoch" to sessionGeneration,"measurementEpoch" to measurementEpoch,
        "sensorClockSource" to sensorClockSource,"arrivalClock" to "elapsedRealtime",
        "clock" to "elapsedRealtime; sensor clock qualification inherited from camera characteristics; no absolute-age claim",
        "missingFieldMeaning" to "not present in actual request/result/event; unsupported versus unset is unknown",
        "records" to (0 until size).map {n -> val i=(head+n)%RECORDS; val o=i*SLOTS
            linkedMapOf<String,Any?>("tagKind" to if(values[o]==MANUAL.toLong()) "manual" else "direct", "generation" to values[o+1],"ordinal" to values[o+2],"cameraEpoch" to values[o+3],"measurementEpoch" to values[o+4],"ticket" to values[o+43]).also { row ->
                names.forEachIndexed {f,name -> row[name]=get(i,f)}
                row["missingFields"]=names.filterIndexed {f,_ -> get(i,f)==null}
                row["imageAssociation"] = if(get(i,10)!=null) "unique matching started/result sensor timestamp" else "unavailable"
            }
        })
}
