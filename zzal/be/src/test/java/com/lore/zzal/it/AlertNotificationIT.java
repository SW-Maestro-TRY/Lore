package com.lore.zzal.it;

import com.lore.zzal.alert.AlertKeys;
import com.lore.zzal.alert.AlertLedger;
import com.lore.zzal.alert.AlertMailer;
import com.lore.zzal.alert.AlertSettings;
import com.lore.zzal.alert.FakeAlertMailer;
import com.lore.zzal.alert.ZzalAlertState;
import com.lore.zzal.alert.ZzalAlertStateRepository;
import com.lore.zzal.alert.ZzalAlerts;
import com.lore.zzal.generation.GenJob;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 경보 — <b>진짜 표에서</b> 같은 메일이 두 번 가지 않는다.
 *
 * <h3>★★ 왜 단위 시험만으로는 부족한가</h3>
 * 중복 막기는 결국 포스트그레스 한 문장에 걸려 있다({@code insert … on conflict … where}).
 * 그 문장이 실제로 "값이 같으면 0" 을 돌려주는지는 진짜 DB 라야 보인다 — 메모리 원장으로는
 * <b>규칙은 맞는데 SQL 이 틀린</b> 경우를 영영 못 본다. 그리고 그 사고의 증상은 예외가 아니라
 * "같은 메일이 계속 온다" 라서, 배포한 다음 날 메일함에서만 드러난다.
 *
 * <h3>★★ 재시작을 흉내 낸다</h3>
 * 표는 그대로 두고 {@link ZzalAlerts} 만 새로 만들어 다시 부른다. 어디엔가 메모리로 세고 있었다면
 * 그 순간 두 번째 메일이 나가고 이 시험이 빨개진다.
 *
 * <h3>★ 메일은 실제로 안 나간다</h3>
 * {@link AlertMailer} 가 {@link FakeAlertMailer} 로 덮여 있다({@code ZzalItConfig}) — 스위치가 아니라
 * 타입으로 막혀 있어, 이 시험이 경보를 켜도 SMTP 로 나가는 길이 없다.
 */
@ZzalIntegrationTest
@TestPropertySource(properties = {
        "app.zzal.alert.enabled=true",
        "app.zzal.alert.to=ops@example.invalid",
        "app.zzal.alert.cost-step-usd=10",
})
@DisplayName("운영 경보 — 재시작해도 같은 메일이 다시 가지 않는다")
class AlertNotificationIT extends ZzalItSupport {

    @Autowired ZzalAlerts alerts;
    @Autowired AlertSettings settings;
    @Autowired AlertLedger ledger;
    @Autowired ZzalAlertStateRepository states;
    @Autowired GenJobRepository jobs;
    @Autowired ZzalPetRepository pets;
    @Autowired AlertMailer mailer;

    private FakeAlertMailer mail() {
        return (FakeAlertMailer) mailer;
    }

    @BeforeEach
    void forgetSentMail() {
        mail().clear();
    }

    @Test
    @DisplayName("★ 시험 컨텍스트의 발송기는 대역이다 — 어떤 경로로도 진짜 메일이 안 나간다")
    void theMailerIsNeverReal() {
        assertThat(mailer).isInstanceOf(FakeAlertMailer.class);
        assertThat(settings.canSend()).isTrue();
    }

    @Test
    @DisplayName("★★ 임계를 넘으면 한 통 — 다시 불러도, 재시작해도 더 안 온다")
    void theSameThresholdIsNeverMailedTwice() {
        spend("7.0000");
        spend("5.0000");                        // 합계 $12 — $10 을 넘었다

        alerts.generationFinished(Instant.now());
        assertThat(mail().count()).isEqualTo(1);
        assertThat(mail().lastSubject()).contains("$10");

        // 같은 서버가 계속 도는 경우
        alerts.generationFinished(Instant.now());
        assertThat(mail().count()).isEqualTo(1);

        // ★★ 재시작 — 표는 그대로, 경보만 새로.
        ZzalAlerts afterRestart = new ZzalAlerts(settings, mailer, ledger, jobs, pets);
        afterRestart.generationFinished(Instant.now());

        assertThat(mail().count()).as("배포할 때마다 같은 메일이 오면 안 된다").isEqualTo(1);
        assertThat(states.findById(AlertKeys.COST))
                .get().extracting(ZzalAlertState::getLastValue).isEqualTo("10");
    }

    @Test
    @DisplayName("★ 다음 단위($20)를 넘으면 재시작 뒤에도 그때는 보낸다")
    void theNextThresholdStillArrives() {
        spend("12.0000");
        alerts.generationFinished(Instant.now());
        assertThat(mail().count()).isEqualTo(1);

        spend("9.0000");                        // 합계 $21
        new ZzalAlerts(settings, mailer, ledger, jobs, pets).generationFinished(Instant.now());

        assertThat(mail().count()).isEqualTo(2);
        assertThat(mail().lastSubject()).contains("$20");
    }

    @Test
    @DisplayName("★★ 원장 한 문장 — 같은 값은 0, 새 값은 1 (진짜 UPSERT 가 판정한다)")
    void theLedgerClaimIsDecidedByTheDatabase() {
        Instant now = Instant.now();
        assertThat(ledger.claimOnce(AlertKeys.NIGHT_BAKE_FAILED, "2026-09-18", now)).isTrue();
        assertThat(ledger.claimOnce(AlertKeys.NIGHT_BAKE_FAILED, "2026-09-18", now)).isFalse();
        assertThat(ledger.claimOnce(AlertKeys.NIGHT_BAKE_FAILED, "2026-09-19", now)).isTrue();

        assertThat(ledger.claimCostThreshold(10, now)).isTrue();
        assertThat(ledger.claimCostThreshold(10, now)).isFalse();
        assertThat(ledger.claimCostThreshold(10, now)).isFalse();
        assertThat(ledger.claimCostThreshold(20, now)).isTrue();
        // ★ 뒤로는 안 간다 — 합계가 내려가도 이미 알린 금액을 다시 알리지 않는다.
        assertThat(ledger.claimCostThreshold(10, now)).isFalse();
    }

    @Test
    @DisplayName("★★ 하루 상한 경보는 그날 한 번 — 거절이 계속돼도 메일은 하나")
    void theDailyCapMailsOncePerDay() {
        Instant noon = kstToday(12, 0);
        alerts.serviceDailyCapReached(40, 40, noon);
        alerts.serviceDailyCapReached(41, 40, noon.plusSeconds(60));
        alerts.serviceDailyCapReached(42, 40, noon.plusSeconds(120));

        assertThat(mail().count()).isEqualTo(1);
        assertThat(mail().lastSubject()).contains("40회");
        assertThat(mail().lastBody()).contains("자정");
    }

    /** 그만큼 돈을 쓴 굽기 기록 한 줄. ★ 실패한 시도도 돈은 나갔으므로 성공·실패를 가리지 않는다. */
    private void spend(String usd) {
        Long userId = newUserId();
        ZzalPet pet = transactions.execute(status ->
                petRepository.save(ZzalPet.draft(userId, newUploadedImageKey(userId), Instant.now())));
        transactions.executeWithoutResult(status -> {
            GenJob job = jobs.save(GenJob.start(pet.getId(), GenKind.HATCH, 1, "v1", Instant.now()));
            job.succeed(new BigDecimal(usd), Instant.now());
            jobs.save(job);
        });
    }
}
