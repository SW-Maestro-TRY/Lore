package com.lore.zzal.docs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 그림 주소는 <b>한 곳에서만</b> 조립한다.
 *
 * <h3>★★ 왜 구조로 막나</h3>
 * 전에는 같은 규약을 펫 상세·공유·후처리가 <b>각자 문자열로</b> 만들었다. 규약이 하나 바뀔 때
 * 한 곳만 고쳐지면 빌드도 기동도 부화도 전부 성공한 채 <b>화면에서만</b> 빈 그림이 뜬다 —
 * 실제로 심화 공유가 기본 그림 경로를 가리킨 적이 있다. 값으로 맞춰 두는 시험은
 * "새로 조립하는 곳이 하나 더 생기는 것" 을 못 막으므로, 여기서는 <b>소스를 읽어</b> 막는다.
 *
 * <h3>★ 왜 소스를 읽나</h3>
 * 서버를 띄우지 않고 파일만 읽어 몇 밀리초에 끝난다 — 그래야 매번 돌아간다
 * (같은 이유로 스키마 이름 충돌 검사도 소스를 읽는다).
 */
@DisplayName("그림 주소 — 조립하는 곳은 MotionImageKeys 하나뿐")
class ImageKeyAssemblyTest {

    /** 조립을 맡은 단 하나의 자리. */
    private static final String OWNER = "MotionImageKeys.java";

    /**
     * 문자열 안에서 <b>사용자에게 나가는</b> 펫 그림 경로를 만드는 모양.
     *
     * ★ 시트·격자 같은 중간 산출물({@code .../sheet.png})은 뺀다 — 그건 화면 계약이 아니라
     *   생성 단계끼리의 자리이고, 판 번호도 붙지 않는다.
     */
    private static final Pattern BUILDS_A_PET_PATH =
            Pattern.compile("\"[^\"]*images/zzal/pets/%d/(?:basic|motions)[^\"]*\"");

    @Test
    @DisplayName("★ zzal 본체에서 펫 그림 경로를 직접 조립하는 곳은 없다")
    void onlyOnePlaceAssemblesPetImagePaths() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(repoRoot().resolve("zzal/be/src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (f.getFileName().toString().equals(OWNER)) {
                    continue;
                }
                String src = Files.readString(f, StandardCharsets.UTF_8);
                Matcher m = BUILDS_A_PET_PATH.matcher(src);
                while (m.find()) {
                    offenders.add("%s — %s".formatted(f.getFileName(), m.group()));
                }
            }
        }
        assertThat(offenders)
                .as("펫 그림 경로는 %s 한 곳에서만 만든다 — 두 곳이 되면 한쪽만 고쳐진 채 화면에서만 틀린다", OWNER)
                .isEmpty();
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
