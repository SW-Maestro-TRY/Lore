package com.lore.webtoon.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주인공 바꾸기 · 이름 바꾸기가 다음 단계가 읽는 파일을 같이 맞추는가(Tosisusae 피드백).
 * 장면 나누기·장 그림은 주인공을 input.json·persona.json·sheet.png 에서, 조연을 cast.json·sheets/ 에서 읽는다.
 */
class PersonFilesTest {

    @TempDir
    Path dir;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void 작품_폴더() throws IOException {
        Path photo = dir.resolve("photo1.png");
        Files.writeString(photo, "photo");
        put("input.json", Map.of("name", "한결", "description", "열여덟 살, 동네 빵집 막내아들.",
                "photos", List.of(photo.toString()), "fields", Map.of(), "card", Map.of(), "photo_note", "",
                "story", "한결은 하윤에게 빵을 건넨다.", "genre", "로맨스"));
        put("persona.json", Map.of("name", "한결", "look", "갈색 곱슬머리", "personality", "잘 웃는다"));
        put("cast.json", List.of(
                Map.of("name", "하윤", "role", "같은 반 여자아이", "look", "검은 단발", "tie", "한결과 같은 반", "line", "필요 없어."),
                Map.of("name", "한결 엄마", "role", "빵집 사장님", "look", "갈색 머리")));
        put("directions.json", List.of(Map.of("n", 1, "title", "남은 빵", "body", "한결은 웃었다. 하윤이 돌아선다.")));
        put("scenes.json", Map.of("scenes", List.of(Map.of("n", 1, "what", "한결이 하윤을 본다.")),
                "cast", List.of(Map.of("name", "하윤", "appearance", "검은 단발"))));
        Files.writeString(dir.resolve("sheet.png"), "hero-sheet");
        Files.writeString(dir.resolve("sheet_spec.json"), "{\"name\":\"한결\"}");
        Files.writeString(dir.resolve("sheet.v1.png"), "hero-old");
        Files.createDirectories(dir.resolve("sheets"));
        Files.writeString(dir.resolve("sheets/한결 엄마.png"), "mom-sheet");
    }

    @Test
    @DisplayName("이름 바꾸기: 이야기·장면·인물 글과 이름이 든 조연 시트까지 새 이름으로")
    void 이름() throws IOException {
        PersonFiles.Renamed r = PersonFiles.rename(dir, mapper, List.of("한결"), "도하");

        assertThat(r.story()).isTrue();
        assertThat(r.cast()).containsEntry("한결 엄마", "도하 엄마");
        assertThat(list("directions.json").get(0).get("body")).isEqualTo("도하는 웃었다. 하윤이 돌아선다.");
        assertThat(String.valueOf(map("scenes.json"))).contains("도하가 하윤을 본다.");
        assertThat(list("cast.json").get(0).get("tie")).isEqualTo("도하와 같은 반");
        assertThat(map("input.json").get("story")).isEqualTo("도하는 하윤에게 빵을 건넨다.");
        assertThat(dir.resolve("sheets/도하 엄마.png")).exists();
        assertThat(dir.resolve("sheets/한결 엄마.png")).doesNotExist();
        /* 사진 경로는 글이 아니다 */
        assertThat(String.valueOf(map("input.json").get("photos"))).contains("photo1.png");
    }

    @Test
    @DisplayName("주인공 바꾸기(새 주인공 시트 없음): 카드·입력·시트를 맞바꾸고 시트를 새로 그리라고 한다")
    void 바꾸기_시트_없음() throws IOException {
        boolean needs = PersonFiles.swapLead(dir, mapper, 0);

        assertThat(needs).isTrue();
        assertThat(map("persona.json").get("name")).isEqualTo("하윤");
        assertThat(map("persona.json").get("line")).isEqualTo("필요 없어.");
        assertThat(map("persona.json")).doesNotContainKey("personality");   // 없는 성격을 붙이지 않는다
        assertThat(list("cast.json").get(0).get("name")).isEqualTo("한결");
        assertThat(list("cast.json").get(0).get("personality")).isEqualTo("잘 웃는다");
        Map<String, Object> input = map("input.json");
        assertThat(input.get("name")).isEqualTo("하윤");
        assertThat(input.get("photos")).isEqualTo(List.of());
        assertThat(String.valueOf(input.get("description"))).contains("역할: 같은 반 여자아이").contains("생김새: 검은 단발");
        assertThat(String.valueOf(input.get("swapped_heroes"))).contains("열여덟 살");
        /* 옛 주인공 시트는 그 사람의 조연 시트로, 옛 판은 맡겨 둔다 */
        assertThat(Files.readString(dir.resolve("sheets/한결.png"))).isEqualTo("hero-sheet");
        assertThat(dir.resolve("sheet.png")).doesNotExist();
        assertThat(dir.resolve("sheet.v1.png")).doesNotExist();
        assertThat(dir.resolve("hero_sheets/한결/sheet.v1.png")).exists();
        /* 장 그림이 옛 주인공 생김새를 읽을 수 있게 장면 등장인물에 더한다 */
        assertThat(String.valueOf(map("scenes.json").get("cast"))).contains("한결").contains("갈색 곱슬머리");
    }

    @Test
    @DisplayName("되돌리기: 다시 바꾸면 처음 적은 설명·사진·시트·옛 판이 돌아온다")
    void 되돌리기() throws IOException {
        PersonFiles.swapLead(dir, mapper, 0);
        Files.writeString(dir.resolve("sheet.png"), "hayun-sheet");          // 새로 그린 하윤 주인공 시트
        boolean needs = PersonFiles.swapLead(dir, mapper, 0);

        assertThat(needs).isFalse();
        Map<String, Object> input = map("input.json");
        assertThat(input.get("name")).isEqualTo("한결");
        assertThat(input.get("description")).isEqualTo("열여덟 살, 동네 빵집 막내아들.");
        assertThat(String.valueOf(input.get("photos"))).contains("photo1.png");
        assertThat(Files.readString(dir.resolve("sheet.png"))).isEqualTo("hero-sheet");
        assertThat(Files.readString(dir.resolve("sheets/하윤.png"))).isEqualTo("hayun-sheet");
        assertThat(dir.resolve("sheet.v1.png")).exists();
        assertThat(map("persona.json").get("name")).isEqualTo("한결");
        assertThat(list("cast.json").get(0).get("name")).isEqualTo("하윤");
    }

    @Test
    @DisplayName("새 주인공에게 뽑아 둔 조연 시트가 있으면 그것이 주인공 시트가 된다")
    void 조연_시트로() throws IOException {
        boolean needs = PersonFiles.swapLead(dir, mapper, 1);

        assertThat(needs).isFalse();
        assertThat(Files.readString(dir.resolve("sheet.png"))).isEqualTo("mom-sheet");
        assertThat(dir.resolve("sheets/한결 엄마.png")).doesNotExist();
        assertThat(Files.readString(dir.resolve("sheets/한결.png"))).isEqualTo("hero-sheet");
    }

    private void put(String name, Object value) throws IOException {
        mapper.writeValue(dir.resolve(name).toFile(), value);
    }

    private Map<String, Object> map(String name) throws IOException {
        return mapper.readValue(dir.resolve(name).toFile(), new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    private List<Map<String, Object>> list(String name) throws IOException {
        return mapper.readValue(dir.resolve(name).toFile(), new TypeReference<List<Map<String, Object>>>() { });
    }
}
