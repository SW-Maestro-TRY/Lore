package com.lore.zzal.it;

import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.line.FakeChatLineClient;
import com.lore.zzal.chat.session.SessionKind;
import com.lore.zzal.chat.session.ZzalChatSession;
import com.lore.zzal.chat.session.ZzalChatSessionRepository;
import com.lore.zzal.pet.CareAction;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** 진짜 MVC/DB·펫 잠금/응답 snapshot, 외부 모델은 대역. */
@ZzalIntegrationTest
class ChatDeliveryIT extends ZzalItSupport {
    @Autowired FakeChatLineClient llm;
    @Autowired ZzalChatSessionRepository sessions;

    record Room(Long user, Long pet, Long session) {
        String path() { return "/api/zzal/v1/me/pets/" + pet + "/chat/CONTINUOUS/answer"; }
    }

    private Room room() throws Exception {
        Long user = newUserId();
        Instant now = Instant.now();
        ZzalPet pet = transactions.execute(status -> {
            ZzalPet created = petRepository.save(ZzalPet.draft(user, newUploadedImageKey(user), now));
            created.character("여울", null, null, "현대", null, null, now);
            created.markAlive("images/zzal/pets/sheet.png", "test", now);
            return created;
        });
        motionSeeder.seed(pet.getId(), now);
        String path = "/api/zzal/v1/me/pets/" + pet.getId();
        assertThat(postAs(user, path + "/care", Map.of("action", CareAction.FEED.name())).getResponse().getStatus()).isEqualTo(200);
        assertThat(postAs(user, path + "/care", Map.of("action", CareAction.PET.name())).getResponse().getStatus()).isEqualTo(200);
        Long session = getJson(user, path + "/chat").path("data").path("session").path("id").asLong();
        return new Room(user, pet.getId(), session);
    }

    private Map<String,Object> request(Room room, int round, UUID id, String text) {
        return Map.of("sessionId",room.session(),"expectedRound",round,"requestId",id.toString(),"text",text);
    }

