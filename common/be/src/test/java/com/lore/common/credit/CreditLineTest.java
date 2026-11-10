package com.lore.common.credit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내역 한 줄에 보이는 말.
 *
 * 출석 보상은 첫날 몇 시간 동안 이유의 기본 문구(「활동 보상」)를 메모로 적었다. 그 줄을
 * 고쳐 쓰지 않고 보여 줄 때만 「출석 보상」으로 바꾼다. 같은 REWARD 를 쓰는 다른 활동은
 * 그대로여야 한다.
 */
class CreditLineTest {

    private static final Instant AT = Instant.parse("2026-10-27T15:30:00Z");

    private static CreditEvent row(CreditReason reason, CreditDomain domain, String refId, String memo) {
        return CreditEvent.of(1L, 2, reason, domain, refId, memo, AT);
    }

    @Test
    @DisplayName("기본 문구로 적힌 옛 출석 줄은 「출석 보상」으로 보인다")
    void 옛_출석_줄() {
        var line = CreditController.Line.from(
                row(CreditReason.REWARD, CreditDomain.COMMON, "attend-2026-10-28-d1", "활동 보상"));
        assertThat(line.memo()).isEqualTo("출석 보상");
        assertThat(line.reason()).isEqualTo("REWARD");
        assertThat(line.label()).isEqualTo("활동 보상");
    }

    @Test
    @DisplayName("메모가 비어 있는 출석 줄도 「출석 보상」으로 보인다")
    void 메모_없는_출석_줄() {
        assertThat(CreditController.Line.from(
                row(CreditReason.REWARD, CreditDomain.COMMON, "attend-2026-10-28-d4", null)).memo())
                .isEqualTo("출석 보상");
    }

    @Test
    @DisplayName("다른 활동 보상(웹툰 설문 등)은 그대로 「활동 보상」")
    void 다른_활동은_그대로() {
        assertThat(CreditController.Line.from(
                row(CreditReason.REWARD, CreditDomain.WEBTOON, "webtoon-feedback-survey", "활동 보상")).memo())
                .isEqualTo("활동 보상");
        assertThat(CreditController.Line.from(
                row(CreditReason.REWARD, CreditDomain.ZZAL, "share-2026-09", "활동 보상")).memo())
                .isEqualTo("활동 보상");
    }

    @Test
    @DisplayName("사람이 따로 적은 메모는 출석 줄이어도 덮어쓰지 않는다")
    void 따로_적은_메모() {
        assertThat(CreditController.Line.from(
                row(CreditReason.REWARD, CreditDomain.COMMON, "attend-2026-10-28-d7", "할로윈 출석 7일째")).memo())
                .isEqualTo("할로윈 출석 7일째");
    }

    @Test
    @DisplayName("REWARD 가 아닌 이유는 refId 가 attend- 로 시작해도 손대지 않는다")
    void 다른_이유() {
        assertThat(CreditController.Line.from(
                row(CreditReason.ADJUST, CreditDomain.COMMON, "attend-fix", "직접 조정")).memo())
                .isEqualTo("직접 조정");
    }
}
