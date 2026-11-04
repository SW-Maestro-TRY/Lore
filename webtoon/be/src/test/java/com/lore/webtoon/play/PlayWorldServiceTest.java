package com.lore.webtoon.play;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.exception.BusinessException;
import com.lore.webtoon.character.CharacterService;
import com.lore.webtoon.character.WebtoonCharacter;
import com.lore.webtoon.job.HarnessProcess;
import com.lore.webtoon.lorebook.PrivateLorebookService;
import com.lore.webtoon.safety.SafetyGuard;
import com.lore.webtoon.usage.SpendGuard;
import com.lore.webtoon.usage.UsageService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 세계관 만들기 — 저장·올리기·AI 로 채우기가 예시 캐릭터를 어떻게 다루는지. 모델은 부르지 않는다(하네스 가짜). */
class PlayWorldServiceTest {

    final PlayWorldRepository rows = mock(PlayWorldRepository.class);
    final PrivateLorebookService books = mock(PrivateLorebookService.class);
    final CharacterService characters = mock(CharacterService.class);
    final HarnessProcess harness = mock(HarnessProcess.class);
    final SafetyGuard safety = mock(SafetyGuard.class);
    final SpendGuard guard = mock(SpendGuard.class);
    final UsageService usage = mock(UsageService.class);
    final com.lore.webtoon.Admins admins = mock(com.lore.webtoon.Admins.class);
    final PlayWorldService service = new PlayWorldService(rows, books, characters, harness, safety, guard, usage,
            new PlayCatalog(), admins, 5);
    final Map<String, PlayWorld> store = new LinkedHashMap<>();
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(rows.save(any())).thenAnswer(c -> {
            PlayWorld w = c.getArgument(0);
            store.put(w.id, w);
            return w;
        });
        when(rows.findById(anyString())).thenAnswer(c -> Optional.ofNullable(store.get((String) c.getArgument(0))));
        when(rows.findByIdAndOwnerId(anyString(), anyLong())).thenAnswer(c -> Optional.ofNullable(store.get((String) c.getArgument(0)))
                .filter(w -> w.ownerId.equals(c.getArgument(1))));
        when(rows.findAllByOwnerIdOrderByUpdatedAtDesc(anyLong())).thenAnswer(c -> store.values().stream()
                .filter(w -> w.ownerId.equals(c.getArgument(0))).toList());
        when(rows.reserve(anyLong(), any(), eq(5))).thenReturn(1);
        mine("m1", 7L);
        mine("m2", 7L);
        mine("m3", 7L);
        mine("other", 8L);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private WebtoonCharacter mine(String id, Long owner) {
        WebtoonCharacter c = WebtoonCharacter.described(id, owner, null, "인물 " + id, "설명", Instant.now());
        when(characters.ownedBy(id, owner)).thenReturn(c);
        when(characters.usableIn(eq(id), eq(owner), any())).thenReturn(c);
        return c;
    }

