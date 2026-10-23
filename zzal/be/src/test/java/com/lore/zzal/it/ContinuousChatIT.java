package com.lore.zzal.it;

import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.delivery.ChatRewardLedger;
import com.lore.zzal.chat.line.FakeChatLineClient;
import com.lore.zzal.chat.session.ZzalChatSessionRepository;
import com.lore.zzal.pet.CareAction;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

@ZzalIntegrationTest
class ContinuousChatIT extends ZzalItSupport {
    @Autowired FakeChatLineClient llm;
    @Autowired ChatRewardLedger rewards;
    @Autowired ZzalChatSessionRepository sessions;

    record Room(Long user,Long pet,Long session) {
        String path(){return "/api/zzal/v1/me/pets/"+pet+"/chat";}
        Map<String,Object> answer(int round,String text){return Map.of("text",text,"sessionId",session,
                "expectedRound",round,"requestId",UUID.randomUUID().toString());}
    }
    private Room room() throws Exception {
        Long user=newUserId(); Instant now=Instant.now();
        var pet=transactions.execute(status->{
            var p=petRepository.save(ZzalPet.draft(user,newUploadedImageKey(user),now));
            p.character("여울",null,null,"현대",null,null,now);p.markAlive("images/zzal/pets/sheet.png","test",now);return p;
        });
        motionSeeder.seed(pet.getId(),now);
        var path="/api/zzal/v1/me/pets/"+pet.getId();
        postAs(user,path+"/care",Map.of("action",CareAction.FEED.name()));
        postAs(user,path+"/care",Map.of("action",CareAction.PET.name()));
        var chat=getJson(user,path+"/chat").path("data");
        assertThat(chat.path("openSlot").asText()).isEqualTo("CONTINUOUS");
        assertThat(chat.path("session").path("maxRounds").isNull()).isTrue();
        return new Room(user,pet.getId(),chat.path("session").path("id").asLong());
    }

    @Test
    void fiftyRoundsKeepSameSessionAndPromptBoundWhileHistoryRemainsComplete() throws Exception {
        Room r=room();
        for(int i=0;i<50;i++){
            var response=postAs(r.user(),r.path()+"/CONTINUOUS/answer",r.answer(i,i==0?"첫고유발언":"이어서"+i));
            assertThat(response.getResponse().getStatus()).as(response.getResponse().getContentAsString()).isEqualTo(200);
            var data=body(response).path("data");
            assertThat(data.path("session").path("id").asLong()).isEqualTo(r.session());
            assertThat(data.path("session").path("round").asInt()).isEqualTo(i+1);
            assertThat(data.path("session").path("closed").asBoolean()).isFalse();
            assertThat(data.path("turns").get(1).path("type").asText()).isEqualTo("CONTINUE");
        }
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_turn where pet_id=?",Integer.class,r.pet())).isEqualTo(101);
        var first=getJson(r.user(),r.path()).path("data");
        assertThat(first.path("turns")).hasSize(40);
        assertThat(first.path("session").path("round").asInt()).isEqualTo(50);
        assertThat(llm.users.getLast()).contains("이어서49").doesNotContain("첫고유발언");
        assertThat(llm.users.getLast().lines().filter(x->x.startsWith("너: ")||x.startsWith("상대: ")).count()).isLessThanOrEqualTo(40);
        Set<Long> ids=new HashSet<>(); Long cursor=null;
        do {
            var page=getJson(r.user(),r.path()+"/history"+(cursor==null?"":"?beforeId="+cursor)).path("data");
            page.path("turns").forEach(t->assertThat(ids.add(t.path("id").asLong())).isTrue());
            cursor=page.path("nextBeforeId").isNull()?null:page.path("nextBeforeId").asLong();
        }while(cursor!=null);
        assertThat(ids).hasSize(101);
        assertThat(petRepository.findById(r.pet()).orElseThrow().getChatAnswers()).isEqualTo(1);
        assertThat(postAs(newUserId(),r.path()+"/CONTINUOUS/answer",r.answer(50,"타인")).getResponse().getStatus()).isEqualTo(404);
        assertThat(getJson(r.user(),r.path()+"/history?limit=100").path("data").path("turns")).hasSize(100);
    }

    @Test
    void tenMinuteGapMidnightAndOldWindowBoundaryDoNotReplaceOrCloseTutorialConversation() throws Exception {
        Room r=room();
        var first=postAs(r.user(),r.path()+"/CONTINUOUS/answer",r.answer(0,"첫 답"));
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        for(var target:List.of(kstToday(13,59),kstToday(14,0),kstToday(23,59),kstToday(0,1).plusSeconds(86400))){
            pinClock(r.pet(),target);
            var chat=getJson(r.user(),r.path()).path("data");
            assertThat(chat.path("session").path("id").asLong()).isEqualTo(r.session());
            assertThat(chat.path("session").path("closed").asBoolean()).isFalse();
            assertThat(chat.path("openSlot").asText()).isEqualTo("CONTINUOUS");
        }
        var after=postAs(r.user(),r.path()+"/CONTINUOUS/answer",r.answer(1,"계속 답"));
        assertThat(after.getResponse().getStatus()).as(after.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(after).path("data").path("session").path("id").asLong()).isEqualTo(r.session());
    }

