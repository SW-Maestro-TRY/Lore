package com.lore.zzal.it;

import com.lore.zzal.chat.line.FakeChatLineClient;
import com.lore.zzal.chat.memory.facts.*;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeoutException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@ZzalIntegrationTest
class FactMemoryIT extends ZzalItSupport {
    @Autowired FakeChatLineClient llm;
    @Autowired FactMemoryService memory;
    @Autowired ZzalFactMemoryRepository facts;
    @Autowired ZzalMemoryCaptureRepository jobs;
    @Autowired MemoryCaptureWorker worker;
    @Autowired com.lore.zzal.chat.ChatService chat;
    record Room(Long user,Long pet) {String path(){return "/api/zzal/v1/me/pets/"+pet+"/chat";}}
    private Room room() throws Exception {
        Long user=newUserId();Instant now=kstToday(10,0);
        Long id=transactions.execute(s->{var p=ZzalPet.draft(user,newUploadedImageKey(user),now);
            p.character("캐릭터",null,null,"현대",null,null,now);p.markAlive("sheet","identity",now);
            p.skipTutorial(now);p.rememberExplicitCallMe("민지");return petRepository.save(p).getId();});
        motionSeeder.seed(id,now);pinClock(id,now);var r=new Room(user,id);getJson(user,r.path());return r;
    }
    private Map<String,Object> delta(String subject,String ref,String content,String quote){
        var d=new LinkedHashMap<String,Object>();d.put("operation","ADD");d.put("source","USER");d.put("subject",subject);
        if(ref!=null)d.put("subject_ref",ref);d.put("content",content);d.put("source_quote",quote);
        d.put("kind","USER_REPORTED");d.put("uncertain",false);d.put("durable",true);return d;
    }
    private Map<String,Object> request(Room r,String text) throws Exception {
        var current=getJson(r.user,r.path()).path("data").path("session");
        return Map.of("text",text,"sessionId",current.path("id").asLong(),"expectedRound",current.path("round").asInt(),
                "requestId",UUID.randomUUID().toString());
    }
    private void answer(Room r,String user,String reply,Object deltas) throws Exception {
        llm.reply(json.writeValueAsString(Map.of("line",reply,"motion","reply","call_me","잘못된이름","memory_deltas",deltas)));
        var result=postAs(r.user,r.path()+"/CONTINUOUS/answer",request(r,user));
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        await("semantic capture",java.time.Duration.ofSeconds(5),()->jobs.findAll().stream().noneMatch(j->j.status().equals("QUEUED")));
    }

