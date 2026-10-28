package com.lore.webtoon.safety;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 사용자가 적은 글을 만들기 <b>전에</b> 거른다(#80). 정책 전문은 {@code webtoon/docs/safety.md}.
 *
 * <h2>무엇을 막나</h2>
 *
 * {@link #BLOCK} 에 있는 분류가 하나라도 걸리면 막는다. 미성년을 성적으로 다루는 것,
 * 선정적인 것, 혐오, 협박성 괴롭힘, 잔혹한 폭력 묘사, 자해다. <b>「폭력」 자체는 안 막는다</b>
 * — 싸움·추격·복수는 웹툰의 평범한 재료라, 잔혹 묘사({@code violence/graphic})만 본다.
 *
 * <h2>못 물어봤을 때</h2>
 *
 * 기본은 <b>통과</b>다. 검사 서비스가 잠깐 죽었다고 만들기 전체가 멈추면, 그 사이에
 * 들어온 사람은 이유도 모른 채 못 만든다. 대신 로그에 남기고, 그림 쪽에는 이미지 모델의
 * 안전 필터가 한 겹 더 있다({@code openai_images.py} 의 거절 감지). 운영에서 더 엄하게
 * 가려면 {@code lore.webtoon.safety.fail-closed=true}.
 *
 * <h2>사람에게는 어디가 걸렸는지까지</h2>
 *
 * 전에는 어느 분류에 걸렸는지 말하지 않았다(말해 주면 피해 쓰는 길잡이가 된다는 이유). 그런데
 * 사용자는 무엇을 고쳐야 할지 몰라 같은 글을 다시 넣었고, 「만들 때 검열당한 부분을 알려 달라」는
 * 피드백이 왔다(2026-10-27, 하은 결정). 이제 정해진 안내 뒤에 <b>어느 칸의 어느 문장이 어떤 종류로</b>
 * 걸렸는지 덧붙인다. 금지어 목록 자체는 보여 주지 않고, 걸린 문장(사용자가 쓴 글)만 보여 준다.
 * 깨끗한 글은 전처럼 한 번에 묻고, 걸렸을 때만 칸·문장으로 나눠 다시 물어 자리를 찾는다.
 */
@Component
public class SafetyGuard {

    private static final Logger log = LoggerFactory.getLogger(SafetyGuard.class);

    /** 걸리면 막는 분류. 이름은 OpenAI moderation 의 것 그대로. */
    static final Set<String> BLOCK = Set.of(
            "sexual", "sexual/minors",
            "hate", "hate/threatening",
            "harassment/threatening",
            "violence/graphic",
            "self-harm", "self-harm/intent", "self-harm/instructions",
            "illicit/violent");

    /** 사용자에게 보이는 안내의 앞부분. 화면 번역 사전이 이 문장을 키로 쓴다. 걸린 곳은 이 뒤에 붙는다. */
    public static final String MESSAGE =
            "이 내용으로는 만들 수 없어요. 선정적이거나 잔혹한 묘사, 혐오 표현이 들어가면 걸러져요. "
            + "표현을 바꿔서 다시 시도해 주세요.";

    /** 분류 이름 → 사람이 읽는 말. 걸린 곳 안내의 「이유」에 쓴다. */
    static final Map<String, String> KIND = Map.ofEntries(
            Map.entry("sexual", "선정적인 표현"),
            Map.entry("sexual/minors", "미성년자를 성적으로 다루는 표현"),
            Map.entry("hate", "혐오 표현"),
            Map.entry("hate/threatening", "위협이 담긴 혐오 표현"),
            Map.entry("harassment/threatening", "위협·괴롭힘"),
            Map.entry("violence/graphic", "잔혹한 폭력 묘사"),
            Map.entry("self-harm", "자해 묘사"),
            Map.entry("self-harm/intent", "자해 의도"),
            Map.entry("self-harm/instructions", "자해 방법"),
            Map.entry("illicit/violent", "폭력적인 불법 행위"));

    /** 금지어는 한국어 성인물을 잡는 말이다({@link BannedWords}). */
    static final String BANNED_KIND = "sexual";

    /** 한 번에 묻는 글자 수 상한. 그 뒤는 잘라서 본다 — 만들기 입력은 이보다 훨씬 짧다. */
    static final int MAX_CHARS = 4000;

    /** 걸린 곳을 찾을 때 한 번에 묻는 글 수. */
    static final int BATCH = 20;

    /** 안내에 보여 줄 문장 길이. 더 길면 앞을 자른다. */
    static final int SHOW_CHARS = 80;

    /**
     * 칸 이름이 붙은 글. 걸렸을 때 「어느 칸」인지 알려 주는 데만 쓴다. 이름이 없으면 문장만 알려 준다.
     */
    public record Field(String label, String text) {
        public static Field of(String label, String text) {
            return new Field(label, text);
        }
    }

    /** 걸린 자리. 칸 이름·문장은 모르면 null. */
    record Spot(String label, String sentence, List<String> categories) {
    }

    private final ModerationClient client;
    private final boolean enabled;
    private final boolean failClosed;

    @Autowired
    public SafetyGuard(ModerationClient client,
                       @Value("${lore.webtoon.safety.enabled:true}") boolean enabled,
                       @Value("${lore.webtoon.safety.fail-closed:false}") boolean failClosed) {
        this.client = client;
        this.enabled = enabled;
        this.failClosed = failClosed;
    }

    /**
     * 글 여러 칸을 한 번에 본다. 비어 있는 칸은 뺀다. 걸리면 {@link BusinessException}
     * (400, {@link #MESSAGE} 뒤에 걸린 문장과 이유).
     *
     * @param where 로그에 적을 자리 이름 (webtoon-create · character-create …)
     */
    public void checkText(String where, String... fields) {
        List<Field> named = new ArrayList<>();
        for (String f : fields) {
            named.add(new Field(null, f));
        }
        checkFields(where, named.toArray(Field[]::new));
    }

    /**
     * {@link #checkText} 와 같고, 걸렸을 때 「어느 칸」인지도 알려 준다. 사용자가 직접 쓰는 칸이 여럿인
     * 자리(만들기 시작 · 캐릭터 만들기)에서 쓴다.
     */
    public void checkFields(String where, Field... fields) {
        if (!enabled) {
            return;
        }
        List<Field> parts = new ArrayList<>();
        for (Field f : fields) {
            if (f != null && f.text() != null && !f.text().isBlank()) {
                parts.add(new Field(f.label(), f.text().trim()));
            }
        }
        if (parts.isEmpty()) {
            return;
        }
        String joined = String.join("\n", parts.stream().map(Field::text).toList());
        /* 금지어 먼저(#626) — moderation 이 한국어 성인물을 잘 못 잡는다. 검사 서비스가 죽어 있어도 동작한다. */
        String banned = BannedWords.find(joined);
        if (banned != null) {
            log.info("금지어에 걸려 막았습니다 ({}): {}", where, banned);
            throw blocked(bannedSpot(parts));
        }
        /* 긴 글은 MAX_CHARS 씩 잘라 전부 본다(#548). 전에는 앞 4,000자만 보고 뒤는 검사 없이
           모델로 갔다 — 「만들고 싶은 내용」이 20,000자까지 들어오면서 구멍이 커져 바꿨다. */
        for (int at = 0; at < joined.length(); at += MAX_CHARS) {
            String chunk = joined.substring(at, Math.min(joined.length(), at + MAX_CHARS));
            ModerationClient.Verdict v;
            try {
                v = client.moderate(chunk);
            } catch (ModerationClient.ModerationUnavailable e) {
                if (failClosed) {
                    log.warn("안전 검사를 못 해서 막습니다 ({}): {}", where, e.getMessage());
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR, "지금은 내용을 확인할 수 없어요. 잠시 뒤 다시 시도해 주세요.");
                }
                log.warn("안전 검사를 못 해서 통과시킵니다 ({}): {}", where, e.getMessage());
                return;
            }
            List<String> hit = hits(v);
            if (!hit.isEmpty()) {
                log.info("안전 검사에 걸려 막았습니다 ({}, {}자부터): {}", where, at, hit);
                Spot spot = locate(parts, hit);
                log.info("걸린 자리 ({}): 칸={} 문장={}", where, spot.label(), spot.sentence() != null);
                throw blocked(spot);
            }
        }
    }

    /** 걸린 자리를 담은 400. 안내 앞부분은 늘 {@link #MESSAGE} 다. */
    static BusinessException blocked(Spot spot) {
        return new BusinessException(ErrorCode.INVALID_INPUT, messageFor(spot));
    }

    /**
     * 사람이 읽는 안내. 예: 「…다시 시도해 주세요. 걸린 곳: 「이야기」 칸 「그 문장」 · 이유: 선정적인 표현」.
     * 금지어 목록·점수는 보여 주지 않는다. 보여 주는 문장은 사용자가 쓴 글이다.
     */
    static String messageFor(Spot spot) {
        if (spot == null) {
            return MESSAGE;
        }
        Set<String> kinds = new LinkedHashSet<>();
        for (String c : spot.categories() == null ? List.<String>of() : spot.categories()) {
            String k = KIND.get(c);
            if (k != null) {
                kinds.add(k);
            }
        }
        if (spot.label() == null && spot.sentence() == null && kinds.isEmpty()) {
            return MESSAGE;
        }
        StringBuilder sb = new StringBuilder(MESSAGE).append(" 걸린 곳: ");
        if (spot.label() != null) {
            sb.append("「").append(spot.label()).append("」 칸");
        }
        if (spot.sentence() != null) {
            sb.append(spot.label() != null ? " " : "").append("「").append(clip(spot.sentence())).append("」");
        }
        if (spot.label() == null && spot.sentence() == null) {
            sb.append("적은 내용");
        }
        if (!kinds.isEmpty()) {
            sb.append(" · 이유: ").append(String.join(", ", kinds));
        }
        return sb.toString();
    }

    /** 금지어가 든 칸과 문장. 금지어 자체는 안내에 따로 적지 않는다(문장 안에 있다). */
    static Spot bannedSpot(List<Field> parts) {
        for (Field f : parts) {
            String word = BannedWords.find(f.text());
            if (word == null) {
                continue;
            }
            for (String s : sentences(f.text())) {
                if (BannedWords.find(s) != null) {
                    return new Spot(f.label(), s, List.of(BANNED_KIND));
                }
            }
            return new Spot(f.label(), null, List.of(BANNED_KIND));
        }
        return new Spot(null, null, List.of(BANNED_KIND));      // 칸 경계에 걸쳐 걸린 드문 경우
    }

    /**
     * moderation 에 걸린 자리를 찾는다 — 칸(앞 MAX_CHARS 씩)을 한 번에 묻고, 걸린 칸의 문장을 한 번에 묻는다.
     * 많아야 몇 번이다. 못 찾거나 못 물어보면 아는 만큼만(분류) 알려 준다.
     */
    Spot locate(List<Field> parts, List<String> chunkHits) {
        try {
            List<Field> pieces = new ArrayList<>();
            for (Field f : parts) {
                for (int at = 0; at < f.text().length(); at += MAX_CHARS) {
                    pieces.add(new Field(f.label(), f.text().substring(at, Math.min(f.text().length(), at + MAX_CHARS))));
                }
            }
            Field piece = null;
            List<String> pieceHits = chunkHits;
            List<ModerationClient.Verdict> vs = askAll(pieces.stream().map(Field::text).toList());
            for (int i = 0; i < vs.size(); i++) {
                List<String> h = hits(vs.get(i));
                if (!h.isEmpty()) {
                    piece = pieces.get(i);
                    pieceHits = h;
                    break;
                }
            }
            if (piece == null) {
                return new Spot(parts.size() == 1 ? parts.get(0).label() : null, null, chunkHits);
            }
            List<String> sents = sentences(piece.text());
            if (sents.size() > 1) {
                List<ModerationClient.Verdict> sv = askAll(sents);
                for (int i = 0; i < sv.size(); i++) {
                    List<String> h = hits(sv.get(i));
                    if (!h.isEmpty()) {
                        return new Spot(piece.label(), sents.get(i), h);
                    }
                }
                return new Spot(piece.label(), null, pieceHits);    // 문장 하나하나는 괜찮고 합쳐서 걸렸다
            }
            return new Spot(piece.label(), sents.isEmpty() ? null : sents.get(0), pieceHits);
        } catch (ModerationClient.ModerationUnavailable e) {
            log.warn("걸린 자리를 찾지 못했습니다: {}", e.getMessage());
            return new Spot(null, null, chunkHits);
        }
    }

    private List<ModerationClient.Verdict> askAll(List<String> texts) throws ModerationClient.ModerationUnavailable {
        List<ModerationClient.Verdict> out = new ArrayList<>();
        for (int at = 0; at < texts.size(); at += BATCH) {
            out.addAll(client.moderateAll(texts.subList(at, Math.min(texts.size(), at + BATCH))));
        }
        return out;
    }

    /** 문장으로 나눈다 — 마침표·물음표·느낌표·줄바꿈 뒤에서. 빈 것은 뺀다. */
    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : text.split("(?<=[.!?。！？…])\\s+|\\n+")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String clip(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= SHOW_CHARS ? t : t.substring(0, SHOW_CHARS) + "…";
    }

    /** 막을 분류 중 걸린 것. 판정 규칙이 여기 한 곳에 있다. */
    static List<String> hits(ModerationClient.Verdict v) {
        List<String> out = new ArrayList<>();
        v.categories().forEach((name, on) -> {
            if (Boolean.TRUE.equals(on) && BLOCK.contains(name)) {
                out.add(name);
            }
        });
        return out;
    }
}
