package com.lore.webtoon.job;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 이미 쓰인 글 속 인물 이름을 새 이름으로 바꾼다 — 인물 확인에서 이름을 고쳤을 때(Tosisusae 피드백).
 *
 * <p>이름만 바꾸고 이야기·장면 글을 그대로 두면 다음 단계(장면 나누기·장 그림)가 옛 이름과 새 이름을
 * 둘 다 받아 두 사람으로 읽는다. 그래서 작품 폴더의 글에서 이름을 같이 바꾼다.
 *
 * <ul>
 *   <li><b>낱말 경계만</b> — 이름 앞에 한글이 붙어 있으면(「밤하늘」 속 「하늘」) 안 바꾼다. 이름 뒤에
 *       한글이 붙어 있으면 조사·호칭으로 시작할 때만 바꾼다(「민수진」 속 「민수」는 안 바꾼다).</li>
 *   <li><b>조사를 받침에 맞춘다</b> — 「연화가」→「민준이」, 「민준이는」→「연화는」, 「하늘로」→「민준으로」.</li>
 *   <li><b>성을 뺀 이름도</b> — 세 글자 한글 이름이면 본문이 자주 부르는 두 글자 이름도 같이 바꾼다
 *       (「서연화」→「한지민」 이면 「연화」→「지민」).</li>
 * </ul>
 *
 * 한글이 아닌 이름은 받침을 모르니 조사를 그대로 둔다.
 */
final class NameSwap {

    private NameSwap() {
    }

    /** 바꿀 짝 하나 — 옛 이름 → 새 이름. */
    record Pair(String from, String to) {
    }

