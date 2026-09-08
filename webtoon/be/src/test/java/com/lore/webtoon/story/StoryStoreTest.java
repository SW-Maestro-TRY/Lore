package com.lore.webtoon.story;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 지어진 이야기를 DB 로 옮겨 담는 규칙.
 *
 * <b>이 파일이 지키는 것</b>: 하네스 폴더를 안 읽고도 제목 · 줄거리 · 장면을
 * 낼 수 있는가. 그게 되어야 그 폴더가 작업대가 되고, 다 끝나면 지워도 되는
 * 것이 된다.
 */
class StoryStoreTest {

    private final List<WebtoonStory> rows = new ArrayList<>();
    private StoryStore store;

    @BeforeEach
    void 세운다() {
        WebtoonStoryRepository repo = mock(WebtoonStoryRepository.class);
        when(repo.existsByRunId(anyString())).thenAnswer(c -> rows.stream()
                .anyMatch(s -> s.getRunId().equals(c.getArgument(0))));
        when(repo.findByRunIdOrderByNAsc(anyString())).thenAnswer(c -> rows.stream()
                .filter(s -> s.getRunId().equals(c.getArgument(0)))
                .sorted((a, b) -> Integer.compare(a.getN(), b.getN())).toList());
        when(repo.findByRunIdAndChosenTrue(anyString())).thenAnswer(c -> rows.stream()
                .filter(s -> s.getRunId().equals(c.getArgument(0)) && s.isChosen()).findFirst());
        when(repo.findByRunIdAndN(anyString(), anyInt())).thenAnswer(c -> rows.stream()
                .filter(s -> s.getRunId().equals(c.getArgument(0))
                        && s.getN() == (int) c.getArgument(1)).findFirst());
        when(repo.saveAll(any())).thenAnswer(c -> {
            Iterable<WebtoonStory> got = c.getArgument(0);
            got.forEach(rows::add);
            return got;
        });
        when(repo.save(any(WebtoonStory.class))).thenAnswer(c -> c.getArgument(0));
        doAnswer(c -> {
            Iterable<WebtoonStory> gone = c.getArgument(0);
            gone.forEach(rows::remove);
            return null;
        }).when(repo).deleteAll(any());
        store = new StoryStore(repo);
    }

