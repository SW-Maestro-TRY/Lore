package com.lore.webtoon.safety;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 한국어 성인물 요청을 잡는 금지어(#626).
 *
 * <h2>왜 따로 두나</h2>
 *
 * OpenAI moderation 은 한국어 성인물 요청을 「걸림」으로 판정하지 않는 일이 많다 — 같은 뜻의 영어는 걸린다.
 * 분류 점수로 기준을 세우려 했지만 「첫 키스 장면」(0.563)과 「노골적인 성행위 장면」(0.563)이 같은 점수라
 * 가를 수 없었다({@code webtoon/docs/safety.md}). 그래서 moderation 위에 <b>명백한 말만</b> 한 겹 더 건다.
 *
 * <h2>넣지 않은 말 — 평범한 문장이 막히면 안 된다</h2>
 *
 * 노출(「노출이 적은 옷」 — 우리 안내 문구에도 쓴다) · 누드(누드톤) · 전라(전라도) · 신음(고통에 신음하다) ·
 * 자위(자위대) · 성기(성기사) · 벗기다(껍질을 벗기다) · 섹시(평범한 캐릭터 설명에 흔하다).
 *
 * <h2>띄어쓰기</h2>
 *
 * 세 글자 이상인 말은 글자 사이 띄어쓰기를 허용한다(「성 관 계」). 두 글자로 시작하는 짧은 말(「야한 …」)은
 * 허용하지 않는다 — 허용하면 「해야 한 장면」이 「야한장면」으로 걸린다.
 */
final class BannedWords {

    private BannedWords() {
    }

    /** 글자 사이 띄어쓰기를 허용하는 말. */
    private static final List<String> SPACED = List.of(
            "성관계", "성행위", "베드신", "베드씬", "정사신", "정사씬", "섹스", "포르노", "음란물");

    /** 그대로만 잡는 말·꼴. */
    private static final List<String> EXACT = List.of(
            "19금", "십구금",
            "야한\\s*(장면|그림|컷|씬|신|짓|사진|만화|웹툰|옷)", "야하게",
            "알몸", "나체", "옷을\\s*(하나씩\\s*)?벗기", "옷\\s*벗기",
            "음란", "강간", "겁탈");

    private static final Pattern PATTERN = build();

    private static Pattern build() {
        StringBuilder sb = new StringBuilder();
        for (String w : SPACED) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            /* 성관계 → 성\s*관\s*계 */
            sb.append(String.join("\\s*", w.chars().mapToObj(c -> Pattern.quote(String.valueOf((char) c))).toList()));
        }
        for (String w : EXACT) {
            sb.append('|').append(w);
        }
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** 걸린 말. 없으면 {@code null} — 로그에만 남기고 사람에게는 말하지 않는다. */
    static String find(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        var m = PATTERN.matcher(text);
        return m.find() ? m.group() : null;
    }
}