    /**
     * 옛 이름들(주인공 카드 이름과 처음 적은 이름이 다를 수 있다) → 새 이름의 짝. 긴 것부터.
     * 같은 이름·빈 이름은 뺀다.
     */
    static List<Pair> pairs(List<String> olds, String to) {
        String next = to == null ? "" : to.trim();
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : olds) {
            String old = raw == null ? "" : raw.trim();
            if (old.isEmpty() || old.equals(next)) {
                continue;
            }
            out.putIfAbsent(old, next);
            String given = givenName(old);
            String nextGiven = givenName(next) != null ? givenName(next) : next;
            if (given != null && !given.equals(nextGiven) && !out.containsKey(given)) {
                out.put(given, nextGiven);
            }
        }
        List<Pair> list = new ArrayList<>();
        out.forEach((k, v) -> list.add(new Pair(k, v)));
        list.sort(Comparator.comparingInt((Pair p) -> p.from().length()).reversed());
        return list;
    }

    /** 세 글자 한글 이름이면 성을 뺀 두 글자. 아니면 null. */
    static String givenName(String name) {
        if (name == null || name.length() != 3) {
            return null;
        }
        for (int i = 0; i < 3; i++) {
            if (!hangul(name.charAt(i))) {
                return null;
            }
        }
        return name.substring(1);
    }

    /** 글 하나에서 이름을 바꾼다. */
    static String replace(String text, List<Pair> pairs) {
        if (text == null || text.isEmpty() || pairs.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        int i = 0;
        outer:
        while (i < text.length()) {
            for (Pair p : pairs) {
                if (!text.startsWith(p.from(), i) || !startsWord(text, i, p.from())) {
                    continue;
                }
                int end = i + p.from().length();
                if (!endsWord(text, end, p.from())) {
                    continue;
                }
                out.append(p.to());
                Particle fix = particle(text.substring(end), p.from(), p.to());
                out.append(fix.text());
                i = end + fix.consumed();
                continue outer;
            }
            out.append(text.charAt(i));
            i++;
        }
        return out.toString();
    }

    /** JSON 으로 읽은 값(Map·List·문자열) 안의 글을 전부 바꾼다. {@code skip} 키 아래는 그대로. */
    @SuppressWarnings("unchecked")
    static Object replaceIn(Object node, List<Pair> pairs, List<String> skip) {
        if (node instanceof String s) {
            return replace(s, pairs);
        }
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                out.put(key, skip.contains(key) ? e.getValue() : replaceIn(e.getValue(), pairs, skip));
            }
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object one : list) {
                out.add(replaceIn(one, pairs, skip));
            }
            return out;
        }
        return node;
    }

    /* ---- 경계 ---- */

    /** 이름 뒤에 바로 붙어도 되는 글자 — 조사·호칭의 첫 글자. 이 밖의 한글이 붙으면 다른 낱말이다. */
    private static final String SUFFIX_START = "이가은는을를과와아야랑로으의도만에께한까부처보하씨님네들라나며여예였다마조뿐밖께";

    private static boolean startsWord(String text, int at, String name) {
        if (at == 0) {
            return true;
        }
        char before = text.charAt(at - 1);
        if (hangul(before)) {
            return false;
        }
        return !(Character.isLetterOrDigit(before) && Character.isLetterOrDigit(name.charAt(0)));
    }

    private static boolean endsWord(String text, int end, String name) {
        if (end >= text.length()) {
            return true;
        }
        char after = text.charAt(end);
        if (hangul(after)) {
            return SUFFIX_START.indexOf(after) >= 0;
        }
        return !(Character.isLetterOrDigit(after) && Character.isLetterOrDigit(name.charAt(name.length() - 1)));
    }

    /* ---- 조사 ---- */

    private record Particle(int consumed, String text) {
        static final Particle KEEP = new Particle(0, "");
    }

    /** 받침 있을 때 꼴 · 없을 때 꼴. 긴 것부터 본다. 「로」는 따로(ㄹ 받침은 「로」). */
    private static final String[][] FORMS = {
            {"이라고", "라고"}, {"이라는", "라는"}, {"이라서", "라서"}, {"이라면", "라면"},
            {"이어서", "여서"}, {"이에요", "예요"}, {"이었", "였"},
            {"이랑", "랑"}, {"이나", "나"}, {"이며", "며"}, {"이여", "여"}, {"이다", "다"},
            {"은", "는"}, {"이", "가"}, {"을", "를"}, {"과", "와"}, {"아", "야"},
    };

    /**
     * 이름 뒤 조사를 새 이름의 받침에 맞춘다. 바꿀 것이 없으면 KEEP(뒤 글을 그대로 둔다).
     *
     * @param tail 이름 바로 뒤의 글
     */
    private static Particle particle(String tail, String from, String to) {
        Boolean oldEnd = batchim(from);
        Boolean newEnd = batchim(to);
        if (oldEnd == null || newEnd == null || tail.isEmpty() || !hangul(tail.charAt(0))) {
            return Particle.KEEP;
        }
        boolean newRieul = rieul(to);
        /* 「로」 — 받침 없거나 ㄹ 받침이면 「로」, 그 밖의 받침이면 「으로」 */
        if (tail.startsWith("으로") || tail.startsWith("로")) {
            int len = tail.startsWith("으로") ? 2 : 1;
            return new Particle(len, (!newEnd || newRieul) ? "로" : "으로");
        }
        if (oldEnd && tail.charAt(0) == '이') {
            /* 받침 있는 이름 뒤의 「이」 — 줄임(이에요·이었)이 먼저, 그다음은 부르는 말(민준이가·민준이는)이거나
               주격 조사(민준이 왔다)다. 받침 없는 새 이름이면 부르는 말의 「이」는 빠지고, 주격이면 「가」다. */
            for (String[] f : FORMS) {
                if (f[0].startsWith("이") && f[0].length() >= 3 && tail.startsWith(f[0])) {
                    return new Particle(f[0].length(), newEnd ? f[0] : f[1]);
                }
            }
            if (tail.startsWith("이었")) {
                return new Particle(2, newEnd ? "이었" : "였");
            }
            if (newEnd) {
                return Particle.KEEP;
            }
            boolean more = tail.length() > 1 && hangul(tail.charAt(1));
            return more ? new Particle(1, "") : new Particle(1, "가");
        }
        for (String[] f : FORMS) {
            String have = oldEnd ? f[0] : f[1];
            if (!tail.startsWith(have)) {
                continue;
            }
            if (have.equals("다") || have.equals("이다")) {
                /* 「다」로 시작하는 다른 낱말(연화다운)과 헷갈리지 않게 — 문장 끝일 때만 */
                int after = have.length();
                if (after < tail.length() && hangul(tail.charAt(after))) {
                    return Particle.KEEP;
                }
            }
            return new Particle(have.length(), newEnd ? f[0] : f[1]);
        }
        return Particle.KEEP;
    }

    /** 마지막 글자에 받침이 있나. 한글이 아니면 null(모른다). */
    static Boolean batchim(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        char last = name.charAt(name.length() - 1);
        return hangul(last) ? (last - 0xAC00) % 28 != 0 : null;
    }

    private static boolean rieul(String name) {
        char last = name.charAt(name.length() - 1);
        return hangul(last) && (last - 0xAC00) % 28 == 8;
    }

    static boolean hangul(char c) {
        return c >= 0xAC00 && c <= 0xD7A3;
    }
}
