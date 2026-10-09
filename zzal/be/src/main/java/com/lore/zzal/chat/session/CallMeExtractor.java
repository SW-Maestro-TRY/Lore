package com.lore.zzal.chat.session;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "뭐라고 부를까" 에 대한 사용자 답에서 호칭을 뽑는다 — <b>LLM 이 아니라 코드</b>(틀려도 소리 안 나는 판정을 맡기지 않는다).
 *
 * <ol>
 *   <li>"X라고 불러 / 불러줘 / 부르면 돼", "X로 불러" → X</li>
 *   <li>아니면 답 전체가 한 단어이고 2~6자 → 그 단어</li>
 *   <li>아니면 저장 안 함(다음 판에 다시 묻는다)</li>
 * </ol>
 * "편한 대로"·"몰라"·"메롱" 같은 답은 호칭이 아니라 빼 둔다.
 *
 * <h3>★ 헛걸림을 막는 세 겹(#704 결함 수정)</h3>
 * <ul>
 *   <li>펫 이름은 호칭이 아니다("우사기" 라고 답해도 아이가 사용자를 "우사기" 라 부르면 안 된다)</li>
 *   <li>인사·감탄·맞장구 사전 — 반가워·고마워·안녕·사랑해·나도·응·네·ㅋㅋ …</li>
 *   <li>한 단어 규칙은 <b>명사 꼴</b>만 — 끝이 워·해·도·다·요 처럼 말끝이면 뺀다("반가워", "좋아해")</li>
 * </ul>
 */
public final class CallMeExtractor {

    private static final Pattern SAY = Pattern.compile(
            "^(?:(?:그냥|날|나를|나는|난|저를|저는|전)\\s+)?['\"“]?(.{1,12}?)['\"”]?\\s*(?:이라고|라고|이라|라|으로|로)\\s*"
                    + "(?:불러|부르면|부르세요|부르셔|불러줘|불러 줘|해줘|해 줘|하면)");
    private static final Pattern WORD = Pattern.compile("^[가-힣A-Za-z]{2,6}$");
    private static final Set<String> NOT_A_NAME = Set.of(
            "몰라", "모르겠어", "싫어", "아무거나", "편한대로", "마음대로", "맘대로", "안녕", "안녕하세요", "메롱", "비밀",
            "하이", "그래", "좋아", "글쎄", "아니", "됐어", "알아서", "아무렇게나", "편하게", "괜찮아", "몰라요", "비밀이야",
            // 인사·감탄·맞장구(#704 결함 수정 — 첫 부름 답에 실제로 들어온 말)
            "반가워", "반가워요", "반갑다", "고마워", "고마워요", "고맙다", "사랑해", "사랑해요", "보고싶었어", "보고싶어",
            "나도", "저도", "응", "네", "넹", "넵", "예", "웅", "ㅇㅇ", "ㅋㅋ", "ㅋㅋㅋ", "ㅎㅎ", "ㅎㅎㅎ", "헐", "와", "우와",
            "귀여워", "귀엽다", "너무귀여워", "하이요", "안뇽", "안녕안녕", "냐아", "헤헤", "히히", "그럼", "물론");
    /** 말끝 꼴 — 한 단어 규칙에서 뺀다(명사 꼴만 호칭). */
    private static final Pattern VERB_END = Pattern.compile(".*(워|해|도|다|요)$");
    private static final Pattern ONLY_JAMO = Pattern.compile("^[ㄱ-ㅎㅏ-ㅣ]+$");

    private CallMeExtractor() {
    }

    /** 뽑은 호칭. 없으면 null. 펫 이름을 모르는 자리(시험)용. */
    public static String extract(String answer) {
        return extract(answer, null);
    }

    /** 뽑은 호칭. 없으면 null. {@code petName} 과 같은 말은 호칭이 아니다. */
    public static String extract(String answer, String petName) {
        String x = raw(answer);
        if (x == null) {
            return null;
        }
        String pn = petName == null ? null : petName.replaceAll("\\s+", "");
        if (pn != null && !pn.isEmpty() && x.replaceAll("\\s+", "").equals(pn)) {
            return null;
        }
        return x;
    }

    private static String raw(String answer) {
        if (answer == null) {
            return null;
        }
        String a = answer.strip().replaceAll("[!~.。…♡♥]+$", "").strip();
        if (a.isEmpty()) {
            return null;
        }
        Matcher m = SAY.matcher(a);
        if (m.find()) {
            String x = m.group(1).strip();
            return x.isEmpty() || NOT_A_NAME.contains(x) ? null : x;
        }
        String w = a.replaceAll("\\s+", "");
        if (a.contains(" ") || !WORD.matcher(w).matches() || NOT_A_NAME.contains(w) || VERB_END.matcher(w).matches()
                || ONLY_JAMO.matcher(w).matches()) {
            return null;
        }
        return w;
    }
}