    @Test void thirdPartyAndUserNegativeKeepOriginalSubjectSourceAndNameAuthority() throws Exception {
        Room r=room();
        answer(r,"친구 수진은 커피 좋아해","수진 얘기구나",List.of(delta("THIRD_PARTY","수진","수진은 커피를 좋아한다","수진은 커피 좋아해")));
        answer(r,"나는 커피 싫어","너는 커피를 안 좋아하는구나",List.of(delta("USER",null,"커피를 좋아하지 않는다","나는 커피 싫어")));
        var rows=memory.list(r.user,r.pet,10);assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().subject()).isEqualTo("USER");assertThat(rows.getFirst().content()).contains("않는다");
        assertThat(rows.getLast().subject()).isEqualTo("THIRD_PARTY");assertThat(rows.getLast().subjectRef()).isEqualTo("수진");
        assertThat(rows).allMatch(f->f.status().equals("ACTIVE"));
        assertThat(jdbc.queryForObject("select line from zzal_chat_turn where id=?",String.class,rows.getLast().sourceTurnId()))
                .isEqualTo("친구 수진은 커피 좋아해");
        assertThat(petRepository.findById(r.pet).orElseThrow().getCallMe()).isEqualTo("민지");
        answer(r,"기억나?","응, 각각 구분해 들었어",List.of());
        assertThat(llm.users.getLast()).contains("[회상기억","THIRD_PARTY","수진","USER","않는다");
        assertThat(getJson(r.user,r.path()+"/memory-facts").path("data")).hasSize(2);
        assertThat(mockMvc.perform(get(r.path()+"/memory-facts").with(asUser(newUserId()))).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test void duplicateDeliveryDoesNotCaptureOrCallModelTwice() throws Exception {
        Room r=room();var request=request(r,"나는 차 좋아해");
        llm.reply(json.writeValueAsString(Map.of("line","차 좋아하는구나","memory_deltas",List.of(delta("USER",null,"차를 좋아한다","나는 차 좋아해")))));
        int before=llm.users.size();var a=postAs(r.user,r.path()+"/CONTINUOUS/answer",request);
        var b=postAs(r.user,r.path()+"/CONTINUOUS/answer",request);
        assertThat(a.getResponse().getStatus()).isEqualTo(200);assertThat(b.getResponse().getContentAsString()).isEqualTo(a.getResponse().getContentAsString());
        await("single capture",java.time.Duration.ofSeconds(5),()->facts.count()==1);
        assertThat(llm.users).hasSize(before+1);assertThat(facts.count()).isEqualTo(1);assertThat(jobs.count()).isEqualTo(1);
        worker.process(jobs.findAll().getFirst().id());assertThat(facts.count()).isEqualTo(1);
    }

    @Test void explicitCorrectionAndRetractionInvalidateRecallAndPreserveOriginalTurns() throws Exception {
        Room r=room();answer(r,"커피 좋아해","알겠어",List.of(delta("USER",null,"커피를 좋아한다","커피 좋아해")));
        Long first=memory.list(r.user,r.pet,10).getFirst().id();
        var correction=delta("THIRD_PARTY","친구","친구는 커피를 좋아한다","아까는 친구 얘기야");
        correction.put("operation","CORRECT");correction.put("correction_of",first);
        answer(r,"아까는 친구 얘기야","친구 얘기로 고쳐 들을게",List.of(correction));
        var rows=memory.list(r.user,r.pet,10);Long second=rows.getFirst().id();
        assertThat(rows.getLast().status()).isEqualTo("SUPERSEDED");assertThat(rows.getFirst().correctionOf()).isEqualTo(first);
        assertThat(rows.getFirst().revision()).isEqualTo(2);
        assertThat(memory.recall(r.pet,"커피")).extracting(FactView::id).containsExactly(second);
        answer(r,"다른 얘기할래","친구는 커피 좋아했지",List.of());
        llm.reply("{\"line\":\"친구는 커피 좋아하잖아\",\"motion\":\"hello\"}");
        assertThat(chat.appendReturnGreeting(r.user,r.pet,Instant.now())).isTrue();
        var retract=delta("THIRD_PARTY","친구","unused","그 친구 기억은 잊어줘");retract.put("operation","RETRACT");retract.put("correction_of",second);
        answer(r,"그 친구 기억은 잊어줘","그 기억은 쓰지 않을게",List.of(retract));
        assertThat(memory.recall(r.pet,"커피")).isEmpty();
        answer(r,"새로운 얘기하자","좋아, 이어가자",List.of());
        assertThat(llm.users.getLast()).doesNotContain("상대: 커피 좋아해","커피를 좋아한다","친구 얘기로 고쳐 들을게","친구는 커피 좋아했지","친구는 커피 좋아하잖아");
        assertThat(llm.users.getLast()).contains("그 친구 기억은 잊어줘");
        assertThat(getJson(r.user,r.path()+"/history").path("data").path("turns").toString()).contains("커피 좋아해");
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_turn where pet_id=? and speaker='USER'",Long.class,r.pet)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_turn where pet_id=? and line='커피 좋아해'",Long.class,r.pet)).isEqualTo(1);
    }

    @Test void malformedAuxiliaryExtractionDoesNotRetryOrRollbackNormalReply() throws Exception {
        Room r=room();int before=llm.users.size();
        answer(r,"차 좋아해","말은 정상으로 받았어","broken auxiliary json");
        assertThat(llm.users).hasSize(before+1);assertThat(jobs.findAll().getFirst().status()).isEqualTo("INVALID");assertThat(facts.count()).isZero();
        var invalid=delta("USER",null,"잘못된 근거","원문에 없는 구절");
        answer(r,"계속 이야기하자","응, 이어가자",List.of(invalid));
        assertThat(facts.count()).isZero();
        assertThat(getJson(r.user,r.path()).path("data").path("session").path("round").asInt()).isEqualTo(2);
    }

    @Test void uncertainEphemeralAndCharacterFictionRemainSeparatedPending() throws Exception {
        Room r=room();var mood=delta("USER",null,"오늘 피곤하다","오늘 피곤해");mood.put("durable",false);
        var guess=delta("USER",null,"조용한 성격일 수 있다","오늘 피곤해");guess.put("kind","INFERENCE");guess.put("uncertain",true);
        var fiction=delta("CHARACTER","SELF","공방에서 차를 마셨다","공방에서 차 마셨어");fiction.put("source","PET");fiction.put("kind","FICTION");
        answer(r,"오늘 피곤해","공방에서 차 마셨어. 같이 쉬자",List.of(mood,guess,fiction));
        var rows=memory.list(r.user,r.pet,10);assertThat(rows).hasSize(3).allMatch(f->f.status().equals("PENDING"));
        assertThat(rows).extracting(FactView::kind).containsExactlyInAnyOrder("USER_REPORTED","INFERENCE","FICTION");
        var f=rows.stream().filter(x->x.kind().equals("FICTION")).findFirst().orElseThrow();
        assertThat(jdbc.queryForObject("select speaker from zzal_chat_turn where id=?",String.class,f.sourceTurnId())).isEqualTo("PET");
        assertThat(memory.recall(r.pet,"차")).isEmpty();
    }

    @Test void failedModelReplyHasNoSemanticCaptureAndForeignCorrectionCannotMutateFacts() throws Exception {
        Room a=room(),b=room();answer(a,"나는 차 좋아해","응",List.of(delta("USER",null,"차를 좋아한다","차 좋아해")));
        Long id=memory.list(a.user,a.pet,10).getFirst().id();
        var wrong=delta("USER",null,"차를 싫어한다","난 차 싫어");wrong.put("operation","CORRECT");wrong.put("correction_of",id);
        answer(b,"난 차 싫어","알겠어",List.of(wrong));
        assertThat(memory.list(a.user,a.pet,10).getFirst().status()).isEqualTo("ACTIVE");assertThat(memory.list(b.user,b.pet,10)).isEmpty();
        long before=jobs.count();llm.fail(new TimeoutException()).reply("not json");
        var failed=postAs(b.user,b.path()+"/CONTINUOUS/answer",request(b,"이어 말할게"));
        assertThat(failed.getResponse().getStatus()).isEqualTo(200);assertThat(jobs.count()).isEqualTo(before);
    }

    @Test void assetSnapshotKeepsIdentityVersionSourcesAndCorrectionRevisionWithoutUserLeak() throws Exception {
        Room r=room();var empty=memory.material(r.user,r.pet,1,"identity-sha",Instant.now());assertThat(empty.state()).isEqualTo("EMPTY");
        answer(r,"나는 커피 좋아해","응",List.of(delta("USER",null,"커피를 좋아한다","커피 좋아해")));
        assertThat(memory.material(r.user,r.pet,1,"identity-sha",Instant.now()).state()).isEqualTo("NONE_ELIGIBLE");
        answer(r,"너는 숲 좋아하잖아","응, 숲 얘기였지",List.of(delta("CHARACTER","SELF","숲을 좋아한다","너는 숲 좋아하잖아")));
        var first=memory.material(r.user,r.pet,1,"identity-sha",Instant.now());assertThat(first.state()).isEqualTo("AVAILABLE");
        assertThat(first.identityHash()).isEqualTo("identity-sha");assertThat(first.candidates()).hasSize(1);
        var change=delta("CHARACTER","SELF","바다를 좋아한다","아냐 바다를 좋아해");change.put("operation","CORRECT");change.put("correction_of",first.candidates().getFirst().id());
        answer(r,"아냐 바다를 좋아해","바다로 고쳐 들을게",List.of(change));
        var second=memory.material(r.user,r.pet,1,"identity-sha",Instant.now());
        assertThat(second.memoryRevision()).isGreaterThan(first.memoryRevision());assertThat(second.candidates().getFirst().content()).contains("바다");
        assertThat(first.candidates().getFirst().content()).contains("숲");assertThat(second.candidates().getFirst().revision()).isEqualTo(2);
    }

    @Test void failedCaptureRetriesStoredSourcesWithoutRegeneratingDialogue() throws Exception {
        Room r=room();var request=request(r,"나는 차 좋아해");
        var response=postAs(r.user,r.path()+"/CONTINUOUS/answer",request);
        assertThat(response.getResponse().getStatus()).isEqualTo(200);
        Long userTurn=jdbc.queryForObject("select id from zzal_chat_turn where pet_id=? and speaker='USER'",Long.class,r.pet);
        Long petTurn=jdbc.queryForObject("select max(id) from zzal_chat_turn where pet_id=?",Long.class,r.pet);
        String payload=json.writeValueAsString(List.of(delta("USER",null,"차를 좋아한다","차 좋아해")));
        Long job=transactions.execute(t->jobs.save(new ZzalMemoryCapture(r.pet,userTurn,petTurn,MemoryCaptureQueue.VERSION,
                payload,"[]",Instant.now())).id());
        worker.failed(job);int calls=llm.users.size();
        assertThat(postAs(newUserId(),r.path()+"/memory-facts/retry",Map.of()).getResponse().getStatus()).isEqualTo(404);
        assertThat(postAs(r.user,r.path()+"/memory-facts/retry",Map.of()).getResponse().getStatus()).isEqualTo(200);
        await("capture retry",java.time.Duration.ofSeconds(5),()->facts.count()==1);
        assertThat(jobs.findById(job).orElseThrow().status()).isEqualTo("APPLIED");
        assertThat(postAs(r.user,r.path()+"/memory-facts/retry",Map.of()).getResponse().getStatus()).isEqualTo(200);
        assertThat(facts.count()).isEqualTo(1);assertThat(llm.users).hasSize(calls);
    }

    @Test void japaneseAndEnglishOriginalsAreStoredWithoutTranslationOrExtraCalls() throws Exception {
        Room r=room();String japanese="私はコーヒーが好きです";
        answer(r,japanese,"コーヒーが好きなんだね",List.of(delta("USER",null,"コーヒーが好き",japanese)));
        String english="I prefer tea now";
        answer(r,english,"Tea it is",List.of(delta("USER",null,"Prefers tea",english)));
        var rows=memory.list(r.user,r.pet,10);assertThat(rows).extracting(FactView::sourceQuote).containsExactly(english,japanese);
        assertThat(jdbc.queryForList("select line from zzal_chat_turn where pet_id=? and speaker='USER' order by id",String.class,r.pet))
                .containsExactly(japanese,english);
    }
}