    @Test
    void concurrentSameRequestReplaysExactlyAndDoesNotSpendTwice() throws Exception {
        Room r = room();
        var payload = request(r,0,UUID.randomUUID(),"같은 답");
        int before = llm.users.size();
        CountDownLatch gate = new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> { gate.await(); return postAs(r.user(),r.path(),payload); });
            var b=pool.submit(() -> { gate.await(); return postAs(r.user(),r.path(),payload); });
            gate.countDown();
            MvcResult one=a.get(20,TimeUnit.SECONDS),two=b.get(20,TimeUnit.SECONDS);
            assertThat(one.getResponse().getStatus()).as(one.getResponse().getContentAsString()).isEqualTo(200);
            assertThat(two.getResponse().getStatus()).as(two.getResponse().getContentAsString()).isEqualTo(200);
            assertThat(body(one).path("data")).isEqualTo(body(two).path("data"));
        }
        assertThat(llm.users.size()-before).isEqualTo(1);
        assertThat(jdbc.queryForObject("select round_count from zzal_chat_session where id=?",Integer.class,r.session())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_turn where pet_id=?",Integer.class,r.pet())).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_delivery where pet_id=?",Integer.class,r.pet())).isEqualTo(1);
        assertThat(petRepository.findById(r.pet()).orElseThrow().getChatAnswers()).isEqualTo(1);
        // 변경 payload, 다른 사용자, 다른 pet의 session은 재사용하거나 노출하지 않는다.
        var conflict=postAs(r.user(),r.path(),request(r,0,UUID.fromString((String)payload.get("requestId")),"다른 답"));
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(conflict)).isEqualTo("ZZAL_CHAT_REQUEST_CONFLICT");
        assertThat(postAs(newUserId(),r.path(),payload).getResponse().getStatus()).isEqualTo(404);
        assertThat(llm.users.size()-before).isEqualTo(1);
    }

    @Test
    void concurrentDifferentIdsWithSameExpectedRoundOnlyOneWins() throws Exception {
        Room r=room();
        int before=llm.users.size();
        CountDownLatch gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> { gate.await(); return postAs(r.user(),r.path(),request(r,0,UUID.randomUUID(),"한 답")); });
            var b=pool.submit(() -> { gate.await(); return postAs(r.user(),r.path(),request(r,0,UUID.randomUUID(),"다른 답")); });
            gate.countDown();
            var results=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
            assertThat(results.stream().map(x->x.getResponse().getStatus())).containsExactlyInAnyOrder(200,409);
            MvcResult rejected=results.stream().filter(x->x.getResponse().getStatus()==409).findFirst().orElseThrow();
            assertThat(errorCode(rejected)).isEqualTo("ZZAL_CHAT_SESSION_STALE");
        }
        assertThat(llm.users.size()-before).isEqualTo(1);
        assertThat(jdbc.queryForObject("select round_count from zzal_chat_session where id=?",Integer.class,r.session())).isEqualTo(1);
    }

    @Test
    void continuousSixthAllowedAndLastRequestReplays() throws Exception {
        Room r=room();
        Map<String,Object> last=null;
        MvcResult lastResult=null;
        for(int round=0;round<5;round++) {
            last=request(r,round,UUID.randomUUID(),"답"+round);
            lastResult=postAs(r.user(),r.path(),last);
            assertThat(lastResult.getResponse().getStatus()).as(lastResult.getResponse().getContentAsString()).isEqualTo(200);
        }
        assertThat(body(lastResult).path("data").path("session").path("closed").asBoolean()).isFalse();
        assertThat(body(lastResult).path("data").path("session").path("maxRounds").isNull()).isTrue();
        int before=llm.users.size();
        assertThat(body(postAs(r.user(),r.path(),last)).path("data")).isEqualTo(body(lastResult).path("data"));
        MvcResult sixth=postAs(r.user(),r.path(),request(r,5,UUID.randomUUID(),"여섯"));
        assertThat(sixth.getResponse().getStatus()).isEqualTo(200);
        assertThat(llm.users.size()).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_turn where pet_id=?",Integer.class,r.pet())).isEqualTo(13);
    }

    @Test
    void staleSessionDoesNotCreateNewWindowOrCallModel() throws Exception {
        Room r=room();
        // 실제벽시계와무관한어제MORNING판. 오늘같은slot/다른slot을materialize하지않아야한다.
        Instant yesterday=Instant.now().minusSeconds(86400);
        ZzalChatSession old=transactions.execute(status -> sessions.save(ZzalChatSession.open(r.pet(),
                LocalDate.now(com.lore.zzal.pet.ZzalRules.ZONE).minusDays(1),ChatSlot.MORNING,SessionKind.DAILY,
                yesterday,yesterday.plusSeconds(60),yesterday)));
        int before=llm.users.size();
        long sessionsBefore=sessions.count();
        var payload=Map.of("sessionId",old.getId(),"expectedRound",0,"requestId",UUID.randomUUID().toString(),"text","어제 답");
        MvcResult stale=postAs(r.user(),r.path().replace("CONTINUOUS","MORNING"),payload);
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(stale)).isEqualTo("ZZAL_CHAT_SESSION_STALE");
        assertThat(llm.users.size()).isEqualTo(before);
        assertThat(sessions.count()).isEqualTo(sessionsBefore);
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_delivery",Integer.class)).isZero();
        Room other=room();
        MvcResult wrongPet=postAs(r.user(),r.path(),Map.of("sessionId",other.session(),"expectedRound",0,"requestId",UUID.randomUUID().toString(),"text","잘못된 판"));
        assertThat(wrongPet.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void partialMetadataRejectedAndLegacyClientStillWorks() throws Exception {
        Room r=room();
        MvcResult partial=postAs(r.user(),r.path(),Map.of("text","답","sessionId",r.session()));
        assertThat(partial.getResponse().getStatus()).isEqualTo(400);
        MvcResult legacy=postAs(r.user(),r.path(),Map.of("text","옛 클라이언트"));
        assertThat(legacy.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_delivery",Integer.class)).isZero();
    }
    @Test
    void replayOfCommittedModelFailureDoesNotRepeatCallsOrChangeCounters() throws Exception {
        Room r=room();
        var payload=request(r,0,UUID.randomUUID(),"응답 유실 뒤 같은 전송");
        llm.fail(new TimeoutException()).reply("broken JSON");
        MvcResult first=postAs(r.user(),r.path(),payload);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(first).path("data").path("session").path("closed").asBoolean()).isFalse();
        int calls=llm.users.size();
        int rounds=jdbc.queryForObject("select round_count from zzal_chat_session where id=?",Integer.class,r.session());
        int rows=jdbc.queryForObject("select count(*) from zzal_chat_turn where pet_id=?",Integer.class,r.pet());
        MvcResult replay=postAs(r.user(),r.path(),payload);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(replay).path("data")).isEqualTo(body(first).path("data"));
        assertThat(llm.users.size()).isEqualTo(calls);
        assertThat(jdbc.queryForObject("select round_count from zzal_chat_session where id=?",Integer.class,r.session())).isEqualTo(rounds);
        assertThat(jdbc.queryForObject("select count(*) from zzal_chat_turn where pet_id=?",Integer.class,r.pet())).isEqualTo(rows);
    }
}
