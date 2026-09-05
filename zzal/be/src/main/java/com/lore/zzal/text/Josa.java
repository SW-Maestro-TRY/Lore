package com.lore.zzal.text;

/**
 * 조사(은/는·이/가·을/를)를 이름에 맞게 고른다.
 *
 * <h3>★ 왜 필요한가 — 조사가 어긋나면 정성이 안 든 것처럼 읽힌다</h3>
 * "보리<b>이가</b> 자고 있어요" 는 문법이 틀린 문장이다(받침 없는 이름에 "이" 를 붙였다).
 * 사용자가 직접 지은 이름이 매번 이렇게 나오면, 그 한 글자가 서비스 전체를 성의 없어 보이게 만든다
 * (2026-09-05 프론트 실서버 연결에서 발견).
 *
 * <h3>받침 판정</h3>
 * <ul>
 *   <li><b>한글</b> — 유니코드 완성형에서 종성 인덱스가 0이 아니면 받침이 있다({@code (코드 - 0xAC00) % 28})</li>
 *   <li><b>영문</b> — 마지막 글자가 모음(a·e·i·o·u)이면 받침 없음, 자음이면 있음("Tom이가" · "Bori가")</li>
 *   <li><b>숫자</b> — 읽는 소리로 판정한다. 0(영)·1(일)·3(삼)·6(육)·7(칠)·8(팔)은 받침, 2·4·5·9는 없음</li>
 *   <li>그 밖(이모지·기호·결합문자)은 <b>건너뛰고</b> 그 앞의 글자로 판정한다 — "밤톨🐣" 은 "밤톨이는".
 *       끝까지 글자가 없으면(이모지뿐) 받침 없음으로 본다</li>
 * </ul>
 *
 * ★ 판정 전에 <b>NFC 로 정규화</b>한다. 같은 "밤톨" 이라도 자모가 풀린 형태(NFD)로 들어오면 마지막 char 가
 *   종성 자모 하나여서 완성형 판정이 통째로 빗나간다(맥 파일명·일부 IME 가 NFD 를 낸다).
 *
 * <h3>이름 뒤의 "이"</h3>
 * 한국어에서 받침 있는 이름은 부를 때 "이" 가 붙는다(밤톨 → <b>밤톨이</b>가). 받침이 없으면 안 붙는다(보리 → 보리가).
 * 그래서 이름 전용 메서드({@link #nameSubject}·{@link #nameTopic}·{@link #nameObject})를 따로 둔다 —
 * 일반 조사 고르기({@link #of})와 규칙이 다르다.
 */
public final class Josa {

    private Josa() {
    }

    /** 받침 있는 낱말에 붙는 소리로 나는 숫자들(영·일·삼·육·칠·팔). */
    private static final String DIGITS_WITH_FINAL = "0136 78".replace(" ", "");

    /**
     * 받침 유무로 조사를 고른다.
     *
     * @param word      앞 낱말
     * @param withFinal 받침이 있을 때 쓸 조사("은" · "이" · "을")
     * @param noFinal   받침이 없을 때 쓸 조사("는" · "가" · "를")
     */
    public static String of(String word, String withFinal, String noFinal) {
        return hasFinalConsonant(word) ? withFinal : noFinal;
    }

    /** 이름 + 이/가 — "밤톨이가" · "보리가". */
    public static String nameSubject(String name) {
        return attach(name, "이가", "가");
    }

    /** 이름 + 은/는 — "밤톨이는" · "보리는". */
    public static String nameTopic(String name) {
        return attach(name, "이는", "는");
    }

    /** 이름 + 을/를 — "밤톨이를" · "보리를". */
    public static String nameObject(String name) {
        return attach(name, "이를", "를");
    }

    /** ★ 앞뒤 공백은 떼고 붙인다 — "여울 는" 처럼 조사가 떨어져 나오는 것을 막는다. */
    private static String attach(String name, String withFinal, String noFinal) {
        String trimmed = name == null ? "" : name.strip();
        return trimmed + (hasFinalConsonant(trimmed) ? withFinal : noFinal);
    }

    /**
     * 마지막 글자에 받침이 있나. 빈 이름은 없음으로 본다(이름은 비어 있을 수 없지만,
     * 여기서 예외를 던지면 <b>거절 메시지를 만들다가 500</b>이 나간다 — 거절이 오류로 바뀐다).
     */
    public static boolean hasFinalConsonant(String word) {
        if (word == null || word.isBlank()) {
            return false;
        }
        String normalized = java.text.Normalizer.normalize(word.strip(), java.text.Normalizer.Form.NFC);
        // 뒤에서부터 "발음이 있는 글자" 를 찾는다 — 이모지·기호·결합문자는 소리가 없으므로 건너뛴다.
        for (int i = normalized.length() - 1; i >= 0; i--) {
            char c = normalized.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) {
                return (c - 0xAC00) % 28 != 0;
            }
            if (Character.isDigit(c)) {
                return DIGITS_WITH_FINAL.indexOf(c) >= 0;
            }
            char lower = Character.toLowerCase(c);
            if (lower >= 'a' && lower <= 'z') {
                return "aeiou".indexOf(lower) < 0;
            }
        }
        return false;
    }
}
