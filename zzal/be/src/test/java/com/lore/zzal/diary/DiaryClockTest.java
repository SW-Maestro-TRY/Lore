package com.lore.zzal.diary;
import com.lore.zzal.PetFixture;
import com.lore.zzal.pet.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
class DiaryClockTest {
    private Instant at(String s){return LocalDateTime.parse("2026-10-16T"+s).atZone(ZzalRules.ZONE).toInstant();}
    private ZzalPet pet(Instant now,boolean tutorial){
        var p=PetFixture.hatching(1L,"친구","원문","image",now);p.markAlive("sheet","identity",now);
        if(!tutorial)p.skipTutorial(now);return p;
    }
    @Test void fourHourBoundaryAndSameAbsenceOnlyOnce(){
        var p=pet(at("10:00:00"),false);p.startDiaryAbsence(at("10:00:00"));
        p.settle(at("13:59:59"));assertNull(p.getDiaryOpportunityAt());
        p.settle(at("14:00:00"));assertEquals(at("14:00:00"),p.getDiaryOpportunityAt());
        p.consumeDiaryOpportunity();p.settle(at("22:00:00"));assertEquals(at("14:00:00"),p.getDiaryOpportunityAt());
    }
    @Test void splitSettlementAndPollVisitsDoNotChangeAbsence(){
        var a=pet(at("10:00:00"),false);var b=pet(at("10:00:00"),false);
        a.startDiaryAbsence(at("10:00:00"));b.startDiaryAbsence(at("10:00:00"));
        a.settle(at("14:00:00"));
        for(int i=1;i<=240;i++){Instant t=at("10:00:00").plusSeconds(60L*i);b.settle(t);b.visit(t);}
        assertEquals(a.getDiaryOpportunityAt(),b.getDiaryOpportunityAt());
    }
    @Test void returnEndsRemainderAndDuplicateDepartureDoesNotMoveStart(){
        var p=pet(at("10:00:00"),false);p.startDiaryAbsence(at("10:00:00"));p.startDiaryAbsence(at("11:00:00"));
        assertEquals(at("10:00:00"),p.getDiaryAbsenceStartedAt());p.settle(at("13:00:00"));p.endDiaryAbsence();
        p.startDiaryAbsence(at("13:00:00"));p.settle(at("14:00:00"));assertNull(p.getDiaryOpportunityAt());assertEquals(3600,p.getDiaryAbsenceAwakeSec());
    }
    @Test void automaticNightSleepIsExcludedWithinSameAbsence(){
        Instant start=at("21:00:00");var p=pet(start,false);p.startDiaryAbsence(start);
        Instant next=at("12:00:00").plus(Duration.ofDays(1));p.settle(next.minusSeconds(1));assertNull(p.getDiaryOpportunityAt());
        p.settle(next);assertEquals(next,p.getDiaryOpportunityAt());assertEquals(14400,p.getDiaryAbsenceAwakeSec());
    }
    @Test void manualNightSleepAndEarlyWakeUseActualSegments(){
        var q=pet(at("19:00:00"),false);q.startDiaryAbsence(at("19:00:00"));
        q.sleep(at("19:00:00"));Instant seven=at("07:00:00").plus(Duration.ofDays(1));
        q.settle(seven);q.wake(seven);q.settle(seven.plusSeconds(14399));assertNull(q.getDiaryOpportunityAt());
        q.settle(seven.plusSeconds(14400));assertEquals(seven.plusSeconds(14400),q.getDiaryOpportunityAt());
    }
    @Test void tutorialAndTravelNeverAccumulate(){
        var tutorial=pet(at("10:00:00"),true);tutorial.startDiaryAbsence(at("10:00:00"));tutorial.settle(at("15:00:00"));
        assertEquals(0,tutorial.getDiaryAbsenceAwakeSec());assertNull(tutorial.getDiaryOpportunityAt());
        var travel=pet(at("10:00:00"),false);ReflectionTestUtils.setField(travel,"tripStartedAt",at("10:00:00"));
        travel.startDiaryAbsence(at("10:00:00"));travel.settle(at("15:00:00"));assertEquals(0,travel.getDiaryAbsenceAwakeSec());
    }
}
