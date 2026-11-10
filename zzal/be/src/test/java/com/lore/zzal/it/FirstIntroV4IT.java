package com.lore.zzal.it;

import com.lore.zzal.pet.*;
import com.lore.zzal.pet.persona.FirstMeeting;
import com.lore.zzal.game.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@ZzalIntegrationTest
class FirstIntroV4IT extends ZzalItSupport {
    @Autowired GameService games;
    record Room(Long user, Long pet, Instant now) { String path(){return "/api/zzal/v1/me/pets/"+pet;} }
    Room fresh(Instant now){
        Long user=newUserId();
        Long id=transactions.execute(tx->{var p=ZzalPet.draft(user,newUploadedImageKey(user),now);
            p.character("아이",null,null,"현대",null,null,now);p.markAlive("sheet","identity",now);
            p.beginFirstMeeting();p.advanceFirstMeeting(FirstMeeting.State.COMPLETE);
            return petRepository.save(p).getId();});
        motionSeeder.seed(id,now);pinClock(id,now);return new Room(user,id,now);
    }
    ZzalPet pet(Room r){return petRepository.findById(r.pet).orElseThrow();}
    void key(Room r,FirstIntro.Step key){assertThat(pet(r).introAt(key)).isTrue();}
    void seen(Room r,FirstIntro.Step key)throws Exception{
        var response=postAs(r.user,r.path()+"/tutorial/seen",Map.of("topic",key.name()));
        assertThat(response.getResponse().getStatus()).as(response.getResponse().getContentAsString()).isEqualTo(200);
    }
    void seven(Room r){
        assertThat(pet(r).getFirstIntroVersion()).isEqualTo(4);
        for(var action:new CareAction[]{CareAction.FEED,CareAction.SNACK,CareAction.CLEAN,CareAction.BATH})
            petService.care(r.user,r.pet,action,FirstIntro.Step.valueOf(action.name()),Instant.now());
        key(r,FirstIntro.Step.GAME);
        var game=games.start(r.user,r.pet,GameKind.LEFT_RIGHT,FirstIntro.Step.GAME,Instant.now()).game();
        for(int i=0;i<5;i++) if(games.guess(r.user,r.pet,game.getId(),'L',Instant.now()).game().isFinished())break;
        key(r,FirstIntro.Step.NAP_SLEEP);
        petService.sleep(r.user,r.pet,FirstIntro.Step.NAP_SLEEP,Instant.now());
        petService.sleep(r.user,r.pet,FirstIntro.Step.NAP_SLEEP,Instant.now());
        key(r,FirstIntro.Step.NAP_WAKE);assertThat(pet(r).getSleepKind()).isEqualTo(SleepKind.NAP);
        petService.wake(r.user,r.pet,FirstIntro.Step.NAP_WAKE,Instant.now());
        petService.wake(r.user,r.pet,FirstIntro.Step.NAP_WAKE,Instant.now());
        key(r,FirstIntro.Step.ATTENDANCE_INTRO);assertThat(pet(r).getClockStartedAt()).isNull();
        assertThat(pet(r).getAttendanceNightCycleAt()).isNull();
    }
    @ParameterizedTest @ValueSource(ints={12,23}) void freshRegistrationSevenActionsReviewAndNightBoundary(int hour)throws Exception{
        var r=fresh(kstToday(hour,0));seven(r);seen(r,FirstIntro.Step.ATTENDANCE_INTRO);
        key(r,FirstIntro.Step.FINISH_NOTICE);
        var done=postAs(r.user,r.path()+"/tutorial/done",null);
        assertThat(done.getResponse().getStatus()).as(done.getResponse().getContentAsString()).isEqualTo(200);
        key(r,FirstIntro.Step.REVIEW_SEEN);assertThat(pet(r).isSleeping()).isFalse();
        Instant started=pet(r).getClockStartedAt();
        getJson(r.user,r.path());postAs(r.user,r.path()+"/tutorial/done",null);
        assertThat(pet(r).isSleeping()).isFalse();assertThat(pet(r).getClockStartedAt()).isEqualTo(started);
        assertThat(pet(r).firstIntroChatAvailable()).isFalse();
        assertThatThrownBy(()->petService.care(r.user,r.pet,CareAction.FEED,Instant.now()))
            .isInstanceOf(com.lore.common.exception.BusinessException.class);
        assertThatThrownBy(()->games.start(r.user,r.pet,GameKind.LEFT_RIGHT,Instant.now()))
            .isInstanceOf(com.lore.common.exception.BusinessException.class);
        seen(r,FirstIntro.Step.REVIEW_SEEN);key(r,FirstIntro.Step.COMPLETE);
        assertThat(pet(r).isSleeping()).isFalse();
        assertThat(pet(r).getFirstNightChatDeadlineAt()).isEqualTo(hour==23?pet(r).getSettledAt().plusSeconds(1800):null);
        Object slept=pet(r).getSleptAt();seen(r,FirstIntro.Step.REVIEW_SEEN);
        assertThat(pet(r).getSleptAt()).isEqualTo(slept);
        assertThat(pet(r).getClockStartedAt()).isEqualTo(started);
        assertThat(jdbc.queryForObject("select count(*) from zzal_awake_visit where pet_id=?",Integer.class,r.pet)).isEqualTo(1);
    }
    @Test void pendingReviewResumesAfterSevenWithoutBackdatedNight()throws Exception{
        var r=fresh(kstToday(23,0));seven(r);seen(r,FirstIntro.Step.ATTENDANCE_INTRO);
        petService.tutorialDone(r.user,r.pet,Instant.now());
        pinClock(r.pet,r.now.plusSeconds(8*3600+60));getJson(r.user,r.path());
        key(r,FirstIntro.Step.REVIEW_SEEN);assertThat(pet(r).isSleeping()).isFalse();
        seen(r,FirstIntro.Step.REVIEW_SEEN);assertThat(pet(r).isSleeping()).isFalse();
        assertThat(pet(r).getAttendanceNightCycleAt()).isNull();
    }
}
