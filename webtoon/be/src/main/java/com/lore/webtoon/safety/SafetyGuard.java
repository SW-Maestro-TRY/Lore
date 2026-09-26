package com.lore.webtoon.safety;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
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
 * <h2>사람에게는 한 줄만</h2>
 *
 * 어느 분류에 걸렸는지는 사용자에게 말하지 않는다. 말해 주면 그 말을 피해 다시 쓰는
 * 길잡이가 된다. 로그에는 남긴다.
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

    /** 사용자에게 보이는 한 줄. 분류는 말하지 않는다. */
    public static final String MESSAGE =
            "이 내용으로는 만들 수 없어요. 선정적이거나 잔혹한 묘사, 혐오 표현이 들어가면 걸러져요. "
            + "표현을 바꿔서 다시 시도해 주세요.";

    /** 한 번에 묻는 글자 수 상한. 그 뒤는 잘라서 본다 — 만들기 입력은 이보다 훨씬 짧다. */
    static final int MAX_CHARS = 4000;

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
     * (400, {@link #MESSAGE}).
     *
     * @param where 로그에 적을 자리 이름 (webtoon-create · character-create …)
     */
    public void checkText(String where, String... fields) {
        if (!enabled) {
            return;
        }
        List<String> parts = new ArrayList<>();
        for (String f : fields) {
            if (f != null && !f.isBlank()) {
                parts.add(f.trim());
            }
        }
        if (parts.isEmpty()) {
            return;
        }
        String joined = String.join("\n", parts);
        if (joined.length() > MAX_CHARS) {
            joined = joined.substring(0, MAX_CHARS);
        }
        ModerationClient.Verdict v;
        try {
            v = client.moderate(joined);
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
            log.info("안전 검사에 걸려 막았습니다 ({}): {}", where, hit);
            throw new BusinessException(ErrorCode.INVALID_INPUT, MESSAGE);
        }
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
