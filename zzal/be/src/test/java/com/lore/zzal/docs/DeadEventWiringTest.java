package com.lore.zzal.docs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 죽은 배선을 남기지 않는다 — 리스너가 받는 이벤트에는 <b>발행처가 있어야 한다</b>(P-3).
 *
 * <h3>★★ 무엇이 잘못됐었나</h3>
 * {@code MotionStartRequested} 는 레코드 선언과 리스너 둘뿐이고 <b>발행하는 코드가 한 줄도 없었다.</b>
 * 그래도 컴파일되고 빈도 올라오고 컨텍스트 시험까지 통과한다 — "리스너가 빈으로 떴다" 는
 * "그 길이 실제로 돈다" 가 아니기 때문이다. 읽는 사람은 그 경로가 살아 있다고 믿고,
 * 그 믿음 위에서 다음 결정을 한다.
 *
 * <h3>★ 왜 소스를 읽어서 검사하나</h3>
 * 발행은 {@code events.publishEvent(new Xxx(...))} 라는 <b>호출</b>이라 반사로는 안 보인다.
 * 실제로 발행이 일어나는지는 통합 하네스가 봐야 하는데 그건 DB 가 있어야 돌고,
 * 이 검사는 "아예 아무도 안 부른다" 는 더 앞쪽 실수를 파일만 읽어 몇 밀리초에 잡는다.
 */
@DisplayName("이벤트 배선 — 받는 곳이 있으면 보내는 곳도 있다")
class DeadEventWiringTest {

    /** {@code public void onXxx(SomeEvent event)} 의 인자 타입 — 바로 위에 리스너 어노테이션이 붙은 것만. */
    private static final Pattern LISTENER = Pattern.compile(
            "@(?:Transactional)?EventListener[^\\n]*\\n\\s*public\\s+\\w+\\s+\\w+\\s*\\(\\s*(\\w+)\\s+\\w+\\s*\\)");

    /** 스프링이 스스로 보내는 것들 — 우리 코드에 발행처가 없는 것이 정상이다. */
    private static final List<String> FRAMEWORK_EVENTS = List.of("ApplicationReadyEvent");

    @Test
    @DisplayName("★★ zzal 의 리스너가 받는 이벤트는 전부 발행처가 있다")
    void everyListenedEventHasAPublisher() throws IOException {
        Path main = repoRoot().resolve("zzal/be/src/main");
        String allSource = readAll(main);

        List<String> orphans = new ArrayList<>();
        for (String event : listenedEvents(main)) {
            if (FRAMEWORK_EVENTS.contains(event)) {
                continue;
            }
            // 발행은 늘 새로 만들어 보낸다 — publishEvent(new Xxx( 또는 new Xxx( 뒤의 publishEvent
            if (!Pattern.compile("publishEvent\\s*\\(\\s*new\\s+" + Pattern.quote(event) + "\\s*\\(").matcher(allSource).find()) {
                orphans.add(event);
            }
        }

        assertThat(orphans)
                .as("""
                        아무도 발행하지 않는 이벤트를 받는 리스너가 있습니다 — 죽은 배선입니다.
                        발행처를 잇거나, 그 길이 이미 다른 코드로 대체됐다면 리스너와 레코드를 지우세요.""")
                .isEmpty();
    }

    @Test
    @DisplayName("검사가 실제로 리스너를 찾고 있다 — 빈 목록을 훑고 통과하지 않게")
    void theScanActuallyFindsListeners() throws IOException {
        assertThat(listenedEvents(repoRoot().resolve("zzal/be/src/main")))
                .contains("PieceCompleted", "PetHatchRequested");
    }

    private List<String> listenedEvents(Path dir) throws IOException {
        List<String> events = new ArrayList<>();
        Matcher m = LISTENER.matcher(readAll(dir));
        while (m.find()) {
            events.add(m.group(1));
        }
        return events;
    }

    private String readAll(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("소스 폴더를 못 찾았습니다: " + dir);
        }
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(p -> p.toString().endsWith(".java")).sorted().forEach(p -> {
                try {
                    sb.append(Files.readString(p)).append('\n');
                } catch (IOException e) {
                    throw new IllegalStateException(p.toString(), e);
                }
            });
        }
        return sb.toString();
    }

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
