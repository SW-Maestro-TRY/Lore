package com.lore.zzal.it;

import com.lore.zzal.pet.*;
import com.lore.zzal.pet.persona.*;
import com.lore.zzal.game.*;
import com.lore.zzal.chat.line.FakeChatLineClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@ZzalIntegrationTest
class FirstIntroIT extends ZzalItSupport {
    @Autowired GameService games;
    @Autowired FakeChatLineClient llm;
    record Room(Long user,Long pet,Instant now) { String path(){return "/api/zzal/v1/me/pets/"+pet;} }
    private Room room(Instant now,boolean fresh){
        Long user=newUserId();
        Long id=transactions.execute(tx->{var p=ZzalPet.draft(user,newUploadedImageKey(user),now);
            p.character("아이",null,null,"현대",null,null,now);p.markAlive("sheet","identity",now);
            if(fresh){p.recordAuthoredPersona(new AuthoredPersona(1,List.of()));p.beginFirstMeeting();p.devSetFirstIntro(3,FirstIntro.Step.FEED,now);p.advanceFirstMeeting(FirstMeeting.State.COMPLETE);}
            return petRepository.save(p).getId();});
        motionSeeder.seed(id,now);pinClock(id,now);return new Room(user,id,now);
    }
    private Room room(){return room(kstToday(10,0),true);}
    private Room room(Instant now,int version){
        Room r=room(now,true);
        transactions.executeWithoutResult(tx->{var p=petRepository.findByIdForUpdate(r.pet).orElseThrow();ReflectionTestUtils.setField(p,"firstIntroVersion",version);});
        return r;
    }
    private void persistedKey(Room r,FirstIntro.Step expected)throws Exception{
        key(r,expected);
        var detail=getJson(r.user,r.path()).path("data");
        assertThat(detail.path("firstIntro").path("key").asText()).isEqualTo(expected.name());
        assertThat(detail.path("clock").path("clockStartedAt").isNull()).isTrue();
    }
    private ZzalPet pet(Room r){return petRepository.findById(r.pet).orElseThrow();}
    private void key(Room r,FirstIntro.Step key){assertThat(pet(r).getFirstIntroStep()).isEqualTo(pet(r).firstIntroIndex(key));}
    private void care(Room r,CareAction action){petService.care(r.user,r.pet,action,Instant.now());}
    private void seen(Room r,FirstIntro.Step topic)throws Exception{
        var result=postAs(r.user,r.path()+"/tutorial/seen",Map.of("topic",topic.name()));
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
    }
    private void throughCare(Room r){
        care(r,CareAction.FEED);care(r,CareAction.SNACK);assertThat(pet(r).getTrash()).isGreaterThan(0);
        care(r,CareAction.CLEAN);assertThat(pet(r).getFirstIntroStep()).isEqualTo(pet(r).firstIntroIndex(FirstIntro.Step.BATH));
        assertThat(pet(r).getTrash()).isEqualTo(3);care(r,CareAction.BATH);assertThat(pet(r).getTrash()).isZero();
        key(r,FirstIntro.Step.GAME);
    }
    private void throughGame(Room r){
        throughCare(r);var first=games.start(r.user,r.pet,GameKind.LEFT_RIGHT,Instant.now());
        var same=games.start(r.user,r.pet,GameKind.LEFT_RIGHT,Instant.now());
        assertThat(same.game().getId()).isEqualTo(first.game().getId());assertThat(pet(r).getTodayGames()).isEqualTo(1);
        key(r,FirstIntro.Step.GAME);
        for(int i=0;i<5;i++){var reply=games.guess(r.user,r.pet,first.game().getId(),'L',Instant.now());if(reply.game().isFinished())break;}
        key(r,FirstIntro.Step.PET);
    }
    private void throughAlbum(Room r)throws Exception{
        throughGame(r);care(r,CareAction.PET);key(r,FirstIntro.Step.NAP_SLEEP);
        petService.sleep(r.user,r.pet,Instant.now());petService.sleep(r.user,r.pet,Instant.now());
        key(r,FirstIntro.Step.NAP_WAKE);assertThat(pet(r).getSleepKind()).isEqualTo(SleepKind.NAP);
        assertThat(pet(r).getClockStartedAt()).isNull();
        // Re-open a new DB entity, not client state; no five-minute wait is required for mandatory NAP.
        petService.wake(r.user,r.pet,Instant.now());petService.wake(r.user,r.pet,Instant.now());
        key(r,FirstIntro.Step.ALBUM);seen(r,FirstIntro.Step.ALBUM);seen(r,FirstIntro.Step.ALBUM);
        if(pet(r).getFirstIntroVersion()>=3){persistedKey(r,FirstIntro.Step.DIARY_INTRO);seen(r,FirstIntro.Step.DIARY_INTRO);}
        key(r,FirstIntro.Step.CHAT);
    }
    private void chat(Room r,String line)throws Exception{
        var chat=getJson(r.user,r.path()+"/chat").path("data").path("session");
        llm.reply("{\"line\":\""+line+"\",\"motion\":\"reply\"}");
        var result=postAs(r.user,r.path()+"/chat/CONTINUOUS/answer",Map.of("text","안녕","sessionId",chat.path("id").asLong(),
                "expectedRound",chat.path("round").asInt(),"requestId",UUID.randomUUID().toString()));
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
    }
    private void throughFinish(Room r)throws Exception{
        throughAlbum(r);chat(r,"반가워요");key(r,FirstIntro.Step.PERSONALITY);
        seen(r,FirstIntro.Step.PERSONALITY);
        if(pet(r).getFirstIntroVersion()==2){persistedKey(r,FirstIntro.Step.DIARY_INTRO);seen(r,FirstIntro.Step.DIARY_INTRO);}
        key(r,FirstIntro.Step.FINISH_NOTICE);
    }
    @ParameterizedTest @ValueSource(ints={2,3}) void completeSequenceResumesAndStartsClockOnlyOnceWithoutMandatoryShareOrEdit(int version)throws Exception{
        var r=room(kstToday(10,0),version);throughFinish(r);assertThat(pet(r).getClockStartedAt()).isNull();
        var response=postAs(r.user,r.path()+"/tutorial/done",null);assertThat(response.getResponse().getStatus()).isEqualTo(200);
        var intro=body(response).path("data").path("firstIntro");assertThat(intro.path("key").asText()).isEqualTo("REVIEW_SEEN");
        assertThat(intro.path("active").asBoolean()).isFalse();assertThat(intro.path("reviewPending").asBoolean()).isTrue();
        assertThat(jdbc.queryForList("select status from zzal_motion where pet_id=? and seq=101",String.class,r.pet)).doesNotContain("QUEUED","PRACTICING");
        Instant started=pet(r).getClockStartedAt();postAs(r.user,r.path()+"/tutorial/done",null);
        assertThat(pet(r).getClockStartedAt()).isEqualTo(started);seen(r,FirstIntro.Step.REVIEW_SEEN);seen(r,FirstIntro.Step.REVIEW_SEEN);
        key(r,FirstIntro.Step.COMPLETE);assertThat(pet(r).getShares()).isZero();
    }
    @Test void duplicateConcurrentFeedDoesNotConsumeTwice()throws Exception{
        var r=room();int before=pet(r).getFood();var pool=Executors.newFixedThreadPool(2);
        try{var futures=pool.invokeAll(List.of(()->{care(r,CareAction.FEED);return true;},()->{care(r,CareAction.FEED);return true;}));
            for(var f:futures)assertThat(f.get()).isEqualTo(true);
        }finally{pool.shutdownNow();}
        assertThat(pet(r).getFood()).isEqualTo(before-1);assertThat(pet(r).getFeeds()).isEqualTo(1);key(r,FirstIntro.Step.SNACK);
    }
    @Test void futureSeenAndEarlyFinishAreRejectedWithoutChangingProgress()throws Exception{
        var r=room();assertThat(postAs(r.user,r.path()+"/tutorial/seen",Map.of("topic","ALBUM")).getResponse().getStatus()).isEqualTo(409);
        assertThat(postAs(r.user,r.path()+"/tutorial/done",null).getResponse().getStatus()).isEqualTo(409);
        key(r,FirstIntro.Step.FEED);assertThat(pet(r).getClockStartedAt()).isNull();
    }
    @ParameterizedTest @ValueSource(ints={2,3}) void nightBeforeCompletionNeverAutoSleepsAndNapResumeRemainsFrozen(int version)throws Exception{
        var r=room(kstToday(3,30),version);throughGame(r);care(r,CareAction.PET);
        petService.refresh(r.user,r.pet,Instant.now().plus(Duration.ofDays(2)));assertThat(pet(r).isSleeping()).isFalse();
        assertThat(pet(r).getClockStartedAt()).isNull();pinClock(r.pet,r.now);
        petService.sleep(r.user,r.pet,Instant.now());petService.refresh(r.user,r.pet,Instant.now().plus(Duration.ofDays(1)));
        key(r,FirstIntro.Step.NAP_WAKE);assertThat(pet(r).getSleepKind()).isEqualTo(SleepKind.NAP);
        petService.wake(r.user,r.pet,Instant.now().plus(Duration.ofDays(1)));key(r,FirstIntro.Step.ALBUM);
        assertThat(pet(r).isSleeping()).isFalse();assertThat(pet(r).getClockStartedAt()).isNull();
    }
    @ParameterizedTest @ValueSource(ints={2,3}) void finishAt23StartsOneNightAndDoesNotReinitializeExistingUser(int version)throws Exception{
        var r=room(kstToday(23,0),version);throughFinish(r);
        assertThat(getJson(r.user,r.path()).path("data").path("firstIntro").path("completionWillSleep").asBoolean()).isTrue();petService.tutorialDone(r.user,r.pet,Instant.now());
        assertThat(pet(r).getSleepKind()).isEqualTo(SleepKind.NIGHT);var started=pet(r).getClockStartedAt();
        petService.tutorialDone(r.user,r.pet,Instant.now().plusSeconds(2));assertThat(pet(r).getClockStartedAt()).isEqualTo(started);
        var old=room(kstToday(10,0),false);assertThat(pet(old).getFirstIntroStep()).isNull();
        care(old,CareAction.FEED);assertThat(pet(old).getTutorialStep()).isEqualTo(1);assertThat(pet(old).getFirstIntroStep()).isNull();
    }
    @Test void failedModelReplyDoesNotCompleteChatAndLaterSuccessRecovers()throws Exception{
        var r=room();throughAlbum(r);getJson(r.user,r.path()+"/chat");
        llm.fail(new TimeoutException("fake timeout"));llm.fail(new TimeoutException("fake timeout"));
        var current=getJson(r.user,r.path()+"/chat").path("data").path("session");
        var result=postAs(r.user,r.path()+"/chat/CONTINUOUS/answer",Map.of("text","안녕","sessionId",current.path("id").asLong(),
            "expectedRound",current.path("round").asInt(),"requestId",UUID.randomUUID().toString()));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);key(r,FirstIntro.Step.CHAT);
        chat(r,"잘 들었어요");key(r,FirstIntro.Step.PERSONALITY);
    }
    @Test void taggedDelayedRequestsNeverBecomeRegularCareAfterTutorialCompletion()throws Exception{
        var r=room();throughFinish(r);petService.tutorialDone(r.user,r.pet,Instant.now());
        int food=pet(r).getFood(),feeds=pet(r).getFeeds();
        assertThat(postAs(r.user,r.path()+"/care",Map.of("action","FEED","firstIntroStep","FEED")).getResponse().getStatus()).isEqualTo(200);
        assertThat(postAs(r.user,r.path()+"/sleep",Map.of("firstIntroStep","NAP_SLEEP")).getResponse().getStatus()).isEqualTo(200);
        assertThat(postAs(r.user,r.path()+"/wake",Map.of("firstIntroStep","NAP_WAKE")).getResponse().getStatus()).isEqualTo(200);
        assertThat(postAs(r.user,r.path()+"/games",Map.of("kind","LEFT_RIGHT","firstIntroStep","GAME")).getResponse().getStatus()).isEqualTo(409);
        assertThat(pet(r).getFood()).isEqualTo(food);assertThat(pet(r).getFeeds()).isEqualTo(feeds);
        assertThat(pet(r).isSleeping()).isFalse();assertThat(pet(r).getTodayGames()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(ints={2,3})
    void everyCareAndNapStepSurvivesReopeningAndBathClearsAllThree(int version)throws Exception{
        var r=room(kstToday(3,0),version);
        persistedKey(r,FirstIntro.Step.FEED);care(r,CareAction.FEED);
        persistedKey(r,FirstIntro.Step.SNACK);care(r,CareAction.SNACK);
        persistedKey(r,FirstIntro.Step.CLEAN);care(r,CareAction.CLEAN);
        persistedKey(r,FirstIntro.Step.BATH);assertThat(pet(r).getTrash()).isEqualTo(3);
        care(r,CareAction.BATH);persistedKey(r,FirstIntro.Step.GAME);assertThat(pet(r).getTrash()).isZero();
        var game=games.start(r.user,r.pet,GameKind.LEFT_RIGHT,Instant.now());
        for(int i=0;i<5;i++){var reply=games.guess(r.user,r.pet,game.game().getId(),'L',Instant.now());if(reply.game().isFinished())break;}
        persistedKey(r,FirstIntro.Step.PET);care(r,CareAction.PET);
        persistedKey(r,FirstIntro.Step.NAP_SLEEP);petService.sleep(r.user,r.pet,Instant.now());
        persistedKey(r,FirstIntro.Step.NAP_WAKE);assertThat(pet(r).getSleepKind()).isEqualTo(SleepKind.NAP);
        petService.wake(r.user,r.pet,Instant.now());persistedKey(r,FirstIntro.Step.ALBUM);
        assertThat(pet(r).isSleeping()).isFalse();
    }

    @Test void publicFirstIntroSchemaDoesNotOverwriteGenericGameState()throws Exception{
        var spec=getJson(newUserId(),"/api/v3/api-docs");
        var schemas=spec.path("components").path("schemas");
        assertThat(schemas.path("Detail").path("properties").path("firstIntro").path("$ref").asText())
                .isEqualTo("#/components/schemas/ZzalFirstIntroState");
        assertThat(schemas.path("ZzalFirstIntroState").path("properties").has("completionWillSleep")).isTrue();
    }

}
