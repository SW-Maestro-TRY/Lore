package com.lore.webtoon.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 작품 번호를 <b>자바가 짓는다.</b>
 *
 * <h2>왜 옮겼나</h2>
 *
 * 예전에는 하네스가 짓고, 자바가 <b>가장 최근에 생긴 폴더</b>로 그걸 되찾았다.
 * 한 줄로 세워 돌 때만 우연히 맞는 방법이다 — 두 편을 같이 돌리는 순간
 * <b>두 사람의 작품이 뒤바뀐다.</b> 그리고 그 뒤로 이어지는 것이 전부
 * 어긋난다: 소유권 · 크레딧 · 공개 여부 · 비용 귀속.
 *
 * 코드 주석이 이미 그렇게 경고하고 있었다 — "여럿을 같이 돌리기 시작하면
 * 이 방법부터 못 쓴다". 그래서 동시 처리로 가기 전에 이것부터 옮겼다.
 */
class RunIdTest {

    /** 번호 짓는 자리만 꺼내 본다 — 스프링을 띄울 일이 아니다. */
    private Callable<String> newRunId() {
        JobRunner runner = mock(JobRunner.class);
        Method m;
        try {
            m = JobRunner.class.getDeclaredMethod("newRunId");
            m.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("newRunId 가 없습니다 — 이름이 바뀌었나요?", e);
        }
        return () -> (String) m.invoke(runner);
    }

    @Test
    @DisplayName("하네스가 짓던 것과 같은 모양이다 — 시각 + 여섯 자리")
    void 같은_모양이다() throws Exception {
        String id = newRunId().call();
        /* story.new_run_id() 와 같은 모양이어야 한다. 모양이 갈리면 폴더를
           시각순으로 늘어놓을 때 옛 작품과 새 작품이 섞여 보인다. */
        assertThat(id).matches("\\d{8}T\\d{6}-[0-9a-f]{6}");
    }

    @Test
    @DisplayName("같은 초에 여러 번 지어도 안 겹친다")
    void 안_겹친다() throws Exception {
        Callable<String> make = newRunId();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            assertThat(seen.add(make.call())).as("%d번째가 겹쳤습니다", i).isTrue();
        }
    }

    @Test
    @DisplayName("여러 갈래가 같이 지어도 안 겹친다 — 동시 처리의 전제다")
    void 동시에_지어도_안_겹친다() throws Exception {
        Callable<String> make = newRunId();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> got = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                got.add(pool.submit(make));
            }
            Set<String> seen = new HashSet<>();
            for (Future<String> f : got) {
                assertThat(seen.add(f.get())).as("동시에 지은 것이 겹쳤습니다").isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("「가장 최근 폴더」로 짐작하는 코드가 남아 있지 않다")
    void 짐작이_안_남아_있다() {
        /* 이 검사가 지키는 것은 <b>다시 안 돌아가는가</b> 다. 폴더 시각으로
           작품을 고르는 방법은 동시 처리에서 조용히 틀린다 — 오류도 안 나고
           남의 작품이 내 것으로 적힐 뿐이다. */
        for (String gone : List.of("latestRun", "latestMeta", "newestRunWith")) {
            boolean still = java.util.Arrays.stream(JobRunner.class.getDeclaredMethods())
                    .anyMatch(m -> m.getName().equals(gone));
            assertThat(still).as("%s 가 되살아났습니다 — 동시 처리에서 작품이 뒤바뀝니다", gone)
                    .isFalse();
        }
    }
}
