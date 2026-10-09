package com.lore.zzal.chat.session;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "뭐라고 부를까" 에 대한 사용자 답에서 호칭을 뽑는다 — <b>LLM 이 아니라 코드</b>(틀려도 소리 안 나는 판정을 맡기지 않는다).
 *
 * <ol>
 *   <li>"X라고 불러 / 불러줘 / 부르면 돼", "X로 불러" → X</li>
 *   <li>"(나는) X라고 해 / 해요 / 합니다" → X (#709)</li>
 *   <li>"(나는) X야 / X이야 / X예요 / X이에요 / X입니다" → X (#709)</li>
 *   <li>"나는 X" · "난 X" · "제 이름은 X" → X (#709)</li>
 *   <li>아니면 답 전체가 한 단어이고 2~6자 → 그 단어</li>
 *   <li>아니면 저장 안 함(다음 판에 다시 묻는다)</li>
 * </ol>
 * 앞머리 인사("안녕", "안녕하세요", "반가워")는 떼고 본다 — "안녕 난 김민서야" → 김민서.
 *
 * <h3>★ 모델 추출과 함께 쓴다(#709)</h3>
 * 모델이 대사와 함께 {@code call_me} 를 돌려준다. 저장 규칙은 {@code ChatService}: 코드가 뽑으면 그것, 못 뽑으면
 * 모델 것({@link #acceptModel} 을 통과할 때만). 둘 다 뽑았는데 다르면 저장하지 않고 턴 행에 둘 다 남긴다(다음 판에 다시 묻는다).
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
    /** "(나는) X라고 해" — 자기소개. */
    private static final Pattern INTRO_SAY = Pattern.compile(
            "^(?:" + "(?:나는|난|저는|전|내 이름은|제 이름은|이름은)" + "\\s*)?['\"“]?(.{1,12}?)['\"”]?\\s*(?:이라고|라고)\\s*"
                    + "(?:해|해요|합니다|하면 돼|하면 돼요)$");
    /** "(나는) X야 / X이야 / X예요 / X이에요 / X입니다" — 서술격 조사를 뗀다(받침이 있으면 "이" 까지). */
    private static final Pattern COPULA = Pattern.compile(
            "^(?:(?:나는|난|저는|전|내 이름은|제 이름은|이름은)\\s*|나\\s+)?([가-힣A-Za-z]{2,8}?)(?:이야|야|이에요|예요|입니다)$");
    /** "나는 X" — 조사 없이 끝나는 자기소개. */
    private static final Pattern INTRO = Pattern.compile(
            "^(?:나는|난|저는|전|내 이름은|제 이름은|이름은)\\s+([가-힣A-Za-z]{2,8})$");
    /** 앞머리 인사 — 떼고 본다. */
    private static final Pattern GREETING_HEAD = Pattern.compile(
            "^(?:안녕하세요|안녕|안뇽|하이|반가워요|반가워|반갑습니다)[\\s,!~.。…]+");
    private static final Pattern WORD = Pattern.compile("^[가-힣A-Za-z]{2,6}$");
    /** 모델이 돌려준 호칭이 이것이면 호칭이 아니다 — 지시문의 기본값·대명사를 그대로 돌려주는 경우. */
    private static final Set<String> NOT_A_CALL = Set.of("너", "당신", "상대", "작가", "사용자", "유저", "주인", "주인님");
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

    /**
     * 모델이 읽은 호칭을 받아도 되나 — 펫 이름·대명사("너")·인사·감탄 사전에 걸리면 null, 아니면 다듬은 값.
     * 따옴표·끝 문장부호는 벗기고 20자(펫 칸)로 자른다.
     */
    public static String acceptModel(String model, String petName) {
        if (model == null) {
            return null;
        }
        String x = model.strip().replaceAll("^['\"“‘]+|['\"”’]+$", "").replaceAll("[!~.。…?？]+$", "").strip();
        if (x.isEmpty() || NOT_A_NAME.contains(x) || NOT_A_CALL.contains(x) || ONLY_JAMO.matcher(x).matches()) {
            return null;
        }
        String pn = petName == null ? null : petName.replaceAll("\\s+", "");
        if (pn != null && !pn.isEmpty() && x.replaceAll("\\s+", "").equals(pn)) {
            return null;
        }
        return x.length() > 20 ? x.substring(0, 20) : x;
    }

    /** 두 호칭이 같은 말인가(띄어쓰기 무시). */
    public static boolean same(String a, String b) {
        return a != null && b != null && a.replaceAll("\\s+", "").equals(b.replaceAll("\\s+", ""));
    }

    private static String raw(String answer) {
        if (answer == null) {
            return null;
        }
        String a = answer.strip().replaceAll("[!~.。…♡♥]+$", "").strip();
        a = GREETING_HEAD.matcher(a).replaceFirst("").strip();
        if (a.isEmpty()) {
            return null;
        }
        Matcher m = SAY.matcher(a);
        if (m.find()) {
            String x = m.group(1).strip();
            return x.isEmpty() || NOT_A_NAME.contains(x) ? null : x;
        }
        for (Pattern p : new Pattern[]{INTRO_SAY, COPULA, INTRO}) {
            Matcher k = p.matcher(a);
            if (k.matches()) {
                String x = k.group(1).strip();
                return x.isEmpty() || NOT_A_NAME.contains(x) || NOT_A_CALL.contains(x) ? null : x;
            }
        }
        String w = a.replaceAll("\\s+", "");
        if (a.contains(" ") || !WORD.matcher(w).matches() || NOT_A_NAME.contains(w) || VERB_END.matcher(w).matches()
                || ONLY_JAMO.matcher(w).matches()) {
            return null;
        }
        return w;
    }
}
