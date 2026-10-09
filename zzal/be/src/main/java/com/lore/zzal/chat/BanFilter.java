package com.lore.zzal.chat;

import java.util.List;

/**
 * 원망 문장 금지 필터 — <b>출력 단계에 강제</b>(정본 0장 6·16장).
 *
 * <h3>어디에 거나</h3>
 * 우리가 쓴 대사 — 장면 대사({@code SceneService})·떠남 대사({@code LeaveService}) — 의 출력 직전에 건다
 * (자캐 커뮤니티 규범 — 캐릭터가 사용자를 원망하는 말은 침해로 읽힌다). 걸리면 대사를 지우고 안전한 한 줄로 바꾼다.
 * 사용자 입력은 거르지 않는다(사용자 말은 자유). 프론트도 같은 목록으로 한 번 더 거른다(두 겹, 결정기록 C15).
 *
 * ★ 채팅 LLM 대사에는 걸지 않는다(#709, 상훈 결정 10/10) — 코드단 내용 필터가 정상 대사를 버리고 고정 문형으로
 *   갈아 끼우던 것을 걷었다. 원망·위험 소재 금기는 채팅 지시문 [말하는 법] 이 맡는다. LLM 전용 금칙 목록도 지웠다.
 */
public final class BanFilter {

    private BanFilter() {
    }

    /** 걸렸을 때 대신 나가는 말. 아무도 탓하지 않는다. */
    public static final String SAFE_LINE = "…♪";

    /** 원망·비난·죄책감 유발의 전형. 어미 변형까지 잡도록 어간으로 적는다. 띄어쓰기는 무시하고 대조한다. */
    static final List<String> DENY = List.of(
            // 안 옴·늦음
            "왜안왔", "왜안와", "왜안오", "안오셨", "안오는줄", "안와줬", "안오면", "또안왔", "왜이렇게늦", "늦게왔",
            // 두고 감·혼자·외로움
            "나를두고", "날두고", "저를두고", "혼자뒀", "혼자두", "혼자있", "외로", "어디갔", "어디가셨",
            // 버림·잊음
            "버렸", "버리", "잊었", "잊어버", "잊으",
            // 원망·실망·탓
            "미워", "원망", "실망", "네탓", "너때문", "당신때문", "무시했", "무시하", "신경도안", "관심도없", "관심없",
            // 기다림을 앞세움
            "기다리게", "기다렸", "기다렸는데",
            // 약속·배신·섭섭
            "약속어", "어겼", "배신", "섭섭", "서운"
    );

    /**
     * 원망 문장이 섞였나.
     *
     * 대조 전에 <b>정규화</b>한다 — NFKC(전각 공백·전각 문자 → 반각), 한글·영숫자 외 전부 제거(띄어쓰기·마침표·물음표·
     * 점으로 잘라 쓴 "왜.안.왔.어" 도 잡는다). 어간으로 적어 어미 변형("오셨어요"·"잊으신")까지 잡는다(리뷰 반영).
     */
    public static boolean isBanned(String line) {
        if (line == null) {
            return false;
        }
        String tight = normalize(line);
        return DENY.stream().anyMatch(tight::contains);
    }

    static String normalize(String line) {
        String nfkc = java.text.Normalizer.normalize(line, java.text.Normalizer.Form.NFKC);
        return nfkc.replaceAll("[^가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9]", "");
    }

    /** 출력 직전 — 걸리면 안전한 한 줄로. */
    public static String clean(String line) {
        return isBanned(line) ? SAFE_LINE : line;
    }
}
