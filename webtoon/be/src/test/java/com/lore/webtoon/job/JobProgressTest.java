package com.lore.webtoon.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 도는 동안 읽는 값.
 *
 * 파이썬이 뱉는 줄에서 "몇 장까지 그렸나" 를 읽어 내는 것이 전부다. 그 규칙이
 * 어긋나면 진행 막대가 그림을 그리는 내내(제일 오래 걸리는 걸음이다) 멈춰
 * 있는 것처럼 보인다.
 */
class JobProgressTest {

    @Test
    @DisplayName("페이지 줄에서 몇 장까지 그렸는지 읽는다")
    void 페이지_줄() {
        JobProgress p = new JobProgress();
        p.line(1L, "[페이지 3/7] 컷 2개 · 참조 2장");

        assertThat(p.of(1L).done()).isEqualTo(3);
        assertThat(p.of(1L).total()).isEqualTo(7);
    }

    @Test
    @DisplayName("장면 줄도 같은 규칙 — 디테일 직행 흐름이 이 모양으로 찍는다")
    void 장면_줄() {
        JobProgress p = new JobProgress();
        p.line(1L, "[장면 2/5] 참조 2장");

        assertThat(p.of(1L).done()).isEqualTo(2);
        assertThat(p.of(1L).total()).isEqualTo(5);
    }

    @Test
    @DisplayName("상관없는 줄은 숫자를 안 건드린다")
    void 딴_줄() {
        JobProgress p = new JobProgress();
        p.line(1L, "[페이지 3/7] 그리는 중");
        p.line(1L, "openai 응답 2.4초");

        assertThat(p.of(1L).done()).isEqualTo(3);
    }

    @Test
    @DisplayName("로그는 끝쪽 60줄만 남긴다 — 사람이 보는 것은 방금 것이다")
    void 로그_상한() {
        JobProgress p = new JobProgress();
        for (int i = 0; i < 200; i++) {
            p.line(1L, "줄 " + i);
        }
        assertThat(p.of(1L).log()).hasSize(60);
        assertThat(p.of(1L).log().getLast()).isEqualTo("줄 199");
    }

    @Test
    @DisplayName("작업마다 따로 센다")
    void 작업마다_따로() {
        JobProgress p = new JobProgress();
        p.line(1L, "[페이지 1/6] a");
        p.line(2L, "[페이지 5/6] b");

        assertThat(p.of(1L).done()).isEqualTo(1);
        assertThat(p.of(2L).done()).isEqualTo(5);
    }

    @Test
    @DisplayName("끝난 작업은 잊는다 — 안 잊으면 서버가 오래 뜰수록 계속 쌓인다")
    void 잊는다() {
        JobProgress p = new JobProgress();
        p.line(1L, "[페이지 1/6] a");
        p.forget(1L);

        assertThat(p.of(1L).log()).isEmpty();
        assertThat(p.of(1L).total()).isZero();
    }

    @Test
    @DisplayName("한 번도 안 본 작업을 물어도 안 죽는다")
    void 모르는_작업() {
        assertThat(new JobProgress().of(999L).log()).isEmpty();
    }
}
