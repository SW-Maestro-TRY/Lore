package com.lore.zzal.alert;

import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenerationRecorder;
import com.lore.zzal.generation.GenerationRunner;
import com.lore.zzal.generation.GenerationStep;
import com.lore.zzal.generation.RunResult;
import com.lore.zzal.generation.StepContext;
import com.lore.zzal.generation.StepResult;
import com.lore.zzal.pet.ZzalPetRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>경보가 굽기를 깨지 않는다</b> — 메일이 터지는 날에도 부화는 그대로 끝난다.
 *
 * <h3>★★ 왜 실행기까지 와서 보나</h3>
 * "{@code ZzalAlerts} 가 예외를 안 던진다" 만 보면, 그 규약이 실제로 걸려 있는 자리
 * ({@code GenerationRunner} 의 {@code finally})가 한 줄도 안 밟힌다. {@code finally} 에서 예외가
 * 나면 <b>돌려주려던 결과가 통째로 사라져</b> 성공한 부화가 실패로 보인다 — 예외도 로그도
 * 이상해 보이지 않고, 사용자 화면에서만 "태어나지 못했다" 로 드러난다.
 *
 * <h3>★ 실패를 주입해서 본다</h3>
 * 정상 경로만 보면 이 안전장치는 <b>한 번도 실행된 적 없이</b> 배포된다.
 */
@DisplayName("경보 — 메일이 터져도 굽기는 끝난다")
class AlertsNeverBreakGenerationTest {

    @Test
    @DisplayName("★★ 발송이 매번 터져도 굽기는 성공으로 끝나고 비용도 적힌다")
    void aBrokenMailerDoesNotBreakTheBake() {
        GenerationRecorder recorder = mock(GenerationRecorder.class);
        when(recorder.startStep(anyLong(), anyInt(), anyString())).thenReturn(1L);

        FakeAlertMailer mailer = new FakeAlertMailer();
        mailer.explode = true;                              // ★ 메일 서버가 죽은 날

        GenJobRepository jobs = mock(GenJobRepository.class);
        when(jobs.sumAllCost()).thenReturn(new BigDecimal("42.00"));   // 임계를 넘겨 반드시 보내게

        ZzalAlerts alerts = new ZzalAlerts(
                new AlertSettings(true, "ops@example.invalid", 10, 3),
                mailer, new MemoryAlertLedger(), jobs, mock(ZzalPetRepository.class));

        GenerationRunner runner = new GenerationRunner(recorder, alerts);
        StepContext ctx = new StepContext(1L, "아무개", null, "v1");

        RunResult result = runner.run(7L, ctx, List.of(List.of(step("sheet", "0.0630"))), List.of());

        assertThat(result.success()).as("경보가 터졌다고 부화가 실패로 뒤집히면 안 된다").isTrue();
        assertThat(result.costUsd()).isEqualByComparingTo("0.0630");
        verify(recorder).succeedJob(any(), any(), any());
    }

    private static GenerationStep step(String name, String cost) {
        return new GenerationStep() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int limitSeconds() {
                return 5;
            }

            @Override
            public String label() {
                return name;
            }

            @Override
            public StepResult run(StepContext ctx) {
                return StepResult.image(name, name + ".png", "fake", new BigDecimal(cost));
            }
        };
    }
}
