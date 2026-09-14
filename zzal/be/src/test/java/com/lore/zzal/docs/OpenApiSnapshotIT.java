package com.lore.zzal.docs;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lore.zzal.it.ZzalIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * API 명세 스냅샷 — 서버가 응답 모양을 바꾸면 <b>여기서</b> 빨개진다.
 *
 * <h3>★ 무엇을 막나 — 아무도 안 알려 주는 변경</h3>
 * 화면이 쓰는 타입({@code zzal/fe/lib/api-schema.ts})은 <b>돌아가는 서버</b>의
 * {@code /api/v3/api-docs} 에서 자동 생성된다. 그런데 서버가 응답을 바꿔도 그 파일은 저절로
 * 바뀌지 않는다 — 화면은 옛 계약을 믿은 채로 빌드가 통과하고, 실제로 눌러 봐야 드러난다.
 *
 * <p>CI 는 서버를 띄울 수 없으므로 고리를 둘로 나눈다.
 * <ol>
 *   <li><b>여기</b> — 서버 명세를 레포에 스냅샷({@code common/docs/openapi.json})으로 박아 둔다</li>
 *   <li><b>프론트 CI</b> — 그 스냅샷으로 타입을 다시 만들어 기존 것과 비교한다</li>
 * </ol>
 * 서버를 바꾸고 스냅샷을 안 갱신하면 <b>이 시험</b>이, 스냅샷은 갱신했는데 타입을 안 만들면
 * <b>프론트 CI</b> 가 빨개진다. 어느 쪽도 조용히 넘어가지 않는다.
 *
 * <h3>★★ 이 시험은 zzal 만이 아니라 API 전체를 본다</h3>
 * 스냅샷은 {@code /api/**} 전부(webtoon · trailer · common 포함)를 덮는다. 그래서 <b>다른 도메인이
 * 자기 API 를 바꿔도 여기가 빨개진다.</b> 그건 고장이 아니라 이 시험이 하는 일이다 — 바꾼 사람이
 * 스냅샷을 갱신하면 된다(아래 갱신법).
 *
 * <p>자리를 zzal 아래 둔 이유는 하나다 — 시험 소스 폴더가 도메인별로만 열려 있고
 * ({@code build.gradle} 의 {@code sourceSets.test.srcDirs}), {@code apps/api} 에는 시험 트리가 없다.
 * 공용 자리가 생기면 그리로 옮기는 편이 맞다.
 *
 * <h3>★ 갱신법</h3>
 * <pre>
 * OPENAPI_UPDATE=true ./gradlew test --tests '*OpenApiSnapshot*'
 * </pre>
 * ★ {@code -Dopenapi.update=true} 는 <b>안 통한다</b> — Gradle 의 {@code -D} 는 데몬 JVM 에만 붙고
 * 시험 워커 JVM 으로 전달되지 않는다({@code build.gradle} 에 {@code systemProperty} 를 더해야 하는데
 * 그 파일은 팀 공용이라 건드리지 않았다). 환경변수는 워커까지 그대로 내려간다(실측 확인).
 * 시스템 프로퍼티도 같이 읽어 두기는 한다 — 나중에 build.gradle 이 전달하게 되면 그때부터 통한다.
 *
 * <h3>★ 정규화 — 시험과 갱신이 <b>같은 함수</b>를 쓴다</h3>
 * {@link #normalize(JsonNode)} 하나뿐이다. 비교하는 쪽과 쓰는 쪽이 다른 규칙을 쓰면 영원히 안 맞는다.
 * <ul>
 *   <li>객체의 키를 재귀적으로 정렬한다. <b>배열 순서는 그대로 둔다</b> —
 *       {@code required} · {@code enum} · {@code parameters} 는 순서가 뜻을 갖는다</li>
 *   <li>2칸 들여쓰기 · 줄바꿈 {@code \n} · 파일 끝 줄바꿈 하나</li>
 *   <li>한글은 이스케이프하지 않는다(UTF-8 그대로)</li>
 * </ul>
 *
 * <h3>★★ 환경마다 달라지는 값은 지운다</h3>
 * 안 지우면 <b>기계마다 다르게</b> 빨개져서, 아무도 이 시험을 믿지 않게 된다.
 * <ul>
 *   <li><b>{@code servers} — 지운다.</b> springdoc 이 요청 URL 로 채우는 자리라 포트·호스트가
 *       그대로 들어간다(로컬 {@code http://localhost:8090}, 시험 {@code http://localhost}, 운영은 도메인).
 *       타입 생성({@code openapi-typescript})은 이 값을 안 쓴다</li>
 *   <li><b>{@code tags} 배열 — 이름순으로 정렬한다.</b> springdoc 은 컨트롤러를 훑은 순서대로 태그를
 *       모으는데, 그 순서는 컴포넌트 스캔 순서(= 파일시스템 나열 순서)라 기계·OS 가 다르면 달라질 수 있다.
 *       유일하게 순서에 뜻이 없는 배열이라 여기만 예외로 정렬한다</li>
 * </ul>
 * 그 밖에 실행마다 달라지는 값은 없다 — 확인했다. 문서에 보이는 시각·UUID
 * ({@code 2026-09-05T10:00:00Z} · {@code a1b2c3d4-…})는 전부 소스에 박힌 {@code @Schema(example)} 이다.
 *
 * <h3>★ 어떤 스위치로 뽑힌 명세인가</h3>
 * 조건부 컨트롤러가 여럿이라(개발 시계 · 관리자 · 후기 · 맥미니 문) 스위치가 다르면 경로 수가 달라진다.
 * 이 시험은 {@link ZzalIntegrationTest} 가 정한 스위치를 그대로 쓴다 —
 * {@code dev-tools=true}, 나머지 셋은 꺼짐. <b>프론트 타입을 뽑아 온 개발 서버(8090)와 같은 구성</b>이라
 * 양쪽 계약이 어긋나지 않는다.
 *
 * <h3>★ DB 가 없으면 건너뛴다 — 그래서 이 시험만으로 안심하면 안 된다</h3>
 * 스프링 컨텍스트를 띄워야 명세가 나오고, 컨텍스트는 진짜 Postgres 가 있어야 뜬다
 * (기동할 때 DB 를 만지는 자리가 넷 있다 — 스키마 패치 둘 · 기본 캐릭터 심기 · 멈춘 작업 정리).
 * 그래서 {@code LORE_TEST_DB_*} 가 없는 기계에서는 <b>조용히 건너뛴다.</b>
 */
@ZzalIntegrationTest
@DisplayName("API 명세 — common/docs/openapi.json 스냅샷과 같다")
class OpenApiSnapshotIT {

    /** 레포 안에서의 스냅샷 자리. 팀 공용 규약 폴더({@code common/docs})에 둔다. */
    private static final String SNAPSHOT = "common/docs/openapi.json";

    /** 켜면 파일을 다시 쓰고 통과시킨다. ★ 기본은 꺼짐 — 켜진 채로 두면 이 시험은 아무것도 안 잡는다. */
    private static final boolean UPDATE =
            "true".equalsIgnoreCase(System.getenv("OPENAPI_UPDATE"))
                    || "true".equalsIgnoreCase(System.getProperty("openapi.update"));

    private static final String HOW_TO_UPDATE = """
            API 명세가 %s 과 다릅니다.
            서버를 바꿨으면 스냅샷을 갱신하세요:
              OPENAPI_UPDATE=true ./gradlew test --tests '*OpenApiSnapshot*'
            갱신 뒤에는 프론트 타입도 다시 만들어야 합니다(zzal/fe/lib/api-schema.ts).
            """.formatted(SNAPSHOT);

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    /** application.yml 의 {@code springdoc.api-docs.path}. 주소가 바뀌면 여기도 같이 바뀐다. */
    @Value("${springdoc.api-docs.path}")
    private String apiDocsPath;

    @Test
    @DisplayName("★ 돌아가는 서버의 /api/v3/api-docs 가 스냅샷과 한 글자도 다르지 않다")
    void theLiveSpecMatchesTheSnapshot() throws Exception {
        String live = normalize(fetchApiDocs());
        Path snapshot = repoRoot().resolve(SNAPSHOT);

        if (UPDATE) {
            Files.createDirectories(snapshot.getParent());
            Files.writeString(snapshot, live, StandardCharsets.UTF_8);
            System.out.println("스냅샷을 갱신했습니다 — " + SNAPSHOT + " (" + live.length() + "바이트 기준 문자수)");
            return;
        }

        if (!Files.exists(snapshot)) {
            fail(HOW_TO_UPDATE + "\n(스냅샷 파일이 아직 없습니다: " + snapshot + ")");
        }

        String stored = Files.readString(snapshot, StandardCharsets.UTF_8);
        if (!stored.equals(live)) {
            fail(HOW_TO_UPDATE + "\n" + summarize(JSON.readTree(stored), JSON.readTree(live)));
        }
    }

    // ── 명세 받아오기 ──────────────────────────────────────────────────────

    private JsonNode fetchApiDocs() throws Exception {
        var response = mockMvc.perform(get(apiDocsPath)).andReturn().getResponse();
        if (response.getStatus() != 200) {
            throw new IllegalStateException(
                    "%s 가 %d 을 냈습니다. springdoc 이 꺼져 있거나(SPRINGDOC_ENABLED) 주소가 바뀌었습니다."
                            .formatted(apiDocsPath, response.getStatus()));
        }
        return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    // ── 정규화 (시험과 갱신이 같이 쓰는 단 하나의 함수) ────────────────────

    /**
     * 환경에 따라 달라지는 값을 지우고, 키를 정렬해 2칸 들여쓴 문자열로 만든다.
     *
     * <p>★ 배열은 건드리지 않는다 — 위쪽 {@code tags} 하나만 예외이고 이유는 클래스 주석에 적었다.
     */
    static String normalize(JsonNode document) throws IOException {
        ObjectNode root = (ObjectNode) document.deepCopy();

        // ★ 환경 의존 값 — 요청 URL 이 그대로 들어간다(localhost:8090 / localhost / 운영 도메인).
        root.remove("servers");

        // ★ 순서에 뜻이 없는 유일한 배열. 스캔 순서에 딸려 오므로 이름으로 고정한다.
        JsonNode tags = root.get("tags");
        if (tags instanceof ArrayNode array) {
            List<JsonNode> sorted = new ArrayList<>();
            array.forEach(sorted::add);
            sorted.sort(Comparator.comparing(t -> t.path("name").asText("")));
            root.set("tags", JSON.createArrayNode().addAll(sorted));
        }

        return JSON.writer(twoSpacePrinter()).writeValueAsString(sortKeys(root)) + "\n";
    }

    /** 객체의 키만 재귀적으로 정렬한다. 배열은 순서를 그대로 둔다. */
    private static JsonNode sortKeys(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Set<String> names = new TreeSet<>();
            object.fieldNames().forEachRemaining(names::add);
            ObjectNode sorted = JSON.createObjectNode();
            names.forEach(name -> sorted.set(name, sortKeys(object.get(name))));
            return sorted;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode copy = JSON.createArrayNode();
            array.forEach(item -> copy.add(sortKeys(item)));
            return copy;
        }
        return node;
    }

    /** 2칸 들여쓰기 · 줄바꿈 {@code \n} · {@code "키": 값} (Jackson 기본은 {@code "키" : 값}). */
    private static DefaultPrettyPrinter twoSpacePrinter() {
        return new TwoSpacePrinter();
    }

    private static final class TwoSpacePrinter extends DefaultPrettyPrinter {

        private static final DefaultIndenter INDENTER = new DefaultIndenter("  ", "\n");

        TwoSpacePrinter() {
            indentObjectsWith(INDENTER);
            indentArraysWith(INDENTER);
        }

        TwoSpacePrinter(TwoSpacePrinter base) {
            super(base);
            indentObjectsWith(INDENTER);
            indentArraysWith(INDENTER);
        }

        @Override
        public DefaultPrettyPrinter createInstance() {
            return new TwoSpacePrinter(this);
        }

        @Override
        public void writeObjectFieldValueSeparator(JsonGenerator generator) throws IOException {
            generator.writeRaw(": ");
        }
    }

    // ── 무엇이 달라졌나 (전체 diff 를 쏟지 않는다 — 사람이 읽을 것만) ──────

    private static String summarize(JsonNode stored, JsonNode live) {
        StringBuilder out = new StringBuilder("무엇이 달라졌나\n");
        compareMap(out, "경로", stored.path("paths"), live.path("paths"));
        compareMap(out, "스키마", stored.path("components").path("schemas"),
                live.path("components").path("schemas"));

        List<String> others = new ArrayList<>();
        for (String key : new LinkedHashSet<>(List.of("openapi", "info", "tags"))) {
            if (!stored.path(key).equals(live.path(key))) {
                others.add(key);
            }
        }
        if (!others.isEmpty()) {
            out.append("  그 밖에 바뀐 항목: ").append(String.join(", ", others)).append('\n');
        }
        return out.toString();
    }

    private static void compareMap(StringBuilder out, String what, JsonNode stored, JsonNode live) {
        Set<String> before = new TreeSet<>();
        stored.fieldNames().forEachRemaining(before::add);
        Set<String> after = new TreeSet<>();
        live.fieldNames().forEachRemaining(after::add);

        List<String> gone = before.stream().filter(k -> !after.contains(k)).toList();
        List<String> born = after.stream().filter(k -> !before.contains(k)).toList();
        List<String> changed = before.stream()
                .filter(after::contains)
                .filter(k -> !stored.get(k).equals(live.get(k)))
                .toList();

        line(out, "사라진 " + what, gone);
        line(out, "생긴 " + what, born);
        line(out, "내용이 바뀐 " + what, changed);
    }

    /** 목록이 길면 앞 15개만 — 전체 diff 를 쏟으면 아무도 안 읽는다. */
    private static void line(StringBuilder out, String label, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        out.append("  ").append(label).append('(').append(items.size()).append("): ")
                .append(String.join(", ", items.subList(0, Math.min(15, items.size()))));
        if (items.size() > 15) {
            out.append(" 외 ").append(items.size() - 15).append("개");
        }
        out.append('\n');
    }

    // ── 자리 찾기 ─────────────────────────────────────────────────────────

    /** 시험은 레포 어디서 돌든 루트를 찾아야 한다. */
    private static Path repoRoot() {
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
