package com.lore.zzal.it;

import com.lore.zzal.chat.presence.*;
import com.lore.zzal.chat.line.FakeChatLineClient;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@ZzalIntegrationTest
class ChatPresenceIT extends ZzalItSupport {
    @Autowired ChatPresenceService presence;
    @Autowired FakeChatLineClient llm;
    record Room(Long user,Long pet) { String path(){return "/api/zzal/v1/me/pets/"+pet+"/chat";} }
    private Room room() {
        Long user=newUserId();Instant now=kstToday(10,0);
        Long id=transactions.execute(s->{var p=ZzalPet.draft(user,newUploadedImageKey(user),now);
            p.character("친구",null,null,"현대",null,null,now);p.markAlive("sheet","identity",now);
            p.skipTutorial(now);return petRepository.save(p).getId();});
        motionSeeder.seed(id,now);pinClock(id,now);return new Room(user,id);
    }
    private ChatPresenceRequest event(UUID visit,long seq,String event,String kind) {
        return new ChatPresenceRequest(visit,UUID.randomUUID(),seq,ChatPresenceRequest.Event.valueOf(event),
                kind==null?null:ChatPresenceRequest.EntryKind.valueOf(kind));
    }
    private ChatPresenceResponse send(Room r,ChatPresenceRequest e,Instant now) {return presence.accept(r.user,r.pet,e,now);}
    private long count(Room r) {return jdbc.queryForObject("select count(*) from zzal_chat_turn where pet_id=?",Long.class,r.pet);}

