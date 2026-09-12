package com.lore.zzal.docs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API 문서의 이름 충돌을 막는다.
 *
 * <h3>★ 무엇이 잘못됐었나</h3>
 * OpenAPI 문서는 스키마를 <b>클래스의 짧은 이름</b>으로 부른다. 서로 다른 곳의 두 record 가
 * 같은 이름이면 <b>한쪽이 다른 쪽을 덮어쓴다.</b> 컴파일도 되고 서버도 뜨고 스웨거도 열리는데,
 * 문서에 적힌 모양만 남의 것이 되어 있다.
 *
 * <p>실제로 두 건이 이렇게 망가져 있었다.
 * <ul>
 *   <li>채팅의 대화 한 건이 사용량 기록의 비용 항목으로 덮였다 — 프론트가 받는 타입에
 *       {@code slot}·{@code line} 대신 {@code costUsd}·{@code model} 이 들어갔다</li>
 *   <li>좌우 맞히기의 <b>응답</b>이 <b>요청</b>으로 덮여, 승패·정답·맞힌 횟수가 통째로 사라지고
 *       {@code pick} 하나만 남았다</li>
 * </ul>
 *
 * <p>둘 다 <b>화면을 실제로 만들어 볼 때까지</b> 드러나지 않는다. 서버는 옳은 값을 보내는데
 * 문서만 틀려서, 그 문서로 만든 타입을 믿은 쪽이 뒤늦게 헤맨다.
 *
 * <h3>★ 왜 소스를 읽어서 검사하나</h3>
 * 문서를 실제로 뽑아 보려면 서버를 띄워야 하고, 그러려면 DB 가 필요하다. 이 검사는
 * 그런 것 없이 파일만 읽어 몇 밀리초에 끝난다 — 그래야 매번 돌아간다.
 *
 * <h3>★ 우리 것만 검사한다</h3>
 * 다른 도메인끼리의 충돌은 그쪽에서 정할 일이다. 여기서 막는 것은
 * <b>zzal 의 이름이 남과 겹치는 경우</b>뿐이고, 그때 고칠 곳도 우리 쪽이다 —
 * {@code @Schema(name = "...")} 로 이름을 직접 준다.
 */
@DisplayName("API 문서 — 스키마 이름이 겹치지 않는다")
class SchemaNameCollisionTest {

    /** {@code public record Xxx(} 또는 {@code public record Xxx {} */
    private static final Pattern RECORD = Pattern.compile("public record (\\w+)\\s*[({]");

    /** 바로 앞줄들에 붙은 {@code @Schema(name = "Xxx")}. 붙어 있으면 그 이름이 문서에 쓰인다. */
    private static final Pattern SCHEMA_NAME = Pattern.compile("@Schema\\s*\\(\\s*name\\s*=\\s*\"(\\w+)\"");

    private record Decl(String schemaName, String file) {
    }

    @Test
    @DisplayName("★ zzal 의 스키마 이름이 다른 도메인과 겹치지 않는다")
    void zzalNamesDoNotCollide() throws IOException {
        Path root = repoRoot();
        List<Decl> zzal = dtoDeclarations(root.resolve("zzal/be/src/main"));
        List<Decl> others = new ArrayList<>();
        others.addAll(allDeclarations(root.resolve("webtoon/be/src/main")));
        others.addAll(allDeclarations(root.resolve("common/be/src/main")));

        Map<String, String> theirs = new LinkedHashMap<>();
        others.forEach(d -> theirs.putIfAbsent(d.schemaName(), d.file()));

        List<String> collisions = zzal.stream()
                .filter(d -> theirs.containsKey(d.schemaName()))
                .map(d -> "%s — %s ↔ %s".formatted(d.schemaName(), d.file(), theirs.get(d.schemaName())))
                .toList();

        assertThat(collisions)
                .as("""
                        이름이 겹칩니다. 문서에서 한쪽이 다른 쪽을 덮어써, 프론트가 받는 타입이 조용히 틀어집니다.
                        우리 쪽 record 에 @Schema(name = "...") 로 겹치지 않는 이름을 주세요.""")
                .isEmpty();
    }

    @Test
    @DisplayName("zzal 안에서도 같은 이름을 두 번 쓰지 않는다 — 요청과 응답이 겹쳤던 자리다")
    void zzalNamesAreUniqueAmongThemselves() throws IOException {
        List<Decl> zzal = dtoDeclarations(repoRoot().resolve("zzal/be/src/main"));

        Map<String, List<String>> byName = new LinkedHashMap<>();
        zzal.forEach(d -> byName.computeIfAbsent(d.schemaName(), k -> new ArrayList<>()).add(d.file()));

        List<String> dups = byName.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> e.getKey() + " — " + String.join(" ↔ ", e.getValue()))
                .toList();

        assertThat(dups).isEmpty();
    }

    /**
     * 우리 쪽에서 <b>문서에 실리는</b> record — {@code dto} 패키지 안의 것만 본다.
     * 그 밖의 record 는 안에서만 쓰고 사라지므로 이름이 겹쳐도 문서에 영향이 없다.
     */
    private List<Decl> dtoDeclarations(Path dir) throws IOException {
        return declarations(dir, p -> p.toString().contains("/dto/"));
    }

    /**
     * 남의 도메인은 <b>전부</b> 본다.
     *
     * ★ 실제로 우리를 덮은 것은 {@code dto} 패키지가 아니라 서비스 클래스 안의 record 였다.
     *   "DTO 폴더에 있는 것만 문서에 실린다" 는 우리 쪽 규칙일 뿐, 남에게는 해당하지 않는다.
     */
    private List<Decl> allDeclarations(Path dir) throws IOException {
        return declarations(dir, p -> true);
    }

    private List<Decl> declarations(Path dir, java.util.function.Predicate<Path> keep) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(keep)
                    .flatMap(this::declarationsIn)
                    .toList();
        }
    }

    private Stream<Decl> declarationsIn(Path file) {
        String src;
        try {
            src = Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException(file.toString(), e);
        }
        String name = file.getFileName().toString();
        List<Decl> found = new ArrayList<>();
        Matcher m = RECORD.matcher(src);
        while (m.find()) {
            found.add(new Decl(schemaNameOf(src, m.start(), m.group(1)), name));
        }
        return found.stream();
    }

    /**
     * 이 선언에 붙은 {@code @Schema(name = ...)} 을 찾는다.
     *
     * ★ 범위를 <b>직전 빈 줄까지</b>로 자른다. 글자 수로 자르면 바로 위 record 에 붙은 이름을
     *   이 record 것으로 읽어, 있지도 않은 충돌을 만든다(실제로 두 건이 그렇게 잡혔다).
     *   애노테이션과 선언 사이에는 빈 줄이 없으므로 이 경계가 정확하다.
     */
    private String schemaNameOf(String src, int declStart, String simpleName) {
        int blank = src.lastIndexOf("\n\n", declStart);
        int from = blank < 0 ? 0 : blank;
        Matcher m = SCHEMA_NAME.matcher(src.substring(from, declStart));
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last != null ? last : simpleName;
    }

    /** 테스트는 레포 어디서 돌든 루트를 찾아야 한다. */
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
