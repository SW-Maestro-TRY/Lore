package com.lore.zzal.generation.steps;

import com.lore.zzal.generation.GenerationStep;
import com.lore.zzal.generation.PromptLoader;
import com.lore.zzal.generation.StepContext;
import com.lore.zzal.generation.StepResult;
import com.lore.zzal.generation.client.ModelSpec;
import com.lore.zzal.generation.client.TextClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2단계 — 시트를 보고 생김새를 글로 받아 적는다.
 *
 * ★ 왜 글을 한 번 거치나 — 그림만 주고 "이대로 8가지 표정을 그려줘" 하면 캐릭터가 조금씩
 *   달라진다. 글로 못박아 두면 생성이 안정된다(2026-08-26 실측에서 확인).
 *
 * ⚠️ 이 단계가 실패의 원인이 된 적이 있다 — 고양이 시트를 보고 엉뚱한 캐릭터를 묘사하는
 *    문단이 나왔고, 그 문단 때문에 다음 단계가 차단됐다. 그래서 격자가 '거부' 로 실패하면
 *    이 단계부터 다시 한다.
 *
 * ★ 이 단계는 없어질 수도 있다(상훈님 2026-09-02). 그때는 파이프라인 목록에서 빼면 되고,
 *   이 클래스는 남겨 v1 로 만들어진 펫들을 계속 설명한다.
 *
 * ★ 저장 전 검사(2026-10-07, #688) — 운영 5건 중 2건에서 모델이 그림을 못 봤다며
 *   "Please upload the character sheet…" 같은 안내문을 돌려줬고, 그게 그대로 {IDENT} 자리에 들어갔다.
 *   상훈님 결정(10/7 정정): 감지되면 재요청 없이 바로 빈 문단으로 진행한다.
 *   빈 문단이면 격자 단계가 {IDENT} 자리를 지운다(GridStep). 원인 조사는 하지 않기로 했다.
 *   스위치: app.zzal.identity.validate (기본 true, 끄면 옛 동작).
 *
 * 실측 15~22초 · $0.018 (운영 10/7 gpt-5 는 39~48초까지 — 아래 시간 제한 참고)
 */
@Component
public class IdentityStep implements GenerationStep {

    public static final String NAME = "identity";

    private static final Logger log = LoggerFactory.getLogger(IdentityStep.class);

    /** 통과 길이. 프롬프트는 350~550자를 요구하지만 정상 문단이 627·751·808자까지 나온다(10/6 운영 실측). */
    static final int MIN_CHARS = 300;
    static final int MAX_CHARS = 1000;

    /**
     * 안내문(그림을 달라는 답)에만 나오는 말. 단어 경계로 잡는다 —
     * 'paste' 가 'pastel' 에, 'attach' 가 'detached' 에 걸리면 정상 문단을 버리게 된다.
     */
    private static final Pattern BANNED = Pattern.compile(
            "\\bupload|\\bpaste\\b|\\battach\\b|\\battach(?:ed|ing)? (?:the |an |a |your )?(?:image|sheet|file)"
                    + "|\\bi can |\\bi need\\b|\\bi'll\\b|\\bplease\\b|\\bimage you\\b|\\bprovide the\\b|\\bshare the\\b");

    private final TextClient textClient;
    private final PromptLoader prompts;
    private final boolean validate;
    /** 이 단계의 시간 제한(초). 설정 {@code app.zzal.openai.text-timeout-seconds}. */
    private final int timeoutSeconds;

    /**
     * 시도 차수별 reasoning_effort. 0번 칸 = 시도 1. 빈 칸(null) = 보내지 않음(모델 기본 medium).
     * 목록보다 차수가 크면 마지막 값. 목록이 비면 언제나 안 보낸다.
     *
     * ★ 2026-10-27 상훈님 결정 — "안 되는 거 계속해서 뭐하냐". 첫 시도는 지금 품질 그대로(기본),
     *   실패해 다시 굽게 되면 생각을 줄여(low → minimal) 빨리 끝내게 한다. 10/26 OpenAI 지연 때
     *   출력 약 1,900토큰 중 약 1,800이 생각 토큰이었고 같은 조건으로 다섯 번 다 90초를 넘겼다.
     *   ★ "none" 은 쓰지 않는다 — gpt-5(2025-08-07)가 받는 최저값은 minimal 이다.
     */
    private final List<String> effortByAttempt;

    /** 테스트·기존 호출용 — 차수 무관하게 effort 를 보내지 않는다(이전 동작). */
    public IdentityStep(TextClient textClient, PromptLoader prompts, boolean validate, int timeoutSeconds) {
        this(textClient, prompts, validate, timeoutSeconds, "");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public IdentityStep(TextClient textClient, PromptLoader prompts,
                        @Value("${app.zzal.identity.validate:true}") boolean validate,
                        @Value("${app.zzal.openai.text-timeout-seconds:90}") int timeoutSeconds,
                        @Value("${app.zzal.openai.identity-reasoning-effort-by-attempt:,low,minimal}") String effortByAttempt) {
        this.textClient = textClient;
        this.prompts = prompts;
        this.validate = validate;
        this.timeoutSeconds = timeoutSeconds;
        this.effortByAttempt = parseEffortByAttempt(effortByAttempt);
    }

    /**
     * ",low,minimal" → [null, "low", "minimal"]. 잘못된 값은 기동 시 예외(설정 이름 포함).
     * 전체가 비면(빈 문자열) 빈 목록 = 언제나 안 보냄.
     */
    public static List<String> parseEffortByAttempt(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new java.util.ArrayList<>();
        for (String item : raw.split(",", -1)) {
            out.add(com.lore.zzal.generation.client.OpenAiTextClient.normalizeReasoningEffort(item));
        }
        return java.util.Collections.unmodifiableList(out);
    }

    /** 이 차수에 보낼 effort. null = 보내지 않음. */
    public String effortFor(int attempt) {
        if (effortByAttempt.isEmpty()) return null;
        int idx = Math.min(Math.max(1, attempt), effortByAttempt.size()) - 1;
        return effortByAttempt.get(idx);
    }

    /**
     * 정체성 문단이 쓸 만한가. 통과면 null, 아니면 거부 사유.
     *
     * 통과 조건: 300~1000자 · 소문자 "the " 로 시작 · preserve/reproduce 포함 · 금칙어 없음 · 줄바꿈 없음.
     */
    public static String rejectReason(String text) {
        if (text == null || text.isBlank()) {
            return "빈 문단";
        }
        String t = text.trim();
        if (t.indexOf('\n') >= 0 || t.indexOf('\r') >= 0) {
            return "줄바꿈 있음";
        }
        if (t.length() < MIN_CHARS) {
            return "너무 짧음(%d자 < %d)".formatted(t.length(), MIN_CHARS);
        }
        if (t.length() > MAX_CHARS) {
            return "너무 김(%d자 > %d)".formatted(t.length(), MAX_CHARS);
        }
        if (!t.startsWith("the ")) {
            return "'the ' 로 시작하지 않음";
        }
        // 굽은 따옴표(’)를 곧은 따옴표로 — 실제 안내문이 "I’ll" 로 왔다.
        String lower = t.toLowerCase(Locale.ROOT).replace('\u2019', '\'');
        if (!lower.contains("preserve") && !lower.contains("reproduce")) {
            return "preserve/reproduce 없음";
        }
        Matcher m = BANNED.matcher(lower);
        if (m.find()) {
            return "금칙어 '%s'".formatted(m.group().trim());
        }
        return null;
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * ★ 2026-10-08 60 → 90(설정값으로 승격). 운영 10/7 실측 gpt-5 응답 39~48초 — 린델(pet52)·로미(pet48)
     *   둘 다 시도 1이 이 단계 TIMEOUT(60초)으로 끝나 5회 중 1회를 격자와 무관하게 잃었다.
     *   HTTP 요청 자체의 상한(OpenAiTextClient 120초)보다 짧아야 이 값이 실제로 먹는다.
     */
    @Override
    public int limitSeconds() {
        return timeoutSeconds;
    }

    @Override
    public String label() {
        return "생김새를 정리하는 중";
    }

    @Override
    public StepResult run(StepContext ctx) throws Exception {
        // ★★ 자유 메모(note)는 여기 안 들어간다 — 2026-09-11 상훈님 결정.
        //   원문: "자유 메모는 움짤보다는 나중에 채팅 기능 넣을 때 퀄리티를 높이기 위한 방법이었어.
        //   메모는 일단 백엔드에 저장이 될 거 아냐. 나중에 쓰는 쪽으로 하자 채팅 때"
        //
        //   ★ 빠뜨린 것이 아니라 <b>뺀 것</b>이다. 메모는 zzal_pet.note 에 그대로 저장되고,
        //     채팅(특히 LLM 을 붙일 때) 재료로 쓴다. 되돌리지 말 것.
        //   ★ 그림이 이름을 기다릴 이유가 사라진 것도 이 변경 때문이다 — 메모가 마지막 남은
        //     "캐릭터 정보에서 그림으로 가는 줄" 이었다. 지금은 그림 등록 즉시 끝까지 굽는다.
        String prompt = prompts.prompt(ctx.version(), NAME);

        ModelSpec spec = prompts.model(ctx.version(), NAME);
        List<String> refs = List.of(ctx.image(SheetStep.NAME));
        int attempt = ctx.attempt();
        String effort = effortFor(attempt);
        String effortLabel = effort == null ? "default" : effort;
        TextClient.Result r;
        try {
            // 기본(effort 없음)은 예전과 똑같은 호출 경로로 간다.
            r = effort == null ? textClient.generate(prompt, refs, spec) : textClient.generate(prompt, refs, spec, effort);
        } catch (Exception e) {
            log.warn("정체성 문단 호출 실패 — petId={} attempt={} effort={} {}: {}",
                    ctx.petId(), attempt, effortLabel, e.getClass().getSimpleName(), e.getMessage());
            throw e;
        }
        log.info("정체성 문단 받음 — petId={} attempt={} effort={} {}자", ctx.petId(), attempt, effortLabel,
                r.text() == null ? 0 : r.text().length());
        if (!validate) {
            return StepResult.text(NAME, r.text(), spec.model(), r.costUsd());
        }

        String reason = rejectReason(r.text());
        if (reason == null) {
            return StepResult.text(NAME, r.text(), spec.model(), r.costUsd());
        }
        // 재요청은 하지 않는다(상훈님 2026-10-07 정정) — 바로 빈 문단으로 진행한다.
        // ★ error_code 에 IDENTITY_BLANK 를 넣지 않는 이유 — zzal_gen_step.error_code 에 CHECK 제약
        //   (TIMEOUT · MODERATION_BLOCKED · UNKNOWN)이 있어 넣으면 저장이 터진다. 로그로만 남긴다.
        log.warn("정체성 문단 거부 — IDENTITY_BLANK, 빈 문단으로 진행 — petId={} attempt={} effort={} 사유={} 앞60자={}",
                ctx.petId(), attempt, effortLabel, reason, head60(r.text()));
        return StepResult.text(NAME, "", spec.model(), r.costUsd());
    }

    private static String head60(String text) {
        if (text == null) {
            return "";
        }
        String t = text.strip().replaceAll("\\s+", " ");
        return t.length() <= 60 ? t : t.substring(0, 60);
    }

}