    @Test
    void generationFailureDoesNotCloseSessionAndNextMessageCanSucceed() throws Exception {
        Room r=room();llm.fail(new TimeoutException()).reply("bad json");
        var failed=postAs(r.user(),r.path()+"/CONTINUOUS/answer",r.answer(0,"실패 앞의 말"));
        assertThat(failed.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(failed).path("data").path("session").path("closed").asBoolean()).isFalse();
        assertThat(body(failed).path("data").path("chatReply").path("line").asText()).isEqualTo("잠깐 말이 잘 안 나왔어.");
        var next=postAs(r.user(),r.path()+"/CONTINUOUS/answer",r.answer(1,"다음 말"));
        assertThat(next.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(next).path("data").path("session").path("round").asInt()).isEqualTo(2);
        assertThat(body(next).path("data").path("session").path("id").asLong()).isEqualTo(r.session());
    }

    @Test
    void rewardWindowsRemainSeparateAndPreviouslyRewardedWindowIsNotGrantedTwice() throws Exception {
        Room r=room();
        postAs(r.user(),r.path()+"/CONTINUOUS/answer",r.answer(0,"튜토리얼"));
        var session=sessions.findById(r.session()).orElseThrow();
        transactions.executeWithoutResult(status->petRepository.findByIdForUpdate(r.pet()).orElseThrow().skipTutorial(kstToday(9,0)));
        assertThat((Boolean)transactions.execute(status->rewards.claim(petRepository.findByIdForUpdate(r.pet()).orElseThrow(),session,kstToday(9,30)))).isFalse();
        for(int h:new int[]{10,14,19}){
            assertThat((Boolean)transactions.execute(status->rewards.claim(petRepository.findByIdForUpdate(r.pet()).orElseThrow(),session,kstToday(h,1)))).isTrue();
            assertThat((Boolean)transactions.execute(status->rewards.claim(petRepository.findByIdForUpdate(r.pet()).orElseThrow(),session,kstToday(h,2)))).isFalse();
        }
        assertThat((Boolean)transactions.execute(status->rewards.claim(petRepository.findByIdForUpdate(r.pet()).orElseThrow(),session,kstToday(23,1)))).isFalse();
        var tomorrow=java.time.LocalDate.now(com.lore.zzal.pet.ZzalRules.ZONE).plusDays(1);
        transactions.executeWithoutResult(status->{
            var old=com.lore.zzal.chat.session.ZzalChatSession.open(r.pet(),tomorrow,ChatSlot.MORNING,
                    com.lore.zzal.chat.session.SessionKind.DAILY,kstToday(10,1).plusSeconds(86400),null,Instant.now());
            old.countUserTurn("이미 보상한 옛 판");sessions.save(old);
        });
        assertThat((Boolean)transactions.execute(status->rewards.claim(petRepository.findByIdForUpdate(r.pet()).orElseThrow(),session,kstToday(10,2).plusSeconds(86400)))).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_reward where pet_id=?",Integer.class,r.pet())).isEqualTo(4);
    }
    @Test
    void legacyCallHistoryIsStillReadableWithCursor() throws Exception {
        Room r=room();
        var repo=context.getBean(com.lore.zzal.chat.ZzalChatCallRepository.class);
        transactions.executeWithoutResult(status->{
            for(int n=1;n<=2;n++){
                var day=java.time.LocalDate.now(com.lore.zzal.pet.ZzalRules.ZONE).minusDays(n);
                var call=com.lore.zzal.chat.ZzalChatCall.call(r.pet(),day,ChatSlot.MORNING,"옛 부름"+n,
                        Instant.now().minusSeconds(86400L*n),null);
                call.answer("옛 사용자 답"+n,"옛 펫 답"+n,"hello",Instant.now().minusSeconds(86400L*n));
                repo.save(call);
            }
        });
        var first=getJson(r.user(),r.path()+"/history/legacy?limit=1").path("data");
        assertThat(first.path("calls")).hasSize(1);
        assertThat(first.path("calls").get(0).path("userLine").asText()).isEqualTo("옛 사용자 답2");
        long cursor=first.path("nextBeforeId").asLong();
        var second=getJson(r.user(),r.path()+"/history/legacy?limit=1&beforeId="+cursor).path("data");
        assertThat(second.path("calls")).hasSize(1);
        assertThat(second.path("calls").get(0).path("userLine").asText()).isEqualTo("옛 사용자 답1");
        assertThat(second.path("nextBeforeId").isNull()).isTrue();
    }
}
