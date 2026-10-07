package com.uhg0.ar_flutter_plugin_2.diagnostics

import org.junit.Assert.*
import org.junit.Test

class StillCaptureMetadataTest {
    private fun owner(release:()->Unit={})=StillCaptureMetadata.create(true,{it==6400L},release)!!.also {it.openSession(7)}
    private fun records(owner:StillCaptureMetadata)=owner.receipt()["records"] as List<Map<String,Any?>>
    @Test fun `disabled and unfunded metadata never allocate an owner or reserve twice`() {
        var reserves=0;var releases=0
        assertNull(StillCaptureMetadata.create(false,{reserves++;true},{releases++}))
        assertEquals(0,reserves)
        assertNull(StillCaptureMetadata.create(true,{reserves++;false},{releases++}))
        assertEquals(1,reserves);assertEquals(0,releases)
    }
    @Test fun `absent metadata stays null while actual false zero and signed integers survive packing`() {
        val o=owner();val t=o.submitted(7,1,9,0,10)
        o.put(t,28,0);o.put(t,22,null);o.put(t,46,-20);o.put(t,47,Int.MAX_VALUE.toLong());o.put(t,42,6400)
        o.end(t)
        val r=records(o).single();assertEquals(0L,r["requestZsl"]);assertNull(r["requestAeMode"])
        assertEquals(-20L,r["cropLeft"]);assertEquals(Int.MAX_VALUE.toLong(),r["cropTop"]);assertEquals(6400L,r["resultSensitivity"])
        assertTrue((r["missingFields"] as List<*>).contains("requestAeMode"));o.close()
    }
    @Test fun `image close before result preserves observed order and exact sensor association`() {
        val o=owner();val t=o.submitted(7,1,9,0,10);o.put(t,1,100);o.event(t,51);o.end(t)
        val image=o.image(7,100,110,4128,3096,256);assertTrue(image!=0L);o.end(image)
        val close=o.imageCloseBegin(7,100);o.put(close,12,120);o.event(close,54);o.end(close)
        val result=o.begin(7,1,9,0);o.put(result,5,100);o.put(result,4,130);o.event(result,52);o.end(result)
        val r=records(o).single();assertEquals(4128L,r["imageWidth"]);assertEquals(3096L,r["imageHeight"])
        assertTrue((r["closeEventOrdinal"] as Long)<(r["completedEventOrdinal"] as Long))
        assertEquals("unique matching started/result sensor timestamp",r["imageAssociation"]);o.close()
    }
    @Test fun `completion before start stays explicit and unknown image timestamp is never guessed`() {
        val o=owner();val t=o.submitted(7,2,9,0,10);o.put(t,5,100);o.event(t,52);o.end(t)
        assertEquals(0L,o.image(7,101,110,4128,3096,256));assertEquals(1L,o.receipt()["unmatchedImageAssociation"])
        val start=o.begin(7,2,9,0);o.put(start,1,100);o.event(start,51);o.end(start)
        val r=records(o).single();assertTrue((r["completedEventOrdinal"] as Long)<(r["startedEventOrdinal"] as Long));assertNull(r["imageTimestampNs"]);o.close()
    }
    @Test fun `camera and measurement generations reject stale callbacks without mixing records`() {
        val o=owner();val old=o.submitted(7,1,9,0,10);o.end(old)
        assertTrue(o.owns(7,1,9,0));assertFalse(o.owns(7,2,9,0));o.openSession(8)
        assertFalse(o.owns(7,1,9,0))
        assertEquals(0L,o.begin(7,1,9,0));val current=o.submitted(8,1,9,0,20);o.end(current)
        assertEquals(8L,records(o).single()["cameraEpoch"])
        val direct=o.submitted(8,2,10,0,30);o.put(direct,1,100);o.end(direct)
        val image=o.image(8,100,110,4128,3096,256);o.end(image)
        val closed=o.imageCloseBegin(8,100);o.put(closed,12,120);o.end(closed)
        assertTrue(o.owns(8,2,10,0));o.resetMeasurements()
        assertFalse(o.owns(8,2,10,0))
        assertEquals(0L,o.begin(8,1,9,0));assertFalse(o.owns(8,1,9,0));assertTrue(records(o).isEmpty());o.close()
    }
    @Test fun `fixed tail overwrites oldest only and reports loss instead of growing`() {
        val o=owner();for(n in 1L..20L){val t=o.submitted(7,1,n,0,n);o.end(t)}
        assertEquals(16,records(o).size);assertEquals(4L,o.receipt()["overwritten"])
        assertEquals((5L..20L).toList(),records(o).map{it["generation"]})
        assertEquals(48,o.receipt()["scalarSlotsPerRecord"]);assertEquals(6400L,o.receipt()["ownedPortableBytes"]);o.close()
    }
    @Test fun `closing preserves fixed funding until outstanding callback ends once`() {
        var releases=0;val o=owner{releases++};val t=o.submitted(7,1,9,0,10)
        o.close();o.close();assertEquals(0,releases);assertEquals(6400L,o.receipt()["ownedPortableBytes"])
        o.end(t);assertEquals(1,releases);assertEquals(0L,o.receipt()["ownedPortableBytes"])
        assertEquals(0L,o.begin(7,1,9,0));assertEquals(1,releases)
    }
    @Test fun `request and result fields failure and duplicate terminal values are distinguishable`() {
        val o=owner();val t=o.submitted(7,1,9,0,10);o.put(t,18,7);o.put(t,19,30);o.put(t,20,30);o.put(t,21,30)
        o.put(t,50,1);o.put(t,4,20);o.put(t,4,99);o.end(t)
        val r=records(o).single();assertEquals(7L,r["requestFpsLower"]);assertEquals(30L,r["resultFpsLower"])
        assertEquals(1L,r["failureReason"]);assertEquals(20L,r["completedArrivalElapsedNs"]);assertEquals(1L,o.receipt()["duplicateEvents"]);o.close()
    }
}
