package com.lore.zzal.dev;

import com.lore.zzal.chat.session.ZzalChatSession;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionStatus;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.pet.UnlockRules;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import com.lore.zzal.piece.PieceKind;
import com.lore.zzal.piece.ZzalPiece;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 리모컨이 보는 <b>서버 상태 한 벌</b> — 화면 응답(Detail)이 감추는 내부 칸까지(케어 미스·오늘 한도·평생 횟수·조각 칸·동작 행).
 *
 * <h3>★ 같은 표가 두 가지 일을 한다</h3>
 * <ol>
 *   <li>리모컨 상태 패널의 숫자 — {@code GET /dev/pets/{id}/state} 와 모든 dev 응답의 {@code state}</li>
 *   <li>"이번에 일어난 일"({@code effects[]}) — 바꾸기 전과 뒤의 표를 칸마다 견준다({@link #diff})</li>
 * </ol>
 * 효과를 칸마다 손으로 적지 않는 이유 — 시간 점프 한 번에 케어 미스·병·떠남 예고·장면·엽서·도착이 한꺼번에 날 수 있다.
 * 그걸 이벤트마다 따로 세면 빠뜨린 한 칸이 "아무 일도 없었다" 로 보인다. 표 전체를 견주면 빠질 자리가 없다.
 *
 * ★ 시간 점프 사이의 <b>중간</b> 사건(예: 미스 2 → 3 → 4)은 남지 않는다 — 결과만 남는다. 숫자의 차이(2 → 4)로 읽는다.
 */
public final class DevSnapshot {

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZzalRules.ZONE);

    /** 칸 이름 → 사람 말. 순서가 곧 효과 목록의 순서다. */
    static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        String[][] rows = {
                {"clock", "펫 시계"}, {"phase", "생애"}, {"deathReason", "떠난 이유"},
                {"tutorialStep", "튜토리얼 칸"}, {"clockStarted", "시계 켜짐"}, {"graduationSeen", "졸업 창 봄"},
                {"sleep", "잠"}, {"wokeAt", "마지막 기상"}, {"overslept", "늦잠"}, {"napCount", "낮잠 횟수"},
                {"fullness", "배부름"}, {"happiness", "행복"}, {"trash", "똥"}, {"food", "밥 재고"},
                {"careMiss", "케어 미스"}, {"todayCareMiss", "오늘 케어 미스"}, {"lastNightCareMiss", "어젯밤 케어 미스"},
                {"zeroMissDays", "잘 돌본 날"},
                {"sick", "병"}, {"sickSince", "병 시작"}, {"naturalSickInMin", "자연 발병까지(깨어 있는 분)"},
                {"healedAt", "나은 시각"}, {"mood", "기분"},
                {"intimacy", "친밀도"}, {"intimacyPeak", "친밀도 최고치"},
                {"todayPetCount", "오늘 쓰다듬기"}, {"todayCareIntimacy", "오늘 돌봄 친밀"}, {"todayGames", "오늘 게임"},
                {"todayBathDone", "오늘 목욕"}, {"todaySnacks", "오늘 간식"}, {"todayCared", "오늘 돌봄"},
                {"feeds", "밥 횟수"}, {"pets", "쓰다듬기 횟수"}, {"cleans", "청소 횟수"}, {"bathCount", "목욕 횟수"},
                {"snacks", "간식 횟수"}, {"chatAnswers", "채팅 답"}, {"sleepWakeCount", "재우기·깨우기"},
                {"wakes", "깨우기"}, {"gameStarts", "게임 시작"}, {"shares", "공유"}, {"leftRightWins", "좌우 승"},
                {"unlocked", "열린 동작"},
                {"daysTogether", "함께한 날"}, {"lastVisitDate", "마지막 방문일"},
                {"leaveEnabled", "떠남 켜짐"}, {"leaveNotice", "떠남 예고"}, {"trip", "여행"},
                {"postcards", "엽서"}, {"leavingJustCancelled", "예고 막 취소"},
                {"absenceMin", "부재(깨어 있는 분)"}, {"scenes", "혼자 논 장면"}, {"scenesEnabled", "장면 기능"},
                {"nightScenePending", "밤 장면 대기"},
                {"layer2Status", "2층 굽기"}, {"layer2Flagged", "2층 결함 표시"}, {"layerTwoDone", "2층 완주"},
                {"piecesEnabled", "3층(조각)"}, {"pieces", "조각 칸"}, {"goodDayPending", "좋은 날 대기"},
                {"goodDayToday", "오늘 좋은 날"}, {"bonusPiece", "보너스 조각"},
                {"personality", "성격"}, {"world", "세계관"}, {"tone", "말투"}, {"genre", "장르"},
                {"background", "배경"}, {"callMe", "호칭"},
                {"chat", "채팅 판"}, {"motions", "동작 행"},
        };
        for (String[] r : rows) {
            LABELS.put(r[0], r[1]);
        }
    }

    private DevSnapshot() {
    }

    /**
     * 지금 상태 한 벌.
     *
     * @param now      펫의 지금(시계 칸)
     * @param piece    조각 줄(3층 전이면 null)
     * @param rows     동작 행(seq → 행)
     * @param scenes   남아 있는 혼자 논 장면 수
     * @param chatOpen 열려 있는 채팅 판(없으면 null)
     */
    static Map<String, Object> of(ZzalPet pet, Instant now, MotionCatalog catalog, ZzalPiece piece,
                                  Map<Integer, ZzalMotion> rows, int scenes, ZzalChatSession chatOpen) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clock", at(now));
        m.put("phase", pet.getPhase().name());
        m.put("deathReason", pet.getDeathReason() == null ? null : pet.getDeathReason().name());
        m.put("tutorialStep", pet.getTutorialStep());
        m.put("clockStarted", pet.getClockStartedAt() != null);
        m.put("graduationSeen", pet.getGraduationSeenAt() != null);
        m.put("sleep", pet.getSleepKind() == null ? "NONE" : pet.getSleepKind().name());
        m.put("wokeAt", at(pet.getWokeAt()));
        m.put("overslept", pet.isOverslept());
        m.put("napCount", pet.getNapCount());
        m.put("fullness", pet.getFullness());
        m.put("happiness", pet.getHappiness());
        m.put("trash", pet.getTrash());
        m.put("food", pet.getFood());
        m.put("careMiss", pet.getCareMiss());
        m.put("todayCareMiss", pet.getTodayCareMiss());
        m.put("lastNightCareMiss", pet.getLastNightCareMiss());
        m.put("zeroMissDays", pet.getZeroMissDays());
        m.put("sick", pet.getSickKind() == null ? "NONE" : pet.getSickKind().name());
        m.put("sickSince", at(pet.getSickSince()));
        m.put("naturalSickInMin", pet.getNaturalSickDueAwakeSec() == null ? null
                : Duration.ofSeconds(pet.getNaturalSickDueAwakeSec()).toMinutes());
        m.put("healedAt", at(pet.getHealedAt()));
        m.put("mood", pet.isAlive() ? pet.mood().name() : null);
        m.put("intimacy", pet.getIntimacy());
        m.put("intimacyPeak", pet.getIntimacyPeak());
        m.put("todayPetCount", pet.getTodayPetCount());
        m.put("todayCareIntimacy", pet.getTodayCareIntimacy());
        m.put("todayGames", pet.getTodayGames());
        m.put("todayBathDone", pet.isTodayBathDone());
        m.put("todaySnacks", pet.getTodaySnacks());
        m.put("todayCared", pet.isTodayCared());
        m.put("feeds", pet.getFeeds());
        m.put("pets", pet.getPets());
        m.put("cleans", pet.getCleans());
        m.put("bathCount", pet.getBathCount());
        m.put("snacks", pet.getSnacks());
        m.put("chatAnswers", pet.getChatAnswers());
        m.put("sleepWakeCount", pet.getSleepWakeCount());
        m.put("wakes", pet.getWakes());
        m.put("gameStarts", pet.getGameStarts());
        m.put("shares", pet.getShares());
        m.put("leftRightWins", pet.getLeftRightWins());
        m.put("unlocked", String.join(",", UnlockRules.unlockedKeys(pet, catalog)));
        m.put("daysTogether", pet.getDaysTogether());
        m.put("lastVisitDate", pet.getLastVisitDate() == null ? null : pet.getLastVisitDate().toString());
        m.put("leaveEnabled", pet.isLeaveEnabled());
        m.put("leaveNotice", at(pet.getLeaveNoticeAt()));
        m.put("trip", at(pet.getTripStartedAt()));
        m.put("postcards", pet.getPostcardCount());
        m.put("leavingJustCancelled", pet.isLeavingJustCancelled());
        m.put("absenceMin", Duration.ofSeconds(pet.getAbsenceAwakeSec()).toMinutes());
        m.put("scenes", scenes);
        m.put("scenesEnabled", pet.isScenesEnabled());
        m.put("nightScenePending", pet.needsNightScene());
        m.put("layer2Status", pet.getLayer2Status() == null ? null : pet.getLayer2Status().name());
        m.put("layer2Flagged", pet.isLayer2Flagged());
        m.put("layerTwoDone", pet.getLayerTwoDoneAt() != null);
        m.put("piecesEnabled", pet.isPiecesEnabled());
        m.put("pieces", piecesOf(piece));
        m.put("goodDayPending", pet.isGoodDayPending());
        m.put("goodDayToday", pet.isGoodDayToday());
        m.put("bonusPiece", pet.isBonusPiece());
        m.put("personality", pet.getPersonality() == null ? null : pet.getPersonality().name());
        m.put("world", pet.getWorld());
        m.put("tone", pet.getTone());
        m.put("genre", pet.getGenre());
        m.put("background", pet.getBackground());
        m.put("callMe", pet.getCallMe());
        m.put("chat", chatOpen == null ? null
                : chatOpen.getSlot().name() + "·" + chatOpen.getKind().name() + "·" + chatOpen.getRoundCount() + "턴");
        m.put("motions", motionsOf(rows));
        return m;
    }

    /** 채운 칸 이름(FOOD,PLAY…). 3층 전이면 null, 열렸는데 빈 판이면 빈 문자열. */
    private static String piecesOf(ZzalPiece piece) {
        if (piece == null) {
            return null;
        }
        List<String> done = new ArrayList<>();
        for (PieceKind k : PieceKind.values()) {
            if (piece.isDone(k)) {
                done.add(k.name());
            }
        }
        return String.join(",", done);
    }

    /**
     * 움직임이 있는 동작 행만 — {@code seq:상태(+도착)(+열람)}. NONE 이고 도착·열람이 없는 줄은 뺀다(18줄이 늘 같으면 읽을 것이 없다).
     */
    private static Map<String, String> motionsOf(Map<Integer, ZzalMotion> rows) {
        Map<String, String> out = new LinkedHashMap<>();
        rows.values().stream()
                .sorted(java.util.Comparator.comparingInt(ZzalMotion::getSeq))
                .forEach(r -> {
                    boolean idle = r.getStatus() == MotionStatus.NONE && r.getRevealedAt() == null && r.getSeenAt() == null;
                    if (idle) {
                        return;
                    }
                    String v = r.getStatus().name()
                            + (r.getRevealedAt() != null ? "+도착" : "")
                            + (r.getSeenAt() != null ? "+열람" : "");
                    out.put(r.getSeq() + "(" + r.getName() + ")", v);
                });
        return out;
    }

    private static String at(Instant t) {
        return t == null ? null : AT.format(t);
    }

    /**
     * 두 표를 칸마다 견줘 "이번에 일어난 일" 을 사람 말로. 바뀐 칸만, {@link #LABELS} 순서대로.
     * 동작 행은 줄마다 따로 적는다(어느 seq 가 어떻게 바뀌었나가 요점이다).
     */
    @SuppressWarnings("unchecked")
    static List<String> diff(Map<String, Object> before, Map<String, Object> after) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : LABELS.entrySet()) {
            String key = e.getKey();
            Object a = before.get(key);
            Object b = after.get(key);
            if (Objects.equals(a, b)) {
                continue;
            }
            if ("motions".equals(key)) {
                Map<String, String> ma = a == null ? Map.of() : (Map<String, String>) a;
                Map<String, String> mb = b == null ? Map.of() : (Map<String, String>) b;
                java.util.Set<String> seqs = new java.util.TreeSet<>(ma.keySet());
                seqs.addAll(mb.keySet());
                for (String s : seqs) {
                    if (!Objects.equals(ma.get(s), mb.get(s))) {
                        out.add("동작 %s: %s → %s".formatted(s, show(ma.get(s), "NONE"), show(mb.get(s), "NONE")));
                    }
                }
                continue;
            }
            out.add("%s: %s → %s".formatted(e.getValue(), show(a, "없음"), show(b, "없음")));
        }
        return out;
    }

    private static String show(Object v, String empty) {
        if (v == null) {
            return empty;
        }
        if (v instanceof Boolean bool) {
            return bool ? "예" : "아니오";
        }
        String s = String.valueOf(v);
        return s.isEmpty() ? "빈칸" : s;
    }
}
