package com.lore.webtoon.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 생성 파이프라인(파이썬)이 <b>없는 모듈을 부르고 있지 않은지</b> 빌드에서 본다.
 *
 * <h2>왜 자바 검사가 파이썬을 보나</h2>
 *
 * 파이썬 쪽은 CI 가 따로 안 돌린다. 그런데 이 파이썬은 jar 에 실려 나가는
 * <b>제품 코드</b>라, 여기서 안 보면 아무도 안 본다.
 *
 * <h2>무엇을 막으려는 것인가</h2>
 *
 * 2026-09-12에 랜딩 웹서버(`landing/serve.py`)를 지웠는데 `s3_upload.py` 가
 * `from serve import thumbnail` 로 아직 부르고 있었다. 그 한 줄이 <b>함수
 * 안에</b> 있었고, 부르는 자리가 다 그리고 나서 올릴 것을 만드는 <b>마지막
 * 걸음</b>이었다.
 *
 * 그래서 빌드·기동·검사가 전부 통과한 채로 배포됐고, 사람이 웹툰 한 편을
 * 끝까지 그린 뒤에야(돈이 다 나간 뒤에야) 처음 터졌다 —
 * {@code ModuleNotFoundError: No module named 'serve'}. 그림이 DB 에 하나도
 * 안 적혀서 결과 화면도 비어 있었다.
 *
 * <p>검사 자체는 {@code webtoon/ai/test_imports.py} 에 있다. 모듈을 실제로
 * import 하지 않고 <b>이름만</b> 보므로, openai·Pillow 가 없는 CI 에서도 돈다.
 */
class HarnessImportsTest {

    @Test
    @DisplayName("파이프라인이 없는 모듈을 부르지 않는다")
    void 없는_모듈을_부르지_않는다() throws Exception {
        Path script = Path.of("webtoon", "ai", "test_imports.py").toAbsolutePath();
        assumeTrue(Files.isRegularFile(script), "검사 스크립트가 없습니다: " + script);

        ProcessBuilder pb = new ProcessBuilder("python3", script.toString());
        pb.redirectErrorStream(true);

        Process p;
        try {
            p = pb.start();
        } catch (java.io.IOException e) {
            // 파이썬이 없는 기계에서 빌드가 빨개지면 사람들이 이 검사를 지운다.
            assumeTrue(false, "python3 가 없어 건너뜁니다");
            return;
        }

        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS))
                .as("import 검사가 안 끝났습니다").isTrue();

        assertThat(p.exitValue())
                .as("없는 모듈을 부르는 곳이 있습니다:%n%s", out)
                .isZero();
    }
}