    private static Map<String, Object> body(String visibility, List<String> eventExamples) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", "잿빛 항구");
        b.put("one", "안개 항구");
        b.put("rule", "밤에는 등대 불을 끄지 않는다.");
        b.put("visibility", visibility);
        b.put("cast", List.of(Map.of("id", "a1", "name", "델", "role", "등대지기", "desc", "")));
        b.put("events", List.of(Map.of("id", "x", "title", "꺼진 등대", "situation", "주인공이 등대에 남았다.",
                "cast", List.of("a1"), "examples", eventExamples)));
        b.put("examples", List.of());
        return b;
    }

    @SuppressWarnings("unchecked")
    private Set<String> lastShown() {
        ArgumentCaptor<Collection<String>> shown = ArgumentCaptor.forClass(Collection.class);
        verify(characters, org.mockito.Mockito.atLeastOnce()).showInPlay(any(), shown.capture());
        return Set.copyOf(shown.getValue());
    }

    @Test
    void 저장은_로그인이_필요하다() {
        assertThatThrownBy(() -> service.save(null, null, body("public", List.of())))
                .isInstanceOf(BusinessException.class).hasMessageContaining("로그인");
    }

    @Test
    void 올리면_사건의_예시_중_내_캐릭터만_남에게_열린다() {
        Map<String, Object> view = service.save(null, 7L, body("public", List.of("m1", "other", "m2")));
        String id = (String) view.get("id");
        assertThat(store.get(id).status).isEqualTo(PlayWorld.PUBLISHED);
        Map<?, ?> event = (Map<?, ?>) ((List<?>) view.get("events")).get(0);
        assertThat(event.get("examples")).isEqualTo(List.of("m1", "m2"));       // 남의 캐릭터는 빠진다
        assertThat(event.get("id")).isEqualTo(id + "-e1");
        assertThat(lastShown()).containsExactlyInAnyOrder("m1", "m2");
        assertThat(view.get("example_characters")).asList().hasSize(2);
    }

    @Test
    void 나만으로_바꾸면_닫히고_다른_올라간_세계에_쓰인_예시는_열린_채다() {
        String a = (String) service.save(null, 7L, body("public", List.of("m1", "m2"))).get("id");
        service.save(null, 7L, body("public", List.of("m2", "m3")));
        service.save(a, 7L, body("private", List.of("m1", "m2")));
        assertThat(store.get(a).status).isEqualTo(PlayWorld.DRAFT);
        assertThat(lastShown()).containsExactlyInAnyOrder("m2", "m3");           // m1 은 닫히고 m2 는 다른 세계 덕에 열림
    }

    @Test
    void 남의_세계는_고칠_수_없고_올리기_전_세계는_남에게_안_보인다() {
        String a = (String) service.save(null, 7L, body("private", List.of())).get("id");
        assertThatThrownBy(() -> service.save(a, 8L, body("public", List.of()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.read(a, 8L)).isInstanceOf(BusinessException.class);
        assertThat(service.playable(a, 8L)).isNull();
        assertThat(service.playable(a, 7L)).isNotNull();
    }

    @Test
    void AI_로_채우면_사건마다_모자란_수만큼_예시를_그리고_전에_그려_준_것은_지운다() throws Exception {
        String a = (String) service.save(null, 7L, body("private", List.of("m1"))).get("id");
        WebtoonCharacter oldDrawn = WebtoonCharacter.playExample("old", 7L, a, "옛 예시", "설명", false, Instant.now());
        when(characters.playDrawn(a)).thenReturn(List.of(oldDrawn));
        AtomicInteger drawn = new AtomicInteger();
        when(characters.drawPlayExample(eq(7L), eq(a), anyString(), anyString(), anyBoolean(), anyString()))
                .thenAnswer(c -> WebtoonCharacter.playExample("new" + drawn.incrementAndGet(), 7L, a, c.getArgument(2),
                        c.getArgument(3), false, Instant.now()));
        when(harness.worldMake(any(), anyString())).thenAnswer(c -> {
            Path dir = c.getArgument(0);
            Map<?, ?> input = json.readValue(dir.resolve("input.json").toFile(), Map.class);
            assertThat(((Map<?, ?>) input.get("draft")).get("name")).isEqualTo("잿빛 항구");
            json.writeValue(dir.resolve("result.json").toFile(), Map.of(
                    "name", "잿빛 항구", "genre", "판타지", "one", "안개 항구", "rules", List.of("밤에는 등대 불을 끄지 않는다."),
                    "spot", "새 등대지기",
                    "cast", List.of(Map.of("name", "델", "role", "등대지기", "desc", "말이 없다")),
                    "events", List.of(
                            Map.of("title", "꺼진 등대", "line", "불이 꺼졌다", "place", "등대", "situation", "주인공이 등대에 남았다.",
                                    "spot", "", "examples", List.of(Map.of("name", "가", "description", "겉모습"),
                                            Map.of("name", "나", "description", "겉모습"))),
                            Map.of("title", "부두", "line", "배가 왔다", "place", "부두", "situation", "배가 들어온다.",
                                    "spot", "선원", "examples", List.of(Map.of("name", "다", "description", "겉모습"),
                                            Map.of("name", "라", "description", "겉모습"))))));
            return 0;
        });
        TransactionSynchronizationManager.initSynchronization();
        Map<String, Object> started = service.make(7L, new PlayWorldService.MakeForm(a, body("private", List.of("m1")), null, "", "ko"));
        assertThat(started.get("making")).isEqualTo("running");
        new ArrayList<>(TransactionSynchronizationManager.getSynchronizations()).forEach(s -> s.afterCommit());
        verify(usage, timeout(5000)).ingestMetaFile(anyString(), any());
        Thread.sleep(100);
        PlayWorld row = store.get(a);
        assertThat(row.making).isEqualTo(PlayWorld.IDLE);
        Map<?, ?> doc = json.readValue(row.docJson, Map.class);
        List<?> events = (List<?>) doc.get("events");
        assertThat(((Map<?, ?>) events.get(0)).get("examples")).isEqualTo(List.of("m1", "new1"));   // 고른 하나 + 새로 하나
        assertThat(((Map<?, ?>) events.get(1)).get("examples")).isEqualTo(List.of("new2", "new3"));
        assertThat(((Map<?, ?>) events.get(0)).get("cast")).isEqualTo(List.of("a1"));
        verify(characters, times(3)).drawPlayExample(eq(7L), eq(a), anyString(), anyString(), anyBoolean(), anyString());
        verify(characters, org.mockito.Mockito.atLeastOnce()).dropPlayExamples(a, "old");
        verify(rows).reserve(eq(7L), any(), eq(5));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 그_자리에서_넣은_예시_인물은_사진_그대로_캐릭터가_되고_그림은_다시_저장해도_남는다() {
        when(characters.storeWorldImage(eq(7L), any(), anyString(), anyBoolean()))
                .thenAnswer(c -> "img/" + c.getArgument(2));
        when(characters.imageUrl(anyString())).thenAnswer(c -> {
            String k = c.getArgument(0);
            return k == null || k.isBlank() ? null : "/" + k;
        });
        when(characters.putPlayPerson(anyString(), eq(7L), anyString(), anyString(), anyString(), any(), anyBoolean()))
                .thenAnswer(c -> {
                    WebtoonCharacter one = WebtoonCharacter.playExample(c.getArgument(0), 7L, c.getArgument(2),
                            c.getArgument(3), c.getArgument(4), true, Instant.now());
                    one.drewArt(c.getArgument(5), com.lore.webtoon.character.CharacterSource.PHOTO, Instant.now());
                    when(characters.ownedBy(one.getPublicId(), 7L)).thenReturn(one);
                    when(characters.usableIn(eq(one.getPublicId()), eq(7L), any())).thenReturn(one);
                    when(characters.artUrl(one)).thenReturn(c.getArgument(5) == null ? null : "/" + c.getArgument(5));
                    return one;
                });
        Map<String, Object> b = body("public", List.of());
        b.put("cover_key", "k-cover");
        b.put("events", List.of(Map.of("id", "x", "title", "꺼진 등대", "situation", "주인공이 등대에 남았다.",
                "image_key", "k-ep",
                "example_people", List.of(Map.of("id", "p1", "name", "가람", "role", "등대지기", "desc", "말이 없다",
                        "face_key", "k-face", "face", "data:image/jpeg;base64,AAAA")))));
        Map<String, Object> view = service.save(null, 7L, b);
        String id = (String) view.get("id");
        assertThat(view.get("cover_url")).isEqualTo("/img/k-cover");
        Map<String, Object> ev = (Map<String, Object>) ((List<?>) view.get("events")).get(0);
        assertThat(ev.get("image_url")).isEqualTo("/img/k-ep");
        String cid = PlayWorldService.personId(id, "p1");
        assertThat(ev.get("examples")).isEqualTo(List.of(cid));
        Map<String, Object> person = (Map<String, Object>) ((List<?>) ev.get("example_people")).get(0);
        assertThat(person).containsEntry("character_id", cid).containsEntry("face_url", "/img/k-face").doesNotContainKey("face");
        verify(characters).putPlayPerson(eq(cid), eq(7L), eq(id), eq("가람"), eq("등대지기. 말이 없다"), eq("img/k-face"), eq(true));
        assertThat(store.get(id).docJson).doesNotContain("base64");          // 그림은 창고 키로만 남는다

        // 다시 저장 — 새 그림 없이 지금 주소만 오면 전 그림을 그대로 둔다
        Map<String, Object> again = body("public", List.of());
        again.put("cover_url", "/img/k-cover");
        again.put("events", List.of(Map.of("id", ev.get("id"), "title", "꺼진 등대", "situation", "주인공이 등대에 남았다.",
                "image_url", "/img/k-ep",
                "example_people", List.of(Map.of("id", "p1", "name", "가람", "role", "등대지기", "desc", "말이 없다",
                        "face_url", "/img/k-face")))));
        Map<String, Object> view2 = service.save(id, 7L, again);
        assertThat(view2.get("cover_url")).isEqualTo("/img/k-cover");
        assertThat(((Map<?, ?>) ((List<?>) view2.get("events")).get(0)).get("image_url")).isEqualTo("/img/k-ep");
        verify(characters).putPlayPerson(eq(cid), eq(7L), eq(id), eq("가람"), anyString(), org.mockito.ArgumentMatchers.isNull(), eq(true));
    }

    @Test
    void 운영_세계는_관리자만_고치고_고친_뒤에는_그_편집본을_쓴다() {
        when(rows.existsById(anyString())).thenAnswer(c -> store.containsKey((String) c.getArgument(0)));
        Map<String, Object> b = body("private", List.of());
        b.put("cover_url", "/static/play/zombie-world.jpg");
        assertThatThrownBy(() -> service.save("zombie", 7L, b)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("관리자");
        when(admins.isAdmin(7L)).thenReturn(true);
        Map<String, Object> view = service.save("zombie", 7L, b);
        assertThat(view.get("id")).isEqualTo("zombie");
        assertThat(store.get("zombie").status).isEqualTo(PlayWorld.PUBLISHED);      // 운영 세계는 늘 올라가 있다
        assertThat(view.get("cover_url")).isEqualTo("/static/play/zombie-world.jpg"); // 정적 그림은 주소 그대로
        assertThat(service.overridden("zombie")).isTrue();
        assertThat(service.overrideView("zombie", null)).containsEntry("name", "잿빛 항구");
        assertThat(service.listed(7L)).isEmpty();                                     // 사용자 세계 목록엔 안 섞인다
    }

    @Test
    void 안_고친_운영_세계는_만들기_화면_모양으로_불러온다() {
        when(rows.existsById(anyString())).thenReturn(false);
        when(admins.isAdmin(7L)).thenReturn(true);
        Map<String, Object> view = service.read("zombie", 7L);
        assertThat(view).containsEntry("operator", true).containsEntry("mine", true);
        assertThat((String) view.get("rule")).isNotBlank();
        assertThat(view.get("events")).asList().isNotEmpty();
        assertThat(service.read("zombie", 8L)).containsEntry("mine", false);
    }

    @Test
    void 안전_검사에는_그림을_보내지_않는다() {
        Object clean = PlayWorldService.withoutPictures(Map.of("name", "n", "cover", "data:xx",
                "events", List.of(Map.of("situation", "s", "image", "data:yy",
                        "example_people", List.of(Map.of("name", "가", "face", "data:zz"))))));
        assertThat(clean.toString()).doesNotContain("data:").contains("가").contains("s");
    }

    @Test
    void 하루_횟수를_다_쓰면_모델을_안_부른다() throws Exception {
        when(rows.reserve(anyLong(), any(), eq(5))).thenReturn(0);
        TransactionSynchronizationManager.initSynchronization();
        assertThatThrownBy(() -> service.make(7L, new PlayWorldService.MakeForm(null, body("private", List.of()), null, "", "ko")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("횟수");
        verify(harness, never()).worldMake(any(), anyString());
    }

    @org.junit.jupiter.api.Test
    void 원래_전개는_상황_뒤에_번호를_달아_붙는다() {
        java.util.Map<String, Object> event = java.util.Map.of("canon", java.util.List.of("숨긴다", " ", "들킨다"));
        org.assertj.core.api.Assertions.assertThat(PlayWorldService.withCanon("상황", event))
                .isEqualTo("상황\n\n[원래 전개]\n1. 숨긴다\n2. 들킨다");
        org.assertj.core.api.Assertions.assertThat(PlayWorldService.withCanon("상황", java.util.Map.of())).isEqualTo("상황");
    }
}
