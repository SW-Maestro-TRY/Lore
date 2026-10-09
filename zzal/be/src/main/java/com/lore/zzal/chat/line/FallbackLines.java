package com.lore.zzal.chat.line;

import com.lore.zzal.chat.persona.PersonaSheet;
import com.lore.zzal.chat.session.TurnType;
import com.lore.zzal.pet.Personality;
import com.lore.zzal.text.Josa;

import java.util.List;
import java.util.Map;

/**
 * 폴백 고정 문형 — 턴 묶음(여는 턴 · 이어 말하기 · 닫기) × 성격 5 + 성격 없음.
 * LLM 이 꺼져 있거나 실패·금칙·시간 초과일 때 이 줄이 나간다. 지금 템플릿(옛 {@code ChatTemplates})을 이 모양으로 재편했다.
 *
 * <h3>끼우는 것 — 셋뿐이고 전부 작가·사용자가 정한 값</h3>
 * <ul>
 *   <li>{@code {이름}} — 펫 이름(여는 턴에만)</li>
 *   <li>{@code {호칭}} — 호칭이 있으면 "누나, " 처럼 앞에 붙고, 없으면 빈칸</li>
 *   <li>{@code {곳}} — 세계관 칩 하나를 장소 낱말로(현대→동네, 학교→학교 …). 칩이 없으면 "곳 없는" 문형을 쓴다</li>
 * </ul>
 * ★ 질문으로 끝나는 줄이 없다 — 폴백은 흐름을 안전하게 잇는 자리라, 대답을 강요하지 않는다.
 * ★ 원망·떠남·상태 죄책감이 없다(원망 필터를 그래도 한 번 더 거친다).
 */
public final class FallbackLines {

    private FallbackLines() {
    }

    enum Group { OPEN, CONTINUE, CLOSE }

    /** {곳 있는 문형, 곳 없는 문형} */
    private record Pair(String withPlace, String plain) {
    }

    /** 세계관 칩 → 장소 낱말. 칩이 아닌 자유글은 쓰지 않는다(지어내지 않으려고). */
    static final Map<String, String> PLACE = Map.of(
            "현대", "동네", "중세", "마을", "미래", "도시", "자연", "숲", "도시", "도시", "우주", "별", "학교", "학교");

    private static final Map<Group, Map<Personality, Pair>> LINES = Map.of(
            Group.OPEN, Map.of(
                    Personality.GENTLE, new Pair("{호칭}안녕하세요. {이름}예요. 오늘 {곳}은 조용해요.", "{호칭}안녕하세요. {이름}예요. 만나서 좋아요."),
                    Personality.LIVELY, new Pair("{호칭}안녕! 나 {이름}! 오늘 {곳}은 신나는 냄새가 나!", "{호칭}안녕! 나 {이름}! 오늘도 신난다!"),
                    Personality.SHY, new Pair("…{호칭}안녕. 나 {이름}. 오늘 {곳}은 조용해.", "…{호칭}안녕. 나 {이름}. 와 줘서 좋아."),
                    Personality.CLINGY, new Pair("{호칭}왔다! {이름} 여기 있어요. 오늘은 {곳}에서 같이 있자요.", "{호칭}왔다! {이름} 여기 있어요. 오늘도 같이 있자요."),
                    Personality.COOL, new Pair("{호칭}왔군. {이름}다. {곳}은 오늘도 별일 없다.", "{호칭}왔군. {이름}다. 오늘도 별일 없다.")),
            Group.CONTINUE, Map.of(
                    Personality.GENTLE, new Pair("{호칭}그렇군요. 말해 줘서 고마워요.", "{호칭}그렇군요. 말해 줘서 고마워요."),
                    Personality.LIVELY, new Pair("{호칭}오오, 그렇구나! 재밌다!", "{호칭}오오, 그렇구나! 재밌다!"),
                    Personality.SHY, new Pair("…{호칭}그렇구나. 잘 들었어.", "…{호칭}그렇구나. 잘 들었어."),
                    Personality.CLINGY, new Pair("{호칭}에헤헤, 그 얘기 좋아요.", "{호칭}에헤헤, 그 얘기 좋아요."),
                    Personality.COOL, new Pair("{호칭}그런가. 알겠다.", "{호칭}그런가. 알겠다.")),
            Group.CLOSE, Map.of(
                    Personality.GENTLE, new Pair("{호칭}저는 {곳} 좀 둘러보고 올게요. 또 말 걸게요.", "{호칭}저는 잠깐 쉬고 있을게요. 또 말 걸게요."),
                    Personality.LIVELY, new Pair("{호칭}나 {곳} 한 바퀴 돌고 올게! 이따 또 말 걸게!", "{호칭}나 잠깐 놀다 올게! 이따 또 말 걸게!"),
                    Personality.SHY, new Pair("…{호칭}나 {곳} 쪽에 잠깐 있을게. 또 말 걸게.", "…{호칭}나 잠깐 쉴게. 또 말 걸게."),
                    Personality.CLINGY, new Pair("{호칭}저 {곳} 구경하고 올게요! 이따 또 말 걸게요!", "{호칭}저 잠깐 놀고 올게요! 이따 또 말 걸게요!"),
                    Personality.COOL, new Pair("{호칭}난 {곳} 좀 돌아보지. 또 말 걸지.", "{호칭}난 하던 거 하지. 또 말 걸지.")));

    /** 성격 없음 — 반말, 짧게(시스템 메시지의 기본 말투와 같다). */
    private static final Map<Group, Pair> NONE = Map.of(
            Group.OPEN, new Pair("{호칭}안녕. 나 {이름}. 오늘 {곳}은 조용해.", "{호칭}안녕. 나 {이름}. 만나서 좋아."),
            Group.CONTINUE, new Pair("{호칭}그렇구나. 잘 들었어.", "{호칭}그렇구나. 잘 들었어."),
            Group.CLOSE, new Pair("{호칭}나 {곳} 좀 둘러보고 올게. 또 말 걸게.", "{호칭}나 잠깐 쉴게. 또 말 걸게."));

    public static String line(PersonaSheet sheet, TurnType type) {
        Group g = type.opening() ? Group.OPEN : type == TurnType.CLOSE ? Group.CLOSE : Group.CONTINUE;
        Personality p = sheet.leadOrNull();
        Pair pair = p == null ? NONE.get(g) : LINES.get(g).get(p);
        String place = place(sheet.world());
        String t = place == null ? pair.plain() : pair.withPlace().replace("{곳}", place);
        String name = sheet.name() == null ? "" : sheet.name();
        // "{이름}예요" · "{이름}다" — 받침이면 "이에요" · "이다"
        t = t.replace("{이름}예요", name + Josa.of(name, "이에요", "예요"))
                .replace("{이름}다.", name + Josa.of(name, "이다.", "다."))
                .replace("{이름}", name);
        t = t.replace("{호칭}", sheet.callMe() == null ? "" : sheet.callMe() + ", ");
        return t;
    }

    /** 세계관 원문에서 첫 칩 → 장소 낱말. 칩이 없으면 null. */
    static String place(String world) {
        if (world == null) {
            return null;
        }
        for (String part : List.of(world.split("·"))) {
            String w = PLACE.get(part.strip());
            if (w != null) {
                return w;
            }
        }
        return null;
    }
}
