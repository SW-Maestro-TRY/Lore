package com.lore.common.credit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 무료 몫과 산 몫 나눠 세기. 보고 싶은 것은 둘이다 —
 * <b>무료부터 빠지는가</b>, <b>두 몫의 합이 언제나 잔액과 같은가.</b>
 */
class CreditPoolsTest {

    private final List<CreditEvent> rows = new ArrayList<>();

    private CreditPoolsTest add(int delta, CreditReason reason, CreditDomain domain, String refId) {
        rows.add(CreditEvent.of(1L, delta, reason, domain, refId, null, Instant.EPOCH));
        return this;
    }

    private CreditPools.Split split() {
        CreditPools.Split s = CreditPools.replay(rows);
        int sum = rows.stream().mapToInt(CreditEvent::getDelta).sum();
        assertThat(s.total()).as("두 몫의 합 = 잔액").isEqualTo(sum);
        return s;
    }

    @Test
    @DisplayName("산 적이 없으면 전부 무료 몫이다")
    void 무료만() {
        add(12, CreditReason.WELCOME, CreditDomain.COMMON, "welcome")
                .add(20, CreditReason.DAILY, CreditDomain.COMMON, "2026-10-13")
                .add(-12, CreditReason.SPEND, CreditDomain.WEBTOON, "job-1");

        assertThat(split()).isEqualTo(new CreditPools.Split(20, 0));
    }

    @Test
    @DisplayName("쓸 때는 무료 몫부터 빠지고, 모자라는 만큼만 산 몫에서 빠진다")
    void 무료부터() {
        add(20, CreditReason.DAILY, CreditDomain.COMMON, "d1")
                .add(70, CreditReason.PURCHASE, CreditDomain.COMMON, "payapp-A")
                .add(-12, CreditReason.SPEND, CreditDomain.WEBTOON, "job-1");
        assertThat(split()).isEqualTo(new CreditPools.Split(8, 70));

        add(-12, CreditReason.SPEND, CreditDomain.WEBTOON, "job-2");
        assertThat(split()).isEqualTo(new CreditPools.Split(0, 66));
    }

    @Test
    @DisplayName("산 뒤에 받은 무료 몫도 먼저 쓴다 — 받은 순서가 아니라 종류로 가른다")
    void 나중에_받은_무료도_먼저() {
        add(70, CreditReason.PURCHASE, CreditDomain.COMMON, "payapp-A")
                .add(20, CreditReason.DAILY, CreditDomain.COMMON, "d2")
                .add(-12, CreditReason.SPEND, CreditDomain.WEBTOON, "job-1");

        assertThat(split()).isEqualTo(new CreditPools.Split(8, 70));
    }

    @Test
    @DisplayName("만들기 실패로 돌려줄 때는 빼 간 몫으로 그대로 돌려준다")
    void 실패_환원은_원래_몫으로() {
        add(5, CreditReason.DAILY, CreditDomain.COMMON, "d1")
                .add(70, CreditReason.PURCHASE, CreditDomain.COMMON, "payapp-A")
                .add(-12, CreditReason.SPEND, CreditDomain.WEBTOON, "job-1");     // 무료 5 + 산 7
        assertThat(split()).isEqualTo(new CreditPools.Split(0, 63));

        add(12, CreditReason.REFUND, CreditDomain.WEBTOON, "job-1");
        assertThat(split()).isEqualTo(new CreditPools.Split(5, 70));
    }

    @Test
    @DisplayName("다른 서비스의 같은 refId 는 다른 일이다")
    void 도메인이_다르면_다른_일() {
        add(70, CreditReason.PURCHASE, CreditDomain.COMMON, "payapp-A")
                .add(-10, CreditReason.SPEND, CreditDomain.WEBTOON, "x")          // 산 몫에서 10
                .add(5, CreditReason.DAILY, CreditDomain.COMMON, "d1")
                .add(10, CreditReason.REFUND, CreditDomain.ZZAL, "x");            // 짤의 x — 빼 간 적 없음

        assertThat(split()).isEqualTo(new CreditPools.Split(15, 60));
    }

    @Test
    @DisplayName("결제 취소로 회수하면 산 몫에서 빠진다")
    void 결제취소() {
        add(20, CreditReason.DAILY, CreditDomain.COMMON, "d1")
                .add(30, CreditReason.PURCHASE, CreditDomain.COMMON, "payapp-A")
                .add(-30, CreditReason.ADJUST, CreditDomain.COMMON, "payapp-cancel-A");

        assertThat(split()).isEqualTo(new CreditPools.Split(20, 0));
    }

    @Test
    @DisplayName("산 몫을 다 쓴 뒤 결제를 취소하면 모자라는 만큼 잔액이 0 아래로 간다")
    void 다_쓰고_취소() {
        add(30, CreditReason.PURCHASE, CreditDomain.COMMON, "payapp-A")
                .add(-24, CreditReason.SPEND, CreditDomain.WEBTOON, "job-1")
                .add(-30, CreditReason.ADJUST, CreditDomain.COMMON, "payapp-cancel-A");

        assertThat(split()).isEqualTo(new CreditPools.Split(-24, 0));
    }

    @Test
    @DisplayName("관리자가 손으로 뺀 것은 무료 몫부터 뺀다")
    void 손으로_뺀_것() {
        add(10, CreditReason.DAILY, CreditDomain.COMMON, "d1")
                .add(30, CreditReason.PURCHASE, CreditDomain.COMMON, "payapp-A")
                .add(-15, CreditReason.ADJUST, CreditDomain.COMMON, "fix-1");

        assertThat(split()).isEqualTo(new CreditPools.Split(0, 25));
    }
}
