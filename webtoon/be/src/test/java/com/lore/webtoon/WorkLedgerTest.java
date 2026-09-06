package com.lore.webtoon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 누가 만든 작품인지 적는 규칙.
 *
 * 여기서 지키는 것은 <b>적히지 않고 지나가는 자리가 없는가</b>다. 한 번
 * 놓치면 그 작품은 영영 주인이 없고, 주인이 없으면 나중에 권한도 못 붙는다.
 */
class WorkLedgerTest {

    private final List<WebtoonWork> rows = new ArrayList<>();
    /* 계정↔브라우저 연결은 여기서 볼 것이 아니다. 가짜는 "안 이어져 있다" 고
       답하므로, 주인 판정은 계정이 직접 붙은 경우만 본다. */
    private final BrowserLinkRepository links = mock(BrowserLinkRepository.class);
    private WorkLedger ledger;

    @BeforeEach
    void 저장소를_세운다() {
        WebtoonWorkRepository repo = mock(WebtoonWorkRepository.class);
        when(repo.findByJobId(anyString())).thenAnswer(c -> rows.stream()
                .filter(w -> w.getJobId().equals(c.getArgument(0))).findFirst());
        when(repo.existsByRunId(anyString())).thenAnswer(c -> rows.stream()
                .anyMatch(w -> c.getArgument(0).equals(w.getRunId())));
        when(repo.save(any(WebtoonWork.class))).thenAnswer(c -> {
            WebtoonWork w = c.getArgument(0);
            if (!rows.contains(w)) {
                rows.add(w);
            }
            return w;
        });
        ledger = new WorkLedger(repo, links);
    }

    private static byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("만들기가 시작되면 작업 번호로 먼저 적는다 — 작품 번호는 아직 없다")
    void 시작할_때_적는다() {
        ledger.started(json("{\"id\":\"job-1\"}"), 7L, "uid-a");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getJobId()).isEqualTo("job-1");
        assertThat(rows.get(0).getUserId()).isEqualTo(7L);
        assertThat(rows.get(0).getBrowserUid()).isEqualTo("uid-a");
        assertThat(rows.get(0).getRunId()).isNull();
    }

    @Test
    @DisplayName("게스트도 적는다 — 로그인 없이 만드는 것이 이 제품의 약속이다")
    void 게스트도_적는다() {
        ledger.started(json("{\"id\":\"job-1\"}"), null, "uid-a");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getUserId()).isNull();
        assertThat(rows.get(0).getBrowserUid()).isEqualTo("uid-a");
    }

    @Test
    @DisplayName("브라우저 값이 없으면 안 적는다 — 가리킬 것이 없는 줄은 쓸모가 없다")
    void 가리킬_것이_없으면_안_적는다() {
        ledger.started(json("{\"id\":\"job-1\"}"), null, null);
        ledger.started(json("{\"id\":\"job-2\"}"), null, "   ");

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("진행 조회에서 작품 번호를 채운다")
    void 작품_번호를_채운다() {
        ledger.started(json("{\"id\":\"job-1\"}"), 7L, "uid-a");
        ledger.progressed("job-1", json("{\"run_id\":\"run-1\",\"status\":\"running\"}"), 7L);

        assertThat(rows.get(0).getRunId()).isEqualTo("run-1");
    }

    @Test
    @DisplayName("이미 채워진 작품 번호는 안 덮는다 — 나중 값이 더 맞을 이유가 없다")
    void 한_번_채우면_안_덮는다() {
        ledger.started(json("{\"id\":\"job-1\"}"), 7L, "uid-a");
        ledger.progressed("job-1", json("{\"run_id\":\"run-1\"}"), 7L);
        ledger.progressed("job-1", json("{\"run_id\":\"run-딴것\"}"), 7L);

        assertThat(rows.get(0).getRunId()).isEqualTo("run-1");
    }

    @Test
    @DisplayName("게스트로 시작했다가 도중에 로그인하면 그때 주인이 붙는다")
    void 도중에_로그인하면_주인이_붙는다() {
        ledger.started(json("{\"id\":\"job-1\"}"), null, "uid-a");
        ledger.progressed("job-1", json("{\"run_id\":\"run-1\"}"), 9L);

        assertThat(rows.get(0).getUserId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("이미 주인이 있으면 안 뺏는다")
    void 주인은_안_바뀐다() {
        ledger.started(json("{\"id\":\"job-1\"}"), 7L, "uid-a");
        ledger.progressed("job-1", json("{\"run_id\":\"run-1\"}"), 9L);

        assertThat(rows.get(0).getUserId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("모르는 작업의 진행은 아무 일도 안 한다 — 없는 줄을 만들지 않는다")
    void 모르는_작업은_무시() {
        ledger.progressed("없는-작업", json("{\"run_id\":\"run-1\"}"), 7L);

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("읽을 수 없는 응답이 와도 안 죽는다 — 적는 일 때문에 만들기가 멈추면 안 된다")
    void 이상한_응답도_삼킨다() {
        ledger.started(json("이건 JSON 이 아니다"), 7L, "uid-a");
        ledger.started(new byte[0], 7L, "uid-a");
        ledger.progressed("job-1", json("{}"), 7L);

        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("옛 작품을 옮겨 담는다. 여러 번 불러도 한 번만 적힌다")
    void 옮겨_담기() {
        assertThat(ledger.moveIn(Map.of("uid-a", List.of("run-1", "run-2")))).isEqualTo(2);
        assertThat(ledger.moveIn(Map.of("uid-a", List.of("run-1", "run-2")))).isZero();
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(w -> {
            assertThat(w.getBrowserUid()).isEqualTo("uid-a");
            assertThat(w.getRunId()).isNotNull();
            assertThat(w.getUserId()).isNull();     // 계정은 브라우저 연결이 잇는다
        });
    }

    @Test
    @DisplayName("옮겨 담을 때 작업 번호 자리에 작품 번호를 쓴다 — 유일 제약을 지켜야 한다")
    void 옮긴_것도_작업_번호가_있다() {
        ledger.moveIn(Map.of("uid-a", List.of("run-1")));

        assertThat(rows.get(0).getJobId()).isEqualTo("run-1");
    }

    @Test
    @DisplayName("이미 시작해 둔 작업이 있으면 다시 안 적는다")
    void 같은_작업은_한_줄() {
        ledger.started(json("{\"id\":\"job-1\"}"), 7L, "uid-a");
        ledger.started(json("{\"id\":\"job-1\"}"), 7L, "uid-a");

        assertThat(rows).hasSize(1);
    }

    @Test
    @DisplayName("작품 번호가 아직 없는 줄은 목록에 안 올린다 — 눌러도 열 수 없다")
    void 아직_안_끝난_것은_목록에_없다() {
        WebtoonWorkRepository repo = mock(WebtoonWorkRepository.class);
        // ownedBy 는 쿼리에서 걸러 준다. 여기서는 그 약속을 문서로 굳힌다.
        when(repo.ownedBy(7L)).thenReturn(List.of(
                WebtoonWork.moved("run-1", "run-1", 7L, "uid-a", java.time.Instant.now())));
        assertThat(new WorkLedger(repo, links).runIdsOf(7L)).containsExactly("run-1");
        assertThat(Optional.of("문서용")).isPresent();
    }
}
