package com.lore.zzal.pet;
import com.lore.zzal.PetFixture;
import com.lore.zzal.attendance.AttendanceService;
import com.lore.zzal.pet.persona.FirstMeeting;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class FirstIntroV4ServiceTest {
    final Instant now=Instant.parse("2026-10-16T03:00:00Z");
    final ZzalPet pet=PetFixture.hatching(1L,"시온",null,"k",now.minusSeconds(60));
    final PetService service=mock(PetService.class,CALLS_REAL_METHODS);
    final AttendanceService attendance=mock(AttendanceService.class);
    FirstIntroV4ServiceTest(){
        pet.markAlive("s","identity",now);pet.beginFirstMeeting();pet.advanceFirstMeeting(FirstMeeting.State.COMPLETE);
        doReturn(pet).when(service).alive(1L,10L,now);
        doAnswer(inv->{((Runnable)inv.getArgument(1)).run();return new PetService.Action(pet,List.of());})
            .when(service).withUnlockDiff(any(),any());
        ReflectionTestUtils.setField(service,"attendanceService",attendance);
        ZzalPetRepository repository=mock(ZzalPetRepository.class);
        when(repository.findByIdForUpdate(10L)).thenReturn(java.util.Optional.of(pet));
        ReflectionTestUtils.setField(service,"petRepository",repository);
    }
    @Test void attendanceSeenRequiresItsStageAndPreparesPreviewExactlyOnce(){
        assertThatThrownBy(()->service.tutorialSeen(1L,10L,FirstIntro.Step.ATTENDANCE_INTRO,now))
            .isInstanceOf(com.lore.common.exception.BusinessException.class);
        pet.devSetFirstIntro(4,FirstIntro.Step.ATTENDANCE_INTRO,now);
        service.tutorialSeen(1L,10L,FirstIntro.Step.ATTENDANCE_INTRO,now);
        assertThat(pet.introAt(FirstIntro.Step.FINISH_NOTICE)).isTrue();
        service.tutorialSeen(1L,10L,FirstIntro.Step.ATTENDANCE_INTRO,now);
        verify(attendance,times(1)).observe(pet,now,false);
        assertThatThrownBy(()->service.tutorialSeen(1L,10L,FirstIntro.Step.ALBUM,now))
            .isInstanceOf(com.lore.common.exception.BusinessException.class);
    }
    @Test void finishAndClosingReviewAreIdempotentWithoutOldExtraCourses(){
        pet.devSetFirstIntro(4,FirstIntro.Step.ATTENDANCE_INTRO,now);
        assertThatThrownBy(()->service.tutorialDone(1L,10L,now)).isInstanceOf(com.lore.common.exception.BusinessException.class);
        service.tutorialSeen(1L,10L,FirstIntro.Step.ATTENDANCE_INTRO,now);
        service.tutorialDone(1L,10L,now);service.tutorialDone(1L,10L,now);
        assertThat(pet.introAt(FirstIntro.Step.REVIEW_SEEN)).isTrue();
        verify(attendance,times(1)).observe(pet,now,true);
        service.tutorialSeen(1L,10L,FirstIntro.Step.REVIEW_SEEN,now);
        service.tutorialSeen(1L,10L,FirstIntro.Step.REVIEW_SEEN,now);
        assertThat(pet.introAt(FirstIntro.Step.COMPLETE)).isTrue();
    }
    @Test void repeatedSleepAndWakeReceiptsDoNotReplayTheActions(){
        pet.devSetFirstIntro(4,FirstIntro.Step.NAP_WAKE,now);
        service.sleep(1L,10L,FirstIntro.Step.NAP_SLEEP,now);
        assertThat(pet.getSleepKind()).isEqualTo(SleepKind.NAP);
        pet.wake(now);
        Object count=ReflectionTestUtils.getField(pet,"sleepWakeCount");
        service.wake(1L,10L,FirstIntro.Step.NAP_WAKE,now);
        assertThat(ReflectionTestUtils.getField(pet,"sleepWakeCount")).isEqualTo(count);
        assertThat(pet.introAt(FirstIntro.Step.ATTENDANCE_INTRO)).isTrue();
    }
    @Test void nightEndingRemainsAwakeUntilReviewReceiptAndRetriesDoNotRepeatSleep(){
        Instant night=Instant.parse("2026-10-16T15:00:00Z");
        pet.devSetFirstIntro(4,FirstIntro.Step.FINISH_NOTICE,now);
        pet.startClock(night);
        assertThat(pet.isSleeping()).isFalse();
        assertThat(pet.firstIntroChatAvailable()).isFalse();
        pet.settle(night.plusSeconds(3600));
        assertThat(pet.isSleeping()).isFalse();
        assertThatThrownBy(()->service.care(1L,10L,CareAction.FEED,now))
            .isInstanceOf(com.lore.common.exception.BusinessException.class);
        pet.finishFirstIntroReview(night.plusSeconds(3600));
        assertThat(pet.firstNightChatGraceActive(night.plusSeconds(3600))).isTrue();
        pet.settle(pet.getFirstNightChatDeadlineAt());
        assertThat(pet.getSleepKind()).isEqualTo(SleepKind.NIGHT);
        Object slept=ReflectionTestUtils.getField(pet,"sleptAt");
        pet.finishFirstIntroReview(night.plusSeconds(3601));
        assertThat(ReflectionTestUtils.getField(pet,"sleptAt")).isEqualTo(slept);
    }
    @Test void returningAfterSevenFinishesInDayWithoutBackdatedNight(){
        Instant night=Instant.parse("2026-10-16T15:00:00Z");
        Instant morning=Instant.parse("2026-10-16T22:01:00Z");
        pet.devSetFirstIntro(4,FirstIntro.Step.FINISH_NOTICE,now);pet.startClock(night);
        pet.settle(morning);pet.finishFirstIntroReview(morning);
        assertThat(pet.introAt(FirstIntro.Step.COMPLETE)).isTrue();
        assertThat(pet.isSleeping()).isFalse();
        assertThat(pet.firstIntroChatAvailable()).isTrue();
    }
}