    private static List<Map<String, Object>> 후보넷() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int n = 1; n <= 4; n++) {
            out.add(Map.of("n", n,
                    "title", "제목 " + n,
                    "genre", "로맨스 판타지",
                    "plot", "줄거리 " + n,
                    "scenes", List.of("장면 하나", "장면 둘"),
                    "cast", List.of(Map.of("name", "유리엘"))));
        }
        return out;
    }

    @Test
    @DisplayName("후보 넷을 통째로 적는다 — 고른 것만 남기면 무엇 중에서 골랐는지가 사라진다")
    void 넷을_적는다() {
        assertThat(store.save("run-1", 후보넷())).isEqualTo(4);
        assertThat(store.candidatesOf("run-1")).hasSize(4);
        assertThat(store.candidatesOf("run-1").getFirst().getTitle()).isEqualTo("제목 1");
    }

    @Test
    @DisplayName("이미 적힌 작품은 두 번 안 적는다 — 두 번 적으면 어느 것이 진짜인지 모른다")
    void 두_번_안_적는다() {
        store.save("run-1", 후보넷());
        assertThat(store.save("run-1", 후보넷())).isZero();
        assertThat(rows).hasSize(4);
    }

    @Test
    @DisplayName("고른 것을 표시한다. 다시 고르면 표시가 옮겨간다")
    void 고르기() {
        store.save("run-1", 후보넷());

        store.choose("run-1", 2);
        assertThat(store.chosenOf("run-1")).get().extracting(WebtoonStory::getN).isEqualTo(2);

        store.choose("run-1", 3);
        assertThat(store.chosenOf("run-1")).get().extracting(WebtoonStory::getN).isEqualTo(3);
        assertThat(rows.stream().filter(WebtoonStory::isChosen)).hasSize(1);
    }

    @Test
    @DisplayName("고른 이야기의 장면을 읽는다 — 완성본에서 그림을 누르면 뜨는 설명이다")
    void 장면() {
        store.save("run-1", 후보넷());
        store.choose("run-1", 2);

        assertThat(store.scenesOf("run-1")).containsExactly("장면 하나", "장면 둘");
    }

    @Test
    @DisplayName("아직 안 골랐으면 장면도 없다 — 아무거나 골라 보여주지 않는다")
    void 안_골랐으면_없다() {
        store.save("run-1", 후보넷());
        assertThat(store.scenesOf("run-1")).isEmpty();
        assertThat(store.chosenOf("run-1")).isEmpty();
    }

    @Test
    @DisplayName("안 옮겨 온 작품은 빈 것을 준다 — 부르는 쪽이 예전 길로 떨어진다")
    void 모르는_작품() {
        assertThat(store.candidatesOf("없는-작품")).isEmpty();
        assertThat(store.scenesOf("없는-작품")).isEmpty();
    }

    @Test
    @DisplayName("빈 것을 넘기면 아무 일도 안 한다")
    void 빈_것() {
        assertThat(store.save("run-1", List.of())).isZero();
        assertThat(store.save(null, 후보넷())).isZero();
        assertThat(store.save("  ", 후보넷())).isZero();
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("다시 지으면 갈아 끼운다 — 안 그러면 고른 적 없는 제목이 작품에 남는다")
    void 갈아_끼운다() {
        store.save("run-1", 후보넷());
        store.choose("run-1", 2);

        List<Map<String, Object>> 새후보 = new ArrayList<>();
        for (int n = 1; n <= 4; n++) {
            새후보.add(Map.of("n", n, "title", "새 제목 " + n, "genre", "무협",
                    "plot", "새 줄거리 " + n, "scenes", List.of("새 장면"),
                    "cast", List.of(Map.of("name", "무명"))));
        }

        assertThat(store.replace("run-1", 새후보)).isEqualTo(4);
        assertThat(rows).hasSize(4);                       // 옛 넷이 남아 있으면 여덟이다
        assertThat(store.candidatesOf("run-1"))
                .extracting(WebtoonStory::getTitle)
                .containsExactly("새 제목 1", "새 제목 2", "새 제목 3", "새 제목 4");
        // 옛 후보에 찍혀 있던 "고름" 표시도 같이 사라진다 — 새 넷은 아직 안 골랐다.
        assertThat(store.chosenOf("run-1")).isEmpty();
    }

    @Test
    @DisplayName("갈아 끼울 것이 없으면 그냥 적는다 — 처음 짓는 것과 같다")
    void 없으면_그냥_적는다() {
        assertThat(store.replace("run-1", 후보넷())).isEqualTo(4);
        assertThat(rows).hasSize(4);
    }

    @Test
    @DisplayName("빈 것으로는 안 갈아 끼운다 — 있던 이야기가 통째로 사라진다")
    void 빈_것으로는_안_지운다() {
        store.save("run-1", 후보넷());

        assertThat(store.replace("run-1", List.of())).isZero();
        assertThat(store.replace("run-1", null)).isZero();

        assertThat(rows).hasSize(4);
    }

    @Test
    @DisplayName("번호가 없는 후보는 건너뛴다 — 고를 수 없는 줄이다")
    void 번호_없는_것은_건너뛴다() {
        assertThat(store.save("run-1", List.of(Map.of("title", "번호 없음")))).isZero();
    }

    @Test
    @DisplayName("편집실에서 제목을 고치면 그 뒤로 그 이름이 뜬다 — 모델이 지은 이름은 안 지운다")
    void 제목을_고친다() {
        store.save("run-1", 후보넷());
        store.choose("run-1", 2);

        String got = store.editTitle("run-1", "  내가   고친   제목  ");

        assertThat(got).isEqualTo("내가 고친 제목");
        assertThat(store.chosenOf("run-1")).get()
                .extracting(WebtoonStory::displayTitle).isEqualTo("내가 고친 제목");
        // 모델이 지은 이름은 그대로 있다 — "왜 이 이야기를 골랐나" 를 볼 때 필요하다.
        assertThat(store.chosenOf("run-1")).get()
                .extracting(WebtoonStory::getTitle).isEqualTo("제목 2");
    }

    @Test
    @DisplayName("빈 값으로 고치면 모델이 지은 이름으로 되돌아간다")
    void 제목을_지우면_원래대로() {
        store.save("run-1", 후보넷());
        store.choose("run-1", 1);
        store.editTitle("run-1", "내가 고친 제목");

        String got = store.editTitle("run-1", "   ");

        assertThat(got).isEqualTo("제목 1");
        assertThat(store.chosenOf("run-1")).get()
                .extracting(WebtoonStory::displayTitle).isEqualTo("제목 1");
    }

    @Test
    @DisplayName("아직 안 고른 작품은 제목을 못 고친다")
    void 안_고르면_못_고친다() {
        store.save("run-1", 후보넷());
        assertThatThrownBy(() -> store.editTitle("run-1", "아무 제목"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }
}
