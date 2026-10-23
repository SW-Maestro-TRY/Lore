package com.lore.zzal.diary;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
class DiaryEntryTest {
    private final Instant now=Instant.parse("2026-10-16T05:00:00Z");
    private DiaryEntry reserve(){return DiaryEntry.reserve(1L,"1:absence",now,now,"ja","{\"sourceType\":\"DIARY_FICTION\",\"persona\":\"原文\"}");}
    @Test void immutableOriginalAndFirstReadTimestamp(){
        var e=reserve();assertTrue(e.claim("claim",now));
        assertTrue(e.complete("claim","窓辺で本を開いた。少し落ち着いた。","stub:1",now.plusSeconds(1)));
        String hash=e.bodyHash();e.read(now.plusSeconds(2));e.read(now.plusSeconds(3));
        assertEquals(now.plusSeconds(2),e.readAt());assertEquals("ja",e.language());assertFalse(e.occupiesSlot());
        assertFalse(e.complete("claim","overwrite","stub:2",now.plusSeconds(4)));assertEquals(hash,e.bodyHash());
        assertTrue(e.snapshot().contains("原文"));
    }
    @Test void claimOnceAndStaleCompletionIgnored(){
        var e=reserve();assertTrue(e.claim("one",now));assertFalse(e.claim("two",now));
        assertFalse(e.complete("two","wrong","receipt",now));assertEquals(DiaryEntry.Status.GENERATING,e.status());
        assertTrue(e.complete("one","A small afternoon by the window.","stub:one",now));
        assertTrue(e.occupiesSlot());
    }
    @Test void unknownExecutionIsNeverReclaimed(){
        var e=reserve();e.claim("one",now);e.uncertain("one","timeout");
        assertEquals(DiaryEntry.Status.UNCERTAIN,e.status());assertTrue(e.occupiesSlot());
        assertFalse(e.claim("retry",now));assertFalse(e.complete("one","late reply","late",now));
    }
    @Test void blankAndStructuredJsonNeverBecomeVisible(){
        for(String body:new String[]{" ","{}","[]"}){
            var e=reserve();e.claim("one",now);assertFalse(e.complete("one",body,"stub",now));
            assertEquals(DiaryEntry.Status.FAILED,e.status());assertNull(e.body());assertTrue(e.occupiesSlot());
            assertThrows(IllegalStateException.class,()->e.read(now));
        }
    }
    @Test void bracketedProseIsPreservedAsOriginalText(){
        for(String body:new String[]{"[로컬 합성 검증 일지] 창가에서 잠시 쉬었다.","[1] 창가에서 잠시 쉬었다."}){
            var e=reserve();e.claim("one",now);assertTrue(e.complete("one",body,"LOCAL_FIXTURE",now));assertEquals(body,e.body());
        }
    }
    @Test void readDoesNotGenerateOrRewrite(){
        var e=reserve();assertThrows(IllegalStateException.class,()->e.read(now));
        assertEquals(DiaryEntry.Status.RESERVED,e.status());assertNull(e.body());
    }
}
