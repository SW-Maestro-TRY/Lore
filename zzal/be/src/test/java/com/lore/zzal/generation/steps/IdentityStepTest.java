package com.lore.zzal.generation.steps;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lore.zzal.generation.PromptLoader;
import com.lore.zzal.generation.StepContext;
import com.lore.zzal.generation.StepResult;
import com.lore.zzal.generation.client.ModelSpec;
import com.lore.zzal.generation.client.TextClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 정체성 문단 저장 전 검사(#688).
 *
 * 견본은 2026-10-06 운영 실사용자 펫 3·4(안내문, 거부 대상)와 펫 5·6·7(정상 문단) 원문 그대로다.
 */
class IdentityStepTest {

    // ── 거부 대상: 모델이 그림을 못 봤다며 돌려준 안내문 ──
    static final String PET3 = "Please upload or paste the character sheet image so I can study it. I'll then return one single 350–550 character English paragraph that begins as a continuation of \"Input image 1: \" (e.g., \"the sole identity/style reference.\"), densely listing only the visible identity features to preserve and ending with an instruction to ignore all sheet text, labels, boxes, swatches, palettes, charts, annotations, and separate props—no line breaks, no quotes.";
    static final String PET4 = "I can do this, but I need the actual character sheet image to list the exact facial features, colors, outfit parts, and styling. Please upload the sheet (or a link). Once I see it, I’ll write one 350–550 character paragraph starting with “the sole identity/style reference.” and densely enumerating only the visible identity traits and art style, ending with an instruction to ignore all text, labels, boxes, swatches, palettes, charts, annotations, and separate props.";

    // ── 통과 대상: 정상 문단(627·808·751자) ──
    static final String PET5 = "the ONLY character identity/style reference. Preserve exactly the same SD chibi proportions (big head, short body), clean soft linework and warm, softly shaded coloring style, fair skin, round brown eyes with gradient and single highlight, thin brows, tiny nose and mouth, tousled dark-brown hair parted center with loose face strands and a low ponytail, dark round glasses, black leather biker jacket with notched lapels, studs and zips, white open-collar shirt, black slacks, black lace-up shoes, black belt with metal buckle. Ignore all sheet text, labels, boxes, swatches, palettes, charts, annotations, and separate props.";
    static final String PET6 = "the sole identity/style reference. Reproduce exactly the same chibi proportions (large head, short limbs), round face with tiny nose/mouth, big amber-brown sparkly eyes with star highlights, soft blush, light skin tone, long wavy dark brown twin ponytails tied with blue bows, straight bangs and side locks, dark brown cat ears with pale pink inner fur, fluffy dark tail with white tip and white tail-base puff, white frilled maid headband, blue-and-white maid dress with white apron and scalloped ruffles, large blue back bow, short puff sleeves with blue bows, blue neck ribbon with gold bell and cat charm, white frilled socks, blue Mary Janes, clean thin linework and soft pastel cel shading. Ignore the sheet’s text, labels, boxes, swatches, palettes, charts, annotations and separately displayed props.";
    static final String PET7 = "the ONLY character identity/style reference. Preserve exactly the same chibi proportions (large head, short limbs), face and eye design, blue‑violet gradient irises with star/heart highlights, fair skin, long dark indigo hair with blunt bangs and soft waves fading to periwinkle tips, a side pink bow with heart jewel and dangling chain, scattered tiny star clips. Reproduce exactly the same pink plaid suspender dress with big chest bow, puff‑sleeve blouse with frilled cuffs, heart belt and chains, layered ruffle hem, white ruffled socks, chunky pink platform Mary Janes, heart choker; keep thin clean linework and soft pastel coloring. Ignore the sheet’s text, labels, boxes, swatches, palettes, charts, annotations and separately displayed props.";

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void attachLog() {
        logger = (Logger) LoggerFactory.getLogger(IdentityStep.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detachLog() {
        logger.detachAppender(logs);
    }

    // ───────────── 검사 함수 ─────────────

    @Test
    void 안내문은_거부된다() {
        assertThat(PET3.length()).isEqualTo(450);
        assertThat(IdentityStep.rejectReason(PET3)).isEqualTo("'the ' 로 시작하지 않음");
        assertThat(IdentityStep.rejectReason(PET4)).isEqualTo("'the ' 로 시작하지 않음");
    }

    @Test
    void 안내문을_the_로_시작하게_바꿔도_금칙어로_거부된다() {
        assertThat(IdentityStep.rejectReason("the " + PET3)).isEqualTo("금칙어 'please'");
        // 펫4 안내문은 preserve/reproduce 도 없어 그 단계에서 먼저 걸린다. 그 말을 넣어도 금칙어로 걸린다.
        assertThat(IdentityStep.rejectReason("the " + PET4)).isEqualTo("preserve/reproduce 없음");
        assertThat(IdentityStep.rejectReason("the " + PET4 + " Preserve.")).startsWith("금칙어");
    }

    @Test
    void 정상_문단은_통과한다() {
        assertThat(PET5.length()).isEqualTo(627);
        assertThat(PET6.length()).isEqualTo(808);
        assertThat(PET7.length()).isEqualTo(751);
        assertThat(IdentityStep.rejectReason(PET5)).isNull();
        assertThat(IdentityStep.rejectReason(PET6)).isNull(); // 'pastel' 이 'paste' 에 걸리면 안 된다
        assertThat(IdentityStep.rejectReason(PET7)).isNull();
    }

    @Test
    void 길이_경계() {
        assertThat(IdentityStep.rejectReason(padTo(299))).startsWith("너무 짧음");
        assertThat(IdentityStep.rejectReason(padTo(300))).isNull();
        assertThat(IdentityStep.rejectReason(padTo(1000))).isNull();
        assertThat(IdentityStep.rejectReason(padTo(1001))).startsWith("너무 김");
    }

    @Test
    void 줄바꿈_대문자_Please_preserve_없음_빈문단() {
        assertThat(IdentityStep.rejectReason(PET5.replace(" Ignore", "\nIgnore"))).isEqualTo("줄바꿈 있음");
        assertThat(IdentityStep.rejectReason(PET5.replace("Ignore all", "Please ignore all")))
                .isEqualTo("금칙어 'please'");
        assertThat(IdentityStep.rejectReason(PET5.replace("Preserve", "Keep"))).isEqualTo("preserve/reproduce 없음");
        assertThat(IdentityStep.rejectReason("The " + PET5.substring(4))).isEqualTo("'the ' 로 시작하지 않음");
        assertThat(IdentityStep.rejectReason("")).isEqualTo("빈 문단");
        assertThat(IdentityStep.rejectReason(null)).isEqualTo("빈 문단");
    }

    @Test
    void 옷_묘사의_detached는_금칙어가_아니다() {
        assertThat(IdentityStep.rejectReason(PET5.replace("dark round glasses", "detached sleeves"))).isNull();
    }

    // ───────────── 재요청 흐름 ─────────────

    @Test
    void 첫_응답이_통과면_한_번만_부른다() throws Exception {
        TextClient client = mock(TextClient.class);
        when(client.generate(anyString(), anyList(), any())).thenReturn(new TextClient.Result(PET5, cost("0.018")));

        StepResult r = step(client, true).run(ctx());

        assertThat(r.text()).isEqualTo(PET5);
        assertThat(r.costUsd()).isEqualByComparingTo("0.018");
        verify(client, times(1)).generate(anyString(), anyList(), any());
    }

    @Test
    void 거부되면_재요청_없이_빈_문단으로_진행하고_경고를_남긴다() throws Exception {
        TextClient client = mock(TextClient.class);
        when(client.generate(anyString(), anyList(), any())).thenReturn(new TextClient.Result(PET4, cost("0.010")));

        StepResult r = step(client, true).run(ctx());

        assertThat(r.text()).isEmpty();
        assertThat(r.costUsd()).isEqualByComparingTo("0.010");
        assertThat(r.model()).isEqualTo("gpt-5");
        verify(client, times(1)).generate(anyString(), anyList(), any());
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains("IDENTITY_BLANK").contains("petId=42")
                .contains("'the ' 로 시작하지 않음").contains("I can do this");
    }

    @Test
    void 펫3_안내문도_빈_문단이_된다() throws Exception {
        TextClient client = mock(TextClient.class);
        when(client.generate(anyString(), anyList(), any())).thenReturn(new TextClient.Result(PET3, cost("0.012")));

        StepResult r = step(client, true).run(ctx());

        assertThat(r.text()).isEmpty();
        verify(client, times(1)).generate(anyString(), anyList(), any());
        assertThat(warnings().get(0)).contains("Please upload");
    }

    @Test
    void 스위치를_끄면_옛_동작대로_그대로_저장한다() throws Exception {
        TextClient client = mock(TextClient.class);
        when(client.generate(anyString(), anyList(), any())).thenReturn(new TextClient.Result(PET4, cost("0.010")));

        StepResult r = step(client, false).run(ctx());

        assertThat(r.text()).isEqualTo(PET4);
        verify(client, times(1)).generate(anyString(), anyList(), any());
        assertThat(warnings()).isEmpty();
    }

    // ───────────── 도우미 ─────────────

    private static IdentityStep step(TextClient client, boolean validate) {
        PromptLoader prompts = mock(PromptLoader.class);
        when(prompts.prompt("v1", IdentityStep.NAME)).thenReturn("PROMPT");
        when(prompts.model("v1", IdentityStep.NAME)).thenReturn(ModelSpec.of("gpt-5"));
        return new IdentityStep(client, prompts, validate, 90);
    }

    private static StepContext ctx() {
        StepContext ctx = new StepContext(42L, "펫", null, "v1");
        ctx.putImage(SheetStep.NAME, "images/zzal/pets/42/sheet.png");
        return ctx;
    }

    private List<String> warnings() {
        return logs.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static BigDecimal cost(String v) {
        return new BigDecimal(v);
    }

    /** 통과 조건을 다 갖춘 n 자 문단. */
    private static String padTo(int n) {
        String base = "the sole identity/style reference. Preserve exactly the same look.";
        StringBuilder sb = new StringBuilder(base);
        while (sb.length() < n) {
            sb.append('x');
        }
        return sb.toString();
    }
}
