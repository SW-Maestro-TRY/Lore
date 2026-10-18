package com.lore.zzal.pet;

import com.lore.zzal.motion.MotionStatus;
import com.lore.zzal.piece.PieceKind;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 개발 리모컨 — 펫 상태를 <b>값으로 직접</b> 세팅한다({@code PATCH /api/zzal/v1/dev/pets/{id}/state}).
 *
 * <h3>★ 규칙</h3>
 * <ul>
 *   <li><b>부분 갱신</b> — null 인 칸은 안 건드린다. 비우는 것은 칸마다 정한 값으로 한다(병 {@code "NONE"},
 *       잠 {@code "NONE"}, 글자 칸은 빈 문자열, 자연 발병 예약은 {@code -1}).</li>
 *   <li><b>검증은 범위만</b> — 칸마다 정해진 범위를 넘으면 400 이고 아무것도 안 바뀐다.</li>
 *   <li><b>연쇄는 안 돌린다</b> — 똥을 4로 두어도 그 자리에서 병이 나지 않는다. 시간을 넘기면(시계) 규칙대로 이어진다.
 *       그래서 0·4 같은 경계값을 넣으면 "그 상태가 막 된 것" 처럼 타이머를 맞춰 둔다(배부름 0 → 6시간 뒤 케어 미스).</li>
 * </ul>
 *
 * <h3>★ 왜 엔티티 패키지에 두나</h3>
 * 엔티티 칸을 바꾸는 곳을 {@link ZzalPet#devApply} 한 곳으로 모으려고. 값을 고치는 길이 여기저기 생기면
 * 운영 규칙이 어느 칸을 믿어도 되는지 흐려진다. 이 레코드는 dev 컨트롤러만 받는다(운영에서는 그 주소가 없다).
 */
@Schema(description = "개발 리모컨 상태 세팅 — 준 칸만 바꾼다. 범위를 넘으면 400, 연쇄(병·떠남)는 시간이 지나야 이어진다")
public record DevPetPatch(
        // 1 생애
        @Schema(description = "DRAFT · HATCHING · ALIVE · FAILED · DEAD") PetPhase phase,
        @Schema(description = "HATCH_FAILED · NEGLECTED · RELEASED (DEAD·FAILED 일 때)") DeathReason deathReason,
        // 2·3 튜토리얼
        @Schema(description = "튜토리얼 칸 0~9. 9 = 끝(시계가 지금 켜진다), 0~8 = 튜토리얼 중으로 되돌린다", example = "0")
        Integer tutorialStep,
        @Schema(description="첫 안내 버전 2, 3 또는 4. key 없이 바꾸면 현재 key를 보존한다") Integer firstIntroVersion,
        @Schema(description="가짜 체험 상태: FEED부터 COMPLETE까지 서버 첫 안내 key") FirstIntro.Step firstIntroKey,
        @Schema(description = "졸업 축하 창을 봤나. false 면 다시 뜬다") Boolean graduationSeen,
        // 4~7 게이지·밥
        @Schema(description = "배부름 0~4", example = "4") Integer fullness,
        @Schema(description = "행복 0~4", example = "4") Integer happiness,
        @Schema(description = "똥(흔적) 0~4", example = "4") Integer trash,
        @Schema(description = "밥 재고 0~3", example = "3") Integer food,
        // 8·9 케어 미스
        @Schema(description = "누적 케어 미스 0~99") Integer careMiss,
        @Schema(description = "오늘 케어 미스 0~99") Integer todayCareMiss,
        @Schema(description = "어젯밤 케어 미스 스냅샷 0~99") Integer lastNightCareMiss,
        @Schema(description = "잘 돌본 날 수 0~999") Integer zeroMissDays,
        // 11·12 병
        @Schema(description = "병 — NONE · NEGLECT · DIRTY · UPSET · NATURAL", example = "DIRTY") String sick,
        @Schema(description = "병이 난 지 몇 시간(깨어 있는 시간 기준 24시간마다 케어 미스). 0~240") Integer sickHoursAgo,
        @Schema(description = "자연 발병까지 깨어 있는 분. -1 이면 예약을 지운다") Integer naturalSickInMinutes,
        // 13~15 잠
        @Schema(description = "잠 — NONE · NIGHT · NAP (훅은 안 돈다 — 밤 정산은 events/night-settle)") String sleep,
        @Schema(description = "늦잠 표시") Boolean overslept,
        @Schema(description = "낮잠 횟수 0~1") Integer napCount,
        // 18·19 친밀·오늘 한도
        @Schema(description = "친밀도 0~999 (최고치는 따라 올라간다)") Integer intimacy,
        @Schema(description = "친밀도 최고치 0~999 (재회 시 50%)") Integer intimacyPeak,
        @Schema(description = "오늘 쓰다듬기 0~3") Integer todayPetCount,
        @Schema(description = "오늘 돌봄 친밀도 0~30") Integer todayCareIntimacy,
        @Schema(description = "오늘 게임 판 수 0~3") Integer todayGames,
        @Schema(description = "오늘 목욕했나") Boolean todayBathDone,
        @Schema(description = "오늘 간식 0~5") Integer todaySnacks,
        @Schema(description = "오늘 한 번이라도 돌봤나(잘 돌본 날 판정)") Boolean todayCared,
        // 20·21 평생 횟수(2층 해금 재료)
        @Schema(description = "밥 횟수") Integer feeds,
        @Schema(description = "쓰다듬기 횟수") Integer pets,
        @Schema(description = "청소 횟수") Integer cleans,
        @Schema(description = "목욕 횟수") Integer bathCount,
        @Schema(description = "간식 횟수") Integer snacks,
        @Schema(description = "채팅 답 횟수") Integer chatAnswers,
        @Schema(description = "재우기+깨우기 횟수") Integer sleepWakeCount,
        @Schema(description = "손으로 깨운 횟수") Integer wakes,
        @Schema(description = "게임 시작 횟수") Integer gameStarts,
        @Schema(description = "공유 횟수") Integer shares,
        @Schema(description = "좌우 맞히기 승수(5 → 달리기)") Integer leftRightWins,
        // 22~25·27·28 방문·떠남·부재
        @Schema(description = "함께한 날 1~9999") Integer daysTogether,
        @Schema(description = "마지막 방문이 며칠 전인가 0~60(결석 떠남 재현)") Integer lastVisitDaysAgo,
        @Schema(description = "떠남 켜기") Boolean leaveEnabled,
        @Schema(description = "떠남 예고 — true 면 지금 예고, false 면 지운다") Boolean leaveNotice,
        @Schema(description = "여행 중 — true 면 지금 출발(엽서 0), false 면 지운다(재회 값은 events/call-back)") Boolean trip,
        @Schema(description = "예고 막 취소됨 연출") Boolean leavingJustCancelled,
        @Schema(description = "부재(깨어 있는) 시간 0~72 — 4시간마다 혼자 논 장면 1컷") Integer absenceHours,
        // 30·31·33 2층·3층
        @Schema(description = "2층 굽기 상태 — PENDING · RUNNING · READY · FAILED") String layer2Status,
        @Schema(description = "2층 결함 표시") Boolean layer2Flagged,
        @Schema(description = "2층 8종 완주 표시(다음 기상에 3층)") Boolean layerTwoDone,
        @Schema(description = "3층(조각) 열림") Boolean piecesEnabled,
        @Schema(description = "기분 좋은 날 판정 대기(내일 아침 보너스)") Boolean goodDayPending,
        @Schema(description = "오늘이 기분 좋은 날") Boolean goodDayToday,
        @Schema(description = "오늘 보너스 조각 받음 표시") Boolean bonusPiece,
        @Schema(description = "조각 칸별 채움 — FOOD · PLAY · CLEAN · BOND → true/false (3층이 열려 있어야 한다)")
        Map<PieceKind, Boolean> pieces,
        // 35·36 성격·호칭
        @Schema(description = "대표 성격 — GENTLE · LIVELY · SHY · CLINGY") Personality personality,
        @Schema(description = "세계관(빈 문자열이면 지운다)") String world,
        @Schema(description = "말투(빈 문자열이면 지운다)") String tone,
        @Schema(description = "장르(빈 문자열이면 지운다)") String genre,
        @Schema(description = "배경 키(빈 문자열이면 지운다)") String background,
        @Schema(description = "호칭(빈 문자열이면 지운다)") String callMe,
        // 37 동작 행
        @Schema(description = "동작 행 상태 — seq 마다 status·도착·열람") List<DevMotionPatch> motions,
        // 40
        @Schema(description = "방금 나음 표시(healedAt). true 면 지금, false 면 지운다") Boolean healed) {

    /** 동작 행 하나. status 는 {@link MotionStatus} 이름, revealed·seen 은 도착·열람 시각을 지금/없음으로. */
    @Schema(description = "동작 행 세팅")
    public record DevMotionPatch(
            @Schema(description = "카탈로그 seq", example = "101") int seq,
            @Schema(description = "NONE · QUEUED · BAKING · REVIEW · LOCAL_REQUESTED · OPEN · FAILED · HOLD") String status,
            @Schema(description = "도착(revealedAt) — true 면 지금, false 면 지운다") Boolean revealed,
            @Schema(description = "열람(seenAt) — true 면 지금, false 면 지운다") Boolean seen) {
    }

    static final int COUNT_MAX = 9999;

    /**
     * 범위만 본다. 틀린 칸마다 한 줄 — 비어 있으면 통과.
     *
     * ★ 한 칸이라도 틀리면 <b>아무것도</b> 안 바꾼다(부르는 쪽이 이 목록을 먼저 본다). 일부만 바뀌면
     *   "똥 4 · 배부름 9" 를 보냈을 때 똥만 바뀐 채 400 이 나가, 화면과 서버가 무엇이 바뀌었는지 서로 다르게 안다.
     */
    public List<String> violations() {
        List<String> out = new ArrayList<>();
        range(out, "tutorialStep", tutorialStep, 0, TutorialSchedule.TOTAL);
        range(out, "firstIntroVersion", firstIntroVersion, 2, 4);
        if (tutorialStep != null && (firstIntroVersion != null || firstIntroKey != null))
            out.add("tutorialStep 과 firstIntroVersion/key 는 함께 바꿀 수 없습니다");
        range(out, "fullness", fullness, 0, ZzalRules.GAUGE_MAX);
        range(out, "happiness", happiness, 0, ZzalRules.GAUGE_MAX);
        range(out, "trash", trash, 0, ZzalRules.TRASH_MAX);
        range(out, "food", food, 0, ZzalRules.FOOD_MAX);
        range(out, "careMiss", careMiss, 0, 99);
        range(out, "todayCareMiss", todayCareMiss, 0, 99);
        range(out, "lastNightCareMiss", lastNightCareMiss, 0, 99);
        range(out, "zeroMissDays", zeroMissDays, 0, 999);
        range(out, "sickHoursAgo", sickHoursAgo, 0, 240);
        range(out, "naturalSickInMinutes", naturalSickInMinutes, -1, (int) ZzalRules.SICK_NATURAL_WINDOW_AWAKE.toMinutes());
        range(out, "napCount", napCount, 0, ZzalRules.NAP_MAX);
        range(out, "intimacy", intimacy, 0, ZzalRules.INTIMACY_MAX);
        range(out, "intimacyPeak", intimacyPeak, 0, ZzalRules.INTIMACY_MAX);
        range(out, "todayPetCount", todayPetCount, 0, ZzalRules.PET_INTIMACY_PER_DAY);
        range(out, "todayCareIntimacy", todayCareIntimacy, 0, ZzalRules.CARE_INTIMACY_DAILY_CAP);
        range(out, "todayGames", todayGames, 0, 3);
        range(out, "todaySnacks", todaySnacks, 0, ZzalRules.SNACK_DAILY_SICK_AT);
        range(out, "feeds", feeds, 0, COUNT_MAX);
        range(out, "pets", pets, 0, COUNT_MAX);
        range(out, "cleans", cleans, 0, COUNT_MAX);
        range(out, "bathCount", bathCount, 0, COUNT_MAX);
        range(out, "snacks", snacks, 0, COUNT_MAX);
        range(out, "chatAnswers", chatAnswers, 0, COUNT_MAX);
        range(out, "sleepWakeCount", sleepWakeCount, 0, COUNT_MAX);
        range(out, "wakes", wakes, 0, COUNT_MAX);
        range(out, "gameStarts", gameStarts, 0, COUNT_MAX);
        range(out, "shares", shares, 0, COUNT_MAX);
        range(out, "leftRightWins", leftRightWins, 0, COUNT_MAX);
        range(out, "daysTogether", daysTogether, 1, COUNT_MAX);
        range(out, "lastVisitDaysAgo", lastVisitDaysAgo, 0, 60);
        range(out, "absenceHours", absenceHours, 0, 72);
        name(out, "sick", sick, "NONE", "NEGLECT", "DIRTY", "UPSET", "NATURAL");
        name(out, "sleep", sleep, "NONE", "NIGHT", "NAP");
        name(out, "layer2Status", layer2Status, "PENDING", "RUNNING", "READY", "FAILED");
        length(out, "world", world, ZzalRules.WORLD_MAX_CHARS);
        length(out, "tone", tone, ZzalRules.TONE_MAX_CHARS);
        length(out, "genre", genre, ZzalRules.GENRE_MAX_CHARS);
        length(out, "background", background, 32);
        length(out, "callMe", callMe, 20);
        if (motions != null) {
            for (DevMotionPatch m : motions) {
                if (m == null) {
                    out.add("motions: 빈 항목");
                    continue;
                }
                if (m.status() != null) {
                    try {
                        MotionStatus.valueOf(m.status());
                    } catch (IllegalArgumentException e) {
                        out.add("motions[%d].status: %s 는 없는 상태".formatted(m.seq(), m.status()));
                    }
                }
            }
        }
        return out;
    }

    private static void range(List<String> out, String field, Integer v, int min, int max) {
        if (v != null && (v < min || v > max)) {
            out.add("%s: %d 는 %d~%d 밖".formatted(field, v, min, max));
        }
    }

    private static void name(List<String> out, String field, String v, String... allowed) {
        if (v != null && !List.of(allowed).contains(v)) {
            out.add("%s: %s 는 %s 중 하나가 아님".formatted(field, v, String.join("·", allowed)));
        }
    }

    private static void length(List<String> out, String field, String v, int max) {
        if (v != null && v.length() > max) {
            out.add("%s: %d자 — %d자까지".formatted(field, v.length(), max));
        }
    }
}
