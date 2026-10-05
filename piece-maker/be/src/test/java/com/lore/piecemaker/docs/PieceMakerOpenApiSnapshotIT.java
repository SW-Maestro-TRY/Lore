package com.lore.piecemaker.docs;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lore.piecemaker.support.PieceMakerIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 실제 springdoc의 Piece Maker 그룹을 저장·대조한다. 공용 전체 API 스냅샷 일치 검사가 아니다.
 * 그룹은 시험에서만 등록하며 운영 설정이나 다른 서비스의 스키마를 변경하지 않는다.
 * 갱신: PIECE_MAKER_OPENAPI_UPDATE=true ./gradlew test --tests '*PieceMakerOpenApiSnapshotIT*'.
 * 타입 생성: ./piece-maker/scripts/gen-api-types.sh. 공용 명세의 기존 불일치는 이 검사로 해결되지 않는다.
 */
@PieceMakerIntegrationTest
@Import(PieceMakerOpenApiSnapshotIT.GroupConfig.class)
class PieceMakerOpenApiSnapshotIT {
    private static final String SNAPSHOT = "piece-maker/be/src/test/resources/openapi.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final boolean UPDATE = "true".equalsIgnoreCase(System.getenv("PIECE_MAKER_OPENAPI_UPDATE"));

    @Autowired private MockMvc mockMvc;
    @Value("${springdoc.api-docs.path}") private String apiDocsPath;

    @TestConfiguration(proxyBeanMethods = false)
    static class GroupConfig {
        @Bean
        GroupedOpenApi pieceMakerContractGroup() {
            return GroupedOpenApi.builder().group("piece-maker").pathsToMatch("/api/piece-maker/**").build();
        }
    }

    @Test
    void generatedPieceMakerGroupMatchesSnapshot() throws Exception {
        String generated = normalize(fetch());
        Path snapshot = root().resolve(SNAPSHOT);
        if (UPDATE) {
            Files.createDirectories(snapshot.getParent());
            Files.writeString(snapshot, generated, StandardCharsets.UTF_8);
        } else {
            assertThat(Files.exists(snapshot)).as("Piece Maker 전용 스냅샷을 먼저 생성해야 합니다: %s", SNAPSHOT).isTrue();
            assertThat(generated).as("Piece Maker 그룹 스냅샷. 변경 시 PIECE_MAKER_OPENAPI_UPDATE=true로 갱신하고 PM 타입도 생성하세요")
                    .isEqualTo(Files.readString(snapshot, StandardCharsets.UTF_8));
        }
    }

    @Test
    void groupContainsOnlyPieceMakerPathsAndPreservesAuthentication() throws Exception {
        JsonNode document = fetch();
        Set<String> paths = names(document.path("paths"));
        assertThat(paths).isNotEmpty().allMatch(path -> path.startsWith("/api/piece-maker/"));
        assertThat(paths).contains("/api/piece-maker/v1/ad-landings", "/api/piece-maker/v1/ad-landings/{id}/claim",
                "/api/piece-maker/v1/admin/ad-cohort", "/api/piece-maker/v1/hypotheses/{id}/result-view");
        assertThat(document.at("/paths/~1api~1piece-maker~1v1~1ad-landings/post").has("security")).isFalse();
        for (String path : List.of("/api/piece-maker/v1/ad-landings/{id}/claim",
                "/api/piece-maker/v1/hypotheses/{id}/result-view")) {
            assertThat(document.path("paths").path(path).path("post").path("security").get(0).has("cookieAuth")).isTrue();
        }
        assertThat(document.path("paths").path("/api/piece-maker/v1/admin/ad-cohort")
                .path("get").path("security").get(0).has("cookieAuth")).isTrue();
        // 공통 응답 봉투는 필요하지만 PM 경로 어디에서도 쓰지 않는 타 서비스 모델은 없어야 한다.
        JsonNode schemas = document.path("components").path("schemas");
        Set<String> reachable = new TreeSet<>();
        Set<String> visited = new TreeSet<>();
        collectSchemaReferences(document.path("paths"), reachable);
        while (!visited.containsAll(reachable)) {
            for (String schema : List.copyOf(reachable)) {
                if (visited.add(schema)) {
                    assertThat(schemas.has(schema)).as("참조된 스키마 %s", schema).isTrue();
                    collectSchemaReferences(schemas.path(schema), reachable);
                }
            }
        }
        assertThat(names(schemas)).containsExactlyElementsOf(reachable);
    }

    @Test
    void advertisingAttributionBelongsToPieceMakerAndDoesNotAddCommonEventFields() throws Exception {
        JsonNode schemas = fetch().path("components").path("schemas");
        assertThat(schemas.path("PieceMakerAdLandingRequest").path("properties").path("attribution").path("$ref").asText())
                .isEqualTo("#/components/schemas/PieceMakerAdAttribution");
        assertThat(names(schemas.path("PieceMakerAdAttribution").path("properties")))
                .containsExactlyInAnyOrder("utmSource", "utmMedium", "utmCampaign", "utmContent", "placement", "firstAdLandedAt");
        assertThat(names(schemas)).doesNotContain("CommonAdAttribution", "CommonEvent", "WebtoonEvent", "WebtoonJobCreateRequest");
    }

    private JsonNode fetch() throws Exception {
        var response = mockMvc.perform(get(apiDocsPath + "/piece-maker")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    /** 경로·스키마를 골라 합성하지 않는다. 환경별 servers와 순서만 정규화한다. */
    private static String normalize(JsonNode document) throws Exception {
        ObjectNode copy = (ObjectNode) document.deepCopy();
        copy.remove("servers");
        if (copy.get("tags") instanceof ArrayNode tags) {
            List<JsonNode> sorted = new ArrayList<>();
            tags.forEach(sorted::add);
            sorted.sort(Comparator.comparing(tag -> tag.path("name").asText()));
            copy.set("tags", JSON.createArrayNode().addAll(sorted));
        }
        var pretty = new DefaultPrettyPrinter()
                .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                .withArrayIndenter(new DefaultIndenter("  ", "\n"));
        return JSON.writer(pretty).writeValueAsString(sortKeys(copy)) + "\n";
    }

    private static JsonNode sortKeys(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode sorted = JSON.createObjectNode();
            names(object).forEach(name -> sorted.set(name, sortKeys(object.get(name))));
            return sorted;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode sorted = JSON.createArrayNode();
            array.forEach(value -> sorted.add(sortKeys(value)));
            return sorted;
        }
        return node;
    }

    private static Set<String> names(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static void collectSchemaReferences(JsonNode node, Set<String> references) {
        String ref = node.path("$ref").asText("");
        String prefix = "#/components/schemas/";
        if (ref.startsWith(prefix)) references.add(ref.substring(prefix.length()));
        if (node.isContainerNode()) node.forEach(child -> collectSchemaReferences(child, references));
    }

    private static Path root() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve("settings.gradle"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("저장소 루트를 찾을 수 없습니다");
        return path;
    }
}
