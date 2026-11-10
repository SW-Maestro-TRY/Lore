package com.lore.zzal.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.lore.zzal.motion.MotionSeeder;
import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import com.lore.zzal.pet.*;
import com.lore.zzal.pet.persona.AuthoredPersona;
import com.lore.zzal.pet.persona.FirstMeeting;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.TestPropertySource;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 개발 리모컨(remote-control-spec-1011) — <b>서버를 진짜로 바꾸는가</b>.
 *
 * <h3>★ 왜 운영에 안 나가는 코드를 시험하나</h3>
 * 상훈님이 리모컨을 눌러 보고 "이 상황에서 이렇게 된다" 를 판단한다. 리모컨이 조용히 틀리면(값은 바뀐 척, 서버는 그대로)
 * 그 판단이 전부 거짓 위에 선다 — 지금까지의 화면 덮어쓰기가 정확히 그 모양이었다. 그래서 응답이 아니라
 * <b>다음 조회에서 서버가 같은 값을 말하는지</b>까지 본다.
 */
@ZzalIntegrationTest
@TestPropertySource(properties="app.zzal.attendance.published-backgrounds=[{\"key\":\"images/fixture/modern.webp\",\"url\":\"/images/fixture/modern.webp\",\"world\":\"modern_daily\",\"tags\":[],\"published\":true,\"autoCheck\":\"PASS\",\"humanReview\":\"UNREVIEWED\"}]")
@DisplayName("개발 리모컨 — 상태 세팅·이벤트·방문 없는 시계·굽기")
class DevRemoteIT extends ZzalItSupport {

    @Autowired MotionSeeder motionSeeder;

    private static final String DEV = "/api/zzal/v1/dev/pets/%d";

    /** 튜토리얼을 끝낸 아이(시계 켜짐) — 오늘 KST 11:00 에 세운다. 동작 행도 깐다(선물 굽기). */
    private ZzalPet pet(Long userId) {
        Instant anchor = kstToday(11, 0);
        ZzalPet pet = transactions.execute(status -> {
            ZzalPet created = petRepository.save(ZzalPet.draft(userId, newUploadedImageKey(userId), anchor));
            created.character("여울", null, null, null, null, null, anchor);
            created.markAlive("images/zzal/pets/sheet.png", "(시험용 정체성 문단)", anchor);
            created.skipTutorial(anchor);
            return created;
        });
        motionSeeder.seed(pet.getId(), anchor);
        pinClock(pet.getId(), anchor);
        return pet;
    }

