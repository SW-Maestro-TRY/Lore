package com.lore.zzal.pet;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NightAttendancePrecisionTest {
 private final Instant start=Instant.parse("2026-10-22T12:00:00Z");
 private ZzalPet pet(){var p=ZzalPet.draft(2L,"images/synthetic",start.minusSeconds(300));p.markAlive("sheet","",start);return p;}
 private ZzalPet night(Instant wake,boolean manual){var p=pet();p.devSleep(SleepKind.NIGHT,start,manual);p.devWake(wake,manual);return p;}
 @Test void manualNightCanonicalizesOnlyAttendanceCycleBeforeObservation(){var at=Instant.parse("2026-10-23T22:00:00.426810283Z");var p=night(at,true);assertEquals(Instant.parse("2026-10-23T22:00:00.426810Z"),p.getAttendanceNightCycleAt());assertEquals(at,p.getWokeAt());assertEquals(426810283,at.getNano());assertFalse(p.isSleeping());assertFalse(p.isOverslept());}
 @Test void automaticNightUsesSamePrecisionAndKeepsOriginalWakeTimestamp(){var at=Instant.parse("2026-10-23T22:00:00.999999999Z");var p=night(at,false);assertEquals(at.truncatedTo(ChronoUnit.MICROS),p.getAttendanceNightCycleAt());assertEquals(at,p.getWokeAt());assertTrue(p.isOverslept());}
 @Test void microAndSecondBoundariesNeverRoundAttendanceIntoNextSecond(){for(int nanos:new int[]{0,1,499,500,999,1000,999999499,999999500,999999999}){var at=Instant.ofEpochSecond(1792792800L,nanos);var p=night(at,true);assertEquals(at.truncatedTo(ChronoUnit.MICROS),p.getAttendanceNightCycleAt());assertEquals(at.getEpochSecond(),p.getAttendanceNightCycleAt().getEpochSecond());assertEquals(0,p.getAttendanceNightCycleAt().getNano()%1000);assertEquals(at,p.getWokeAt());}}
 @Test void napKeepsExistingNightCycleAndWakeTimestamp(){var at=Instant.parse("2026-10-23T22:00:00.426810283Z");var p=night(at,true);var cycle=p.getAttendanceNightCycleAt();p.devSleep(SleepKind.NAP,at.plusSeconds(600),true);p.devWake(at.plusSeconds(900),true);assertEquals(cycle,p.getAttendanceNightCycleAt());assertEquals(at,p.getWokeAt());}
 @Test void wakingAnAwakePetDoesNotCreateAnotherCycle(){var at=Instant.parse("2026-10-23T22:00:00.426810283Z");var p=night(at,true);var cycle=p.getAttendanceNightCycleAt();p.devWake(at.plusNanos(1),false);assertEquals(cycle,p.getAttendanceNightCycleAt());assertEquals(at,p.getWokeAt());}
}