    @Test void publicEndpointPreservesStageAuthorityAndUnknownDepartureNeverQualifies() throws Exception {
        Room r=room();Instant t=Instant.now();UUID v=UUID.randomUUID();
        // A leave without an observed active visit cannot invent a confirmed absence.
        send(r,event(v,1,"LEAVE",null),t);
        var request=event(v,2,"ENTER","OPEN");
        var response=postAs(r.user,r.path()+"/presence",request);
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(response).path("data").path("outcome").asText()).isEqualTo("RESTORED");
        assertThat(body(response).path("data").path("chat").path("stageSpeech").path("turnIdx").asInt()).isZero();
        assertThat(count(r)).isEqualTo(1);
        Room fresh=room();
        transactions.execute(status->{petRepository.findById(fresh.pet).orElseThrow().beginFirstMeeting();return null;});
        assertThat(send(fresh,event(UUID.randomUUID(),1,"ENTER","OPEN"),t).outcome()).isEqualTo("UNAVAILABLE");
        assertThat(count(fresh)).isZero();
    }

    @Test void exactOneHourReturnAddsOnlyPetTurnAndAnswerContinuesWithUniqueIdx() throws Exception {
        Room r=room();Instant t=Instant.now();UUID v=UUID.randomUUID();
        var opened=send(r,event(v,1,"ENTER","OPEN"),t);
        assertThat(opened.chat().stageSpeech()).isNotNull();
        send(r,event(v,2,"LEAVE",null),t.plusSeconds(1));
        var shortReturn=send(r,event(v,3,"ENTER","RETURN"),t.plusSeconds(3600));
        assertThat(shortReturn.outcome()).isEqualTo("RESTORED");assertThat(count(r)).isEqualTo(1);
        send(r,event(v,4,"LEAVE",null),t.plusSeconds(3601));
        var request=event(v,5,"ENTER","RETURN");
        var returned=send(r,request,t.plusSeconds(7201));
        assertThat(returned.outcome()).isEqualTo("GENERATED");
        assertThat(returned.chat().stageSpeech().turnIdx()).isEqualTo(1);
        assertThat(returned.chat().session().round()).isZero();
        int calls=llm.users.size();assertThat(send(r,request,t.plusSeconds(7202))).isEqualTo(returned);
        assertThat(llm.users).hasSize(calls);assertThat(count(r)).isEqualTo(2);
        var response=postAs(r.user,r.path()+"/CONTINUOUS/answer",Map.of("text","왔어","sessionId",returned.chat().session().id(),
                "expectedRound",0,"requestId",UUID.randomUUID().toString()));
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(response).path("data").path("turns").get(0).path("idx").asInt()).isEqualTo(2);
        assertThat(body(response).path("data").path("turns").get(1).path("idx").asInt()).isEqualTo(3);
        assertThat(getJson(r.user,r.path()).path("data").path("stageSpeech").isNull()).isTrue();
        assertThat(count(r)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(distinct idx) from zzal_chat_turn where pet_id=?",Long.class,r.pet)).isEqualTo(4);
    }

    @Test void reloadAndOtherActiveTabNeverTriggerAndDuplicateLeaveDoesNotMoveClock() {
        Room r=room();Instant t=Instant.now();UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        send(r,event(a,1,"ENTER","OPEN"),t);send(r,event(b,1,"ENTER","OPEN"),t);
        send(r,event(a,2,"LEAVE",null),t.plusSeconds(1));
        assertThat(send(r,event(a,3,"ENTER","RETURN"),t.plusSeconds(4000)).outcome()).isEqualTo("RESTORED");
        send(r,event(a,4,"LEAVE",null),t.plusSeconds(4001));send(r,event(b,2,"LEAVE",null),t.plusSeconds(4002));
        send(r,event(b,3,"LEAVE",null),t.plusSeconds(7000));
        assertThat(send(r,event(a,5,"ENTER","RELOAD"),t.plusSeconds(7602)).outcome()).isEqualTo("IGNORED_RELOAD");
        assertThat(count(r)).isEqualTo(1);
        send(r,event(a,6,"LEAVE",null),t.plusSeconds(7603));
        send(r,event(a,7,"LEAVE",null),t.plusSeconds(10000));
        assertThat(send(r,event(a,8,"ENTER","RETURN"),t.plusSeconds(11203)).outcome()).isEqualTo("GENERATED");
        assertThat(send(r,event(a,2,"LEAVE",null),t.plusSeconds(12000)).outcome()).isEqualTo("STALE");
        assertThat(count(r)).isEqualTo(2);
    }

    @Test void concurrentDevicesGenerateOnceAndConflictingEventIsRejected() throws Exception {
        Room r=room();Instant t=Instant.now();UUID a=UUID.randomUUID();
        send(r,event(a,1,"ENTER","OPEN"),t);send(r,event(a,2,"LEAVE",null),t.plusSeconds(1));
        var x=event(UUID.randomUUID(),1,"ENTER","OPEN");var y=event(UUID.randomUUID(),1,"ENTER","OPEN");
        var pool=Executors.newFixedThreadPool(2);
        try {
            var results=pool.<ChatPresenceResponse>invokeAll(List.of(()->send(r,x,t.plusSeconds(3601)),()->send(r,y,t.plusSeconds(3601))));
            assertThat(results.stream().map(f->{try{return f.get().outcome();}catch(Exception e){throw new RuntimeException(e);}}).toList())
                    .containsExactlyInAnyOrder("GENERATED","RESTORED");
        } finally {pool.shutdownNow();}
        assertThat(count(r)).isEqualTo(2);
        var conflict=new ChatPresenceRequest(x.visitId(),x.eventId(),99,ChatPresenceRequest.Event.LEAVE,null);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->send(r,conflict,t)).isInstanceOf(com.lore.common.exception.BusinessException.class);
        assertThat(postAs(newUserId(),r.path()+"/presence",x).getResponse().getStatus()).isEqualTo(404);
        assertThat(postAs(r.user,r.path()+"/presence",Map.of("visitId",a,"eventId",UUID.randomUUID(),"sequence",0,"event","ENTER"))
                .getResponse().getStatus()).isEqualTo(400);
    }

    @Test void sleepingTravelAndFailureDoNotDuplicateOrCloseConversation() {
        Room r=room();Instant t=Instant.now();UUID v=UUID.randomUUID();
        send(r,event(v,1,"ENTER","OPEN"),t);send(r,event(v,2,"LEAVE",null),t.plusSeconds(1));
        transactions.execute(s->{var p=petRepository.findById(r.pet).orElseThrow();
            ReflectionTestUtils.setField(p,"tripStartedAt",t);return null;});
        assertThat(send(r,event(v,3,"ENTER","RETURN"),t.plusSeconds(3601)).outcome()).isEqualTo("UNAVAILABLE");
        assertThat(count(r)).isEqualTo(1);
        send(r,event(v,4,"LEAVE",null),t.plusSeconds(3602));
        transactions.execute(s->{var p=petRepository.findById(r.pet).orElseThrow();
            ReflectionTestUtils.setField(p,"tripStartedAt",null);ReflectionTestUtils.setField(p,"sleepKind",com.lore.zzal.pet.SleepKind.NIGHT);
            ReflectionTestUtils.setField(p,"sleptAt",t);return null;});
        assertThat(send(r,event(v,5,"ENTER","RETURN"),t.plusSeconds(7202)).outcome()).isEqualTo("UNAVAILABLE");
        send(r,event(v,6,"LEAVE",null),t.plusSeconds(7203));
        transactions.execute(s->{var p=petRepository.findById(r.pet).orElseThrow();
            ReflectionTestUtils.setField(p,"sleepKind",null);ReflectionTestUtils.setField(p,"sleptAt",null);return null;});
        llm.fail(new TimeoutException()).reply("bad json");var req=event(v,7,"ENTER","RETURN");
        var failed=send(r,req,t.plusSeconds(10803));
        assertThat(failed.chat().session().closed()).isFalse();assertThat(failed.chat().session().round()).isZero();
        int calls=llm.users.size();send(r,req,t.plusSeconds(10804));assertThat(llm.users).hasSize(calls);
        assertThat(count(r)).isEqualTo(2);
    }
}