    private MvcResult patchAs(Long userId, String path, Object body) throws Exception {
        return mockMvc.perform(patch(path).with(asUser(userId)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
    }

    private JsonNode ok(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as("응답=%s", r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r).path("data");
    }

    private JsonNode state(Long userId, Long petId) throws Exception {
        return getJson(userId, (DEV + "/state").formatted(petId)).path("data").path("state");
    }

    private static List<String> effects(JsonNode data) {
        List<String> out = new ArrayList<>();
        data.path("effects").forEach(e -> out.add(e.asText()));
        return out;
    }

    @Test
    @DisplayName("ping — 200 · devTools=true · 진짜 생성 스위치는 꺼짐(시험)")
    void pingSaysOn() throws Exception {
        JsonNode data = getJson(newUserId(), "/api/zzal/v1/dev/ping").path("data");
        assertThat(data.path("devTools").asBoolean()).isTrue();
        assertThat(data.path("generationReal").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("★★ 세팅은 서버에 남는다 — 똥 4 · 배부름 0 · 병 DIRTY 가 다음 조회에서도 그대로")
    void patchStaysOnServer() throws Exception {
        Long userId = newUserId();
        Long petId = pet(userId).getId();

        JsonNode data = ok(patchAs(userId, (DEV + "/state").formatted(petId),
                Map.of("trash", 4, "fullness", 0, "sick", "DIRTY", "careMiss", 3)));
        assertThat(effects(data)).anyMatch(e -> e.startsWith("똥:") && e.endsWith("→ 4"));
        assertThat(effects(data)).anyMatch(e -> e.startsWith("병:") && e.endsWith("→ DIRTY"));
        assertThat(data.path("after").path("gauges").path("trash").asInt()).isEqualTo(4);

        // 다음 조회(사용자 경로)가 같은 값을 말한다 — 화면 덮어쓰기가 아니다
        JsonNode detail = getJson(userId, "/api/zzal/v1/me/pets/" + petId).path("data");
        assertThat(detail.path("gauges").path("trash").asInt()).isEqualTo(4);
        assertThat(detail.path("gauges").path("fullness").asInt()).isZero();
        assertThat(detail.path("mood").asText()).isEqualTo("SICK");
        assertThat(state(userId, petId).path("careMiss").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("★ 범위 밖이면 400 이고 <b>아무것도</b> 안 바뀐다(일부만 바뀌지 않는다)")
    void outOfRangeChangesNothing() throws Exception {
        Long userId = newUserId();
        Long petId = pet(userId).getId();
        int trashBefore = state(userId, petId).path("trash").asInt();

        MvcResult r = patchAs(userId, (DEV + "/state").formatted(petId), Map.of("trash", 4, "fullness", 9));
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(state(userId, petId).path("trash").asInt()).isEqualTo(trashBefore);
    }

    @Test
    @DisplayName("★★ 방문 없이 하루씩 7번 → 결석 떠남 예고 → 여행 출발, 부르기로 돌아온다")
    void absenceLeavesWithoutVisit() throws Exception {
        Long userId = newUserId();
        Long petId = pet(userId).getId();
        String advance = (DEV + "/advance-clock").formatted(petId);

        List<String> all = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            all.addAll(effects(ok(postAs(userId, advance, Map.of("minutes", 24 * 60, "visit", false)))));
        }
        assertThat(all).as("일어난 일=%s", all).anyMatch(e -> e.startsWith("떠남 예고: 없음 →"));
        assertThat(all).anyMatch(e -> e.startsWith("여행: 없음 →"));
        assertThat(state(userId, petId).path("trip").isNull()).isFalse();

        JsonNode back = ok(postAs(userId, (DEV + "/events/call-back").formatted(petId), null));
        assertThat(back.path("state").path("trip").isNull()).isTrue();
        assertThat(back.path("state").path("careMiss").asInt()).isZero();
    }

    @Test
    @DisplayName("대조 — 같은 7일을 방문하며 밀면(기본) 결석 예고가 안 난다")
    void visitingEveryDayNeverLeavesForAbsence() throws Exception {
        Long userId = newUserId();
        Long petId = pet(userId).getId();
        // 케어 미스 8 로 떠나는 길을 막는다 — 결석 쪽만 본다
        ok(patchAs(userId, (DEV + "/state").formatted(petId), Map.of("leaveEnabled", true)));
        String advance = (DEV + "/advance-clock").formatted(petId);
        for (int i = 0; i < 7; i++) {
            ok(postAs(userId, advance, Map.of("minutes", 24 * 60)));
            ok(patchAs(userId, (DEV + "/state").formatted(petId), Map.of("careMiss", 0, "fullness", 4, "happiness", 4, "trash", 0)));
        }
        assertThat(state(userId, petId).path("leaveNotice").isNull()).isTrue();
        assertThat(state(userId, petId).path("trip").isNull()).isTrue();
    }

    @Test
    @DisplayName("이벤트 — 밤 정산은 잠들고 하루 한도를 비운다, 손으로 깨우면 깨우기 횟수가 오른다")
    void nightSettleThenWake() throws Exception {
        Long userId = newUserId();
        Long petId = pet(userId).getId();
        ok(patchAs(userId, (DEV + "/state").formatted(petId), Map.of("todayGames", 3)));

        JsonNode slept = ok(postAs(userId, (DEV + "/events/night-settle").formatted(petId), null));
        assertThat(slept.path("state").path("sleep").asText()).isEqualTo("NIGHT");
        assertThat(slept.path("state").path("todayGames").asInt()).isZero();

        JsonNode woke = ok(postAs(userId, (DEV + "/events/wake").formatted(petId), null));
        assertThat(woke.path("state").path("sleep").asText()).isEqualTo("NONE");
        // 손으로 깨웠다 — 2층 '일어나기' 재료(깨우기 횟수)가 오른다
        assertThat(effects(woke)).contains("잠: NIGHT → NONE", "깨우기: 0 → 1");
    }

    @Test
    @DisplayName("이벤트 — 강제 발병(종류 지정)·약, 모르는 이벤트는 400")
    void sickAndRecover() throws Exception {
        Long userId = newUserId();
        Long petId = pet(userId).getId();
        JsonNode sick = ok(postAs(userId, (DEV + "/events/sick?arg=UPSET").formatted(petId), null));
        assertThat(sick.path("state").path("sick").asText()).isEqualTo("UPSET");
        JsonNode healed = ok(postAs(userId, (DEV + "/events/recover").formatted(petId), null));
        assertThat(healed.path("state").path("sick").asText()).isEqualTo("NONE");

        assertThat(postAs(userId, (DEV + "/events/fly").formatted(petId), null).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("이벤트 — 게임 승은 실제 판 길로, 구 해금 조건을 채워도 새 구보상은 열리지 않는다")
    void gameWinAndUnlock() throws Exception {
        Long userId = newUserId();
        Long petId = pet(userId).getId();
        int wins = state(userId, petId).path("leftRightWins").asInt();
        JsonNode won = ok(postAs(userId, (DEV + "/events/game-win").formatted(petId), null));
        assertThat(won.path("state").path("leftRightWins").asInt()).isEqualTo(wins + 1);

        ok(patchAs(userId, (DEV + "/state").formatted(petId), Map.of("layer2Status", "READY")));
        JsonNode unlocked = ok(postAs(userId, (DEV + "/events/unlock?arg=all").formatted(petId), null));
        assertThat(unlocked.path("after").path("justUnlocked").size()).isZero();
    }

    @Test
    @DisplayName("이벤트 — 첫 안내 처음부터: FEED·시계 꺼짐·졸업 창 다시")
    void tutorialRestart() throws Exception {
        Long userId = newUserId();
        Long petId = pet(userId).getId();
        JsonNode r = ok(postAs(userId, (DEV + "/events/tutorial-restart").formatted(petId), null));
        assertThat(r.path("state").path("firstIntroKey").asText()).isEqualTo("FEED");
        assertThat(r.path("state").path("clockStarted").asBoolean()).isFalse();
        assertThat(r.path("after").path("firstIntro").path("active").asBoolean()).isTrue();
        assertThat(r.path("after").path("firstIntro").path("key").asText()).isEqualTo("FEED");
    }

    @Test
    @DisplayName("폐기된 선물 굽기는 개발 리모컨에서도 거절하고 작업을 만들지 않는다")
    void bakeRespectsTheSwitchAndRetirement() throws Exception {
        Long userId=newUserId();Long petId=pet(userId).getId();String bake=(DEV+"/bake").formatted(petId);
        var refused=postAs(userId,bake,Map.of("kind","gift","real",true));
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(refused).path("error").path("message").asText()).contains("과금 스위치 꺼짐");
        assertThat(postAs(userId,bake,Map.of("kind","gift","real",false)).getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select count(*) from zzal_gen_job where pet_id=?",Integer.class,petId)).isZero();
    }

    @Test
    @DisplayName("★ 남의 펫은 리모컨으로도 못 바꾼다(404)")
    void strangersGet404() throws Exception {
        Long owner = newUserId();
        Long stranger = newUserId();
        Long petId = pet(owner).getId();
        assertThat(patchAs(stranger, (DEV + "/state").formatted(petId), Map.of("trash", 4)).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(postAs(stranger, (DEV + "/events/sick").formatted(petId), null).getResponse().getStatus())
                .isEqualTo(404);
    }

    // 최신 버전형 첫 안내/저장 인사/출석 연결 회귀를 기존 리모컨 검수와 함께 보존한다.
    record Room(Long user,Long id){String remote(){return "/api/zzal/v1/dev/pets/"+id;} String normal(){return "/api/zzal/v1/me/pets/"+id;}}
    Room room(boolean intro){
        Long user=newUserId();Instant at=kstToday(3,0);
        Long id=transactions.execute(t->{var p=ZzalPet.draft(user,newUploadedImageKey(user),at);
            p.character("아이",null,null,null,null,null,at);p.markAlive("sheet","identity",at);
            if(intro){p.recordAuthoredPersona(new AuthoredPersona(1,List.of()));p.beginFirstMeeting();p.devSetFirstIntro(3,FirstIntro.Step.FEED,at);p.advanceFirstMeeting(FirstMeeting.State.COMPLETE);}
            return petRepository.save(p).getId();});
        motionSeeder.seed(id,at);pinClock(id,at);return new Room(user,id);
    }
    com.fasterxml.jackson.databind.JsonNode patchRoom(Room r,Map<String,Object> fields)throws Exception{
        var result=mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(r.remote()+"/state").with(asUser(r.user)).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(fields))).andExpect(status().isOk()).andReturn();
        return body(result).path("data");
    }
    com.fasterxml.jackson.databind.JsonNode event(Room r,String kind)throws Exception{
        var result=postAs(r.user,r.remote()+"/events/"+kind,null);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        return body(result).path("data");
    }
    int visits(Room r){return jdbc.queryForObject("SELECT count(*) FROM zzal_awake_visit WHERE pet_id=?",Integer.class,r.id);}

    @ParameterizedTest @ValueSource(ints={2,3,4})
    void remoteReplaysVersionedIntroAndPreviewsWithoutRecordingAVisit(int version)throws Exception{
        var r=room(true);
        patchRoom(r,Map.of("firstIntroVersion",version,"firstIntroKey","BATH","todayBathDone",true));
        var restart=event(r,"tutorial-restart");
        assertThat(restart.path("state").path("firstIntroVersion").asInt()).isEqualTo(version);
        assertThat(restart.path("state").path("firstIntroKey").asText()).isEqualTo("FEED");
        // A restarted mandatory action follows the real care path, not just the remote counter.
        petService.care(r.user,r.id,CareAction.FEED,Instant.now());
        assertThat(petRepository.findById(r.id).orElseThrow().introAt(FirstIntro.Step.SNACK)).isTrue();
        var sequence=FirstIntro.sequence(version);
        for(int i=sequence.indexOf(FirstIntro.Step.SNACK);i<sequence.indexOf(FirstIntro.Step.FINISH_NOTICE);i++){
            var next=event(r,"tutorial-next");
            assertThat(next.path("state").path("firstIntroKey").asText()).isEqualTo(sequence.get(i+1).name());
            assertThat(getJson(r.user,r.remote()+"/state").path("data").path("state").path("firstIntroKey").asText()).isEqualTo(sequence.get(i+1).name());
        }
        var state=getJson(r.user,r.remote()+"/state").path("data");
        assertThat(state.path("detail").path("attendanceRewards").path("background").path("preview").path("key").asText()).isEqualTo("images/fixture/modern.webp");
        assertThat(state.path("detail").path("clock").path("clockStartedAt").isNull()).isTrue();
        assertThat(visits(r)).isZero();
        assertThat(postAs(r.user,r.remote()+"/events/tutorial-next",null).getResponse().getStatus()).isEqualTo(409);
        var done=event(r,"clock-start");
        assertThat(done.path("state").path("firstIntroKey").asText()).isEqualTo("REVIEW_SEEN");
        assertThat(visits(r)).isEqualTo(1);
        event(r,"tutorial-next");
        assertThat(petRepository.findById(r.id).orElseThrow().introAt(FirstIntro.Step.COMPLETE)).isTrue();
    }

    @Test void versionSwitchPreservesSemanticKeyAndDirectPreviewPatchIsNotCompletion()throws Exception{
        var r=room(true);patchRoom(r,Map.of("firstIntroKey","CHAT"));
        var changed=patchRoom(r,Map.of("firstIntroVersion",2));
        assertThat(changed.path("state").path("firstIntroKey").asText()).isEqualTo("CHAT");
        assertThat(changed.path("state").path("firstIntroStep").asInt()).isEqualTo(FirstIntro.index(2,FirstIntro.Step.CHAT));
        var preview=patchRoom(r,Map.of("firstIntroKey","FINISH_NOTICE"));
        assertThat(preview.path("after").path("attendanceRewards").path("background").path("preview").path("key").asText()).isEqualTo("images/fixture/modern.webp");
        assertThat(preview.path("after").path("clock").path("clockStartedAt").isNull()).isTrue();assertThat(visits(r)).isZero();
        patchRoom(r,Map.of("firstIntroKey","NAP_WAKE"));
        assertThat(petRepository.findById(r.id).orElseThrow().getSleepKind()).isEqualTo(SleepKind.NAP);
        petService.wake(r.user,r.id,Instant.now());
        assertThat(petRepository.findById(r.id).orElseThrow().introAt(FirstIntro.Step.ALBUM)).isTrue();
    }

    @Test void finishingReviewPreservesTheNightStartedByClockCompletion()throws Exception{
        var r=room(true);pinClock(r.id,kstToday(23,30));
        patchRoom(r,Map.of("firstIntroKey","FINISH_NOTICE"));event(r,"clock-start");
        var before=petRepository.findById(r.id).orElseThrow();
        assertThat(before.getSleepKind()).isEqualTo(SleepKind.NIGHT);
        Instant started=before.getClockStartedAt();
        event(r,"tutorial-next");var complete=petRepository.findById(r.id).orElseThrow();
        assertThat(complete.getSleepKind()).isEqualTo(SleepKind.NIGHT);
        assertThat(complete.getClockStartedAt()).isEqualTo(started);
    }

    @Test void tutorialSelectionNeverSilentlyCompletesTheFirstMeeting()throws Exception{
        var r=room(true);
        transactions.executeWithoutResult(tx->petRepository.findByIdForUpdate(r.id).orElseThrow().advanceFirstMeeting(FirstMeeting.State.DISCOVERED));
        var response=patchRoom(r,Map.of("firstIntroKey","FINISH_NOTICE"));
        assertThat(response.path("state").path("firstMeetingState").asText()).isEqualTo("DISCOVERED");
        assertThat(petRepository.findById(r.id).orElseThrow().isFirstMeetingComplete()).isFalse();
    }

    @Test void forcedSkipCompletesOnceAndRecordsDayOneWithoutLosingTheVersion()throws Exception{
        var r=room(true);patchRoom(r,Map.of("firstIntroVersion",2));
        var skipped=event(r,"tutorial-skip");
        assertThat(skipped.path("state").path("firstIntroKey").asText()).isEqualTo("COMPLETE");
        assertThat(skipped.path("state").path("firstIntroVersion").asInt()).isEqualTo(2);
        assertThat(visits(r)).isEqualTo(1);
        assertThat(postAs(r.user,r.remote()+"/events/tutorial-skip",null).getResponse().getStatus()).isEqualTo(409);
        assertThat(visits(r)).isEqualTo(1);
    }

    @Test void paidModeMismatchCannotStartGeneration()throws Exception{
        var r=room(true);
        assertThat(postAs(r.user,r.remote()+"/bake",Map.of("kind","hatch","real",true)).getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM zzal_gen_job WHERE pet_id=?",Integer.class,r.id)).isZero();
        assertThat(petRepository.findById(r.id).orElseThrow().isAlive()).isTrue();
    }

    @Test void ownershipValidationAndLegacyControlsRemainEffective()throws Exception{
        var r=room(false);Long other=newUserId();
        assertThat(postAs(other,r.remote()+"/events/tutorial-restart",null).getResponse().getStatus()).isEqualTo(404);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(r.remote()+"/state").with(asUser(r.user)).contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"fullness\":9,\"trash\":4}")).andExpect(status().isBadRequest());
        assertThat(petRepository.findById(r.id).orElseThrow().getTrash()).isNotEqualTo(4);
        patchRoom(r,Map.of("tutorialStep",8));event(r,"clock-start");assertThat(visits(r)).isEqualTo(1);
        patchRoom(r,Map.of("fullness",4,"happiness",4,"food",3));
        event(r,"sick");assertThat(petRepository.findById(r.id).orElseThrow().isSick()).isTrue();
        event(r,"recover");assertThat(petRepository.findById(r.id).orElseThrow().isSick()).isFalse();
        event(r,"trip-start");event(r,"postcard");
        assertThat(petRepository.findById(r.id).orElseThrow().getPostcardCount()).isGreaterThan(0);
        // Clock responses keep before/after/effects and visit:false never adds attendance.
        var advance=postAs(r.user,r.remote()+"/advance-clock",Map.of("minutes",1,"visit",false));
        assertThat(advance.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(advance).path("data").has("after")).isTrue();assertThat(visits(r)).isEqualTo(1);
    }
    @Test void v4RemoteReviewNextAndSkipUseTheCurrentNightBoundary()throws Exception{
        var r=room(true);patchRoom(r,Map.of("firstIntroVersion",4,"firstIntroKey","FINISH_NOTICE"));
        event(r,"clock-start");assertThat(petRepository.findById(r.id).orElseThrow().isSleeping()).isFalse();
        event(r,"tutorial-next");assertThat(petRepository.findById(r.id).orElseThrow().getFirstNightChatDeadlineAt()).isNotNull();
        assertThat(petRepository.findById(r.id).orElseThrow().isSleeping()).isFalse();
        var other=room(true);patchRoom(other,Map.of("firstIntroVersion",4));event(other,"tutorial-skip");
        assertThat(petRepository.findById(other.id).orElseThrow().getFirstNightChatDeadlineAt()).isNotNull();
        assertThat(petRepository.findById(other.id).orElseThrow().isSleeping()).isFalse();
        assertThat(visits(other)).isEqualTo(1);
    }
}
