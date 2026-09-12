package com.lore.webtoon.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 스프링이 파이썬을 <b>진짜로</b> 부를 수 있나.
 *
 * <h2>가짜로 대신하지 않는다</h2>
 *
 * 여기서 확인하려는 것이 "우리 코드가 뭘 부르려 하는가" 가 아니라 "그게 실제로
 * 도는가" 다. 파이썬이 없거나, 자리를 못 찾거나, 필요한 꾸러미가 안 깔려 있으면
 * 배포에서 만들기가 통째로 죽는데 그건 가짜로는 절대 안 잡힌다.
 *
 * <b>돈은 안 나간다.</b> {@code --plan} 은 어느 단계에 어느 모델을 쓸지 적어
 * 보여줄 뿐 모델을 부르지 않는다.
 *
 * 하네스가 없는 곳(CI 등)에서는 통째로 건너뛴다 — 없는 것을 없다고 실패시키면
 * 남의 빌드가 우리 때문에 빨개진다.
 */
class HarnessProcessTest {

    private static final Path HARNESS =
            Path.of(System.getProperty("user.dir"), "webtoon", "ai", "new_harness");

    static boolean 하네스가_있다() {
        return Files.isRegularFile(HARNESS.resolve("run.py"));
    }

    private HarnessProcess process(int timeoutSeconds) {
        return new HarnessProcess("python3", HARNESS.toString(), timeoutSeconds, "", null);
    }

    @Test
    @DisplayName("하네스가 어디 있는지 안다")
    void 자리를_안다() {
        HarnessProcess harness = process(60);
        assertThat(harness.ready()).isEqualTo(하네스가_있다());
        assertThat(harness.dir()).isAbsolute();
    }

    @Test
    @DisplayName("없는 자리를 가리키면 준비 안 된 것으로 답한다 — 부르고 나서 죽지 않는다")
    void 없으면_없다고_한다() {
        assertThat(new HarnessProcess("python3", "/없는/자리", 60, "", null).ready()).isFalse();
    }

    @Test
    @EnabledIf("하네스가_있다")
    @DisplayName("파이썬을 실제로 돌리고 나오는 줄을 읽는다 (--plan, 돈 안 나감)")
    void 실제로_돈다() throws Exception {
        List<String> lines = new ArrayList<>();
        int code = process(120).run(List.of("--plan"), Map.of(), lines::add);

        assertThat(code).isZero();
        assertThat(lines).isNotEmpty();
        // 어느 단계에 어느 모델을 쓸지 적어 준다 — 그 표가 나오면 제대로 돈 것이다.
        assertThat(String.join("\n", lines)).contains("STORY");
    }

    @Test
    @EnabledIf("하네스가_있다")
    @DisplayName("넘긴 환경변수가 파이썬에 닿는다 — 그림체가 이 길로 간다")
    void 환경변수가_닿는다() throws Exception {
        List<String> lines = new ArrayList<>();
        process(120).run(List.of("--plan"), Map.of("NH_STYLE", "romance_fantasy"), lines::add);

        assertThat(lines).isNotEmpty();
    }

    @Test
    @DisplayName("너무 오래 걸리면 죽이고 알린다 — 안 그러면 만들기 하나가 서버를 붙든다")
    void 시간이_지나면_죽인다(@TempDir Path fake) throws Exception {
        /* 진짜 하네스로는 못 잰다 — `--plan` 은 1 초도 안 걸려서 시간 초과가
           안 걸리고, 오래 걸리는 단계는 돈이 나간다. 그래서 **안 끝나는
           run.py 를 하나 만들어** 그 자리에 둔다. 죽이는 길이 실제로 도는지가
           여기서 보려는 전부다. */
        Files.writeString(fake.resolve("run.py"), "import time\ntime.sleep(600)\n");

        HarnessProcess slow = new HarnessProcess("python3", fake.toString(), 1, "", null);
        assertThatThrownBy(() -> slow.run(List.of(), Map.of(), line -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("오래");
    }
}
