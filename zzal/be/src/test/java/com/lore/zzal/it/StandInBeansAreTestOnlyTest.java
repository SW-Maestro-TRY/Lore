package com.lore.zzal.it;

import com.lore.zzal.generation.MotionPostProfiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대역(stand-in) 빈은 <b>시험에만</b> 있다.
 *
 * <h3>★★ 왜 시험으로 못 박나</h3>
 * {@link ZzalItConfig#standInMotionPostProfiles} 는 "검수를 마친 동작만 굽는다" 는 규칙을
 * <b>일부러 비켜 가는</b> 물건이다. 그게 운영에 새면 검수를 거치지 않은 후처리로 구운 그림이
 * 사용자에게 그대로 나가고, 그건 <b>화면을 봐야만</b> 드러난다.
 *
 * <p>막는 겹이 둘이다 — 시험 소스 트리에만 있는 것과, 컴포넌트 스캔에서 빠지는 것.
 * 둘 다 <b>주석이 아니라 값</b>으로 확인해 둔다. 주석은 누가 지워도 아무 일도 안 일어난다.
 */
@DisplayName("대역 빈 — 시험 전용")
class StandInBeansAreTestOnlyTest {

    @Test
    @DisplayName("★ @TestConfiguration 이라 컴포넌트 스캔에 안 걸린다 — @Import 로 직접 불러야만 뜬다")
    void standInConfigIsExcludedFromComponentScan() {
        // ★ 이 표식이 없으면 @Configuration 한 장이 되어 스캔 대상이 된다.
        assertThat(ZzalItConfig.class.getAnnotation(TestConfiguration.class))
                .as("운영 컨텍스트가 대역 빈을 주울 수 있게 되면 안 된다")
                .isNotNull();
    }

    @Test
    @DisplayName("★ 운영 소스에는 대역 프로파일이 없다 — 시험 트리 밖으로 새지 않았나")
    void noStandInProfilesInProductionSources() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(repoRoot().resolve("zzal/be/src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                // 대역은 "표에 없는 동작에 확정본의 프로파일을 빌려준다" 는 모양이다.
                if (src.contains("standInMotionPostProfiles")) {
                    offenders.add(f.getFileName().toString());
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("대역은 적힌 동작에만 확정본 프로파일을 빌려주고, 나머지는 진짜 표를 그대로 지난다")
    void standInOnlyCoversTheListedMotions() {
        MotionPostProfiles profiles = new ZzalItConfig().standInMotionPostProfiles("practice");

        assertThat(profiles.forMotion("v1", "practice"))
                .as("대역 동작에만 확정본(구르기)의 프로파일을 빌려준다")
                .isEqualTo(new MotionPostProfiles().forMotion("v1", "roll"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> profiles.forMotion("v1", "없는동작"))
                .as("'표에 없으면 멈춘다' 는 규칙은 대역을 끼워도 살아 있다")
                .isInstanceOf(IllegalStateException.class);
    }

    /** 시험은 레포 어디서 돌든 루트를 찾아야 한다. */
    private Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("레포 루트를 못 찾았습니다");
        }
        return p;
    }
}
