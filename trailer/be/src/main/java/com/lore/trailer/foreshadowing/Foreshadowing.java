package com.lore.trailer.foreshadowing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.Arrays;
import java.util.List;

/**
 * 복선 카드 한 장 — 표 {@code foreshadowings}의 한 줄(V20260922_0847__trailer_foreshadowings.sql).
 *
 * <h3>이 표는 서버가 쓰지 않는다</h3>
 * 줄은 NarrativeAnalysis 의 {@code src/deliver/export_cards_sql.py} 가 만든 SQL 로 통째로 들어온다.
 * 서버는 읽기만 한다. 그래서 값을 바꾸는 메서드가 없다.
 *
 * <h3>★★ 회수 칸 셋은 그대로 내보내지 않는다</h3>
 * {@code status} · {@code resolved_chapter} · {@code resolution} 은 400화 기준의 값이다. 독자가 읽은 회차
 * N 뒤에 회수된 복선을 그대로 주면 <b>독자가 아직 읽지 않은 결말</b>이 카드 상세에 뜬다.
 * 그래서 이 셋은 getter 가 없고, N 을 받는 {@link #statusAt} · {@link #resolvedChapterAt} ·
 * {@link #resolutionAt} 만 있다. 가리지 않은 값이 응답으로 나갈 길이 코드에 없다
 * (NarrativeAnalysis migration/decisions.md 2-18).
 *
 * <h3>★ 목록의 순서는 {@code id} 순이다</h3>
 * {@code id} 는 DB 가 매기는 번호다. 넣는 스크립트가 T 번호 순으로 넣어서 {@code id} 순이 곧 T1, T2, …
 * 순이다. {@code thread_id} 로 늘어놓으면 글자 순(T1, T10, T100, T2)이 된다(decisions.md 1-24).
 *
 * <h3>★ 표 이름이 복수라 {@code @Table} 을 적는다</h3>
 * {@code User} → {@code users} 와 같다. ERD 의 이름을 그대로 쓰기로 했다.
 */
@Entity
@Table(name = "foreshadowings",
        uniqueConstraints = @UniqueConstraint(name = "uk_foreshadowings_thread_id", columnNames = "thread_id"))
public class Foreshadowing {

    public static final String OPEN = "open";
    public static final String RESOLVED = "resolved";

    /** 인물 이름을 한 칸에 이어 둔 구분자. lore 에 배열 칼럼 선례가 없어 줄바꿈으로 잇는다(WebtoonCharacter.fate 와 같다). */
    private static final String PEOPLE_SEPARATOR = "\n";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** "T12". 화면과 판정이 카드를 부르는 이름. 응답의 {@code id} 가 이것이다. */
    @Column(name = "thread_id", nullable = false, length = 16)
    private String threadId;

    /** 복선을 심은 회차. 독자가 읽은 회차보다 뒤면 목록에 넣지 않는다. */
    @Column(name = "start_chapter", nullable = false)
    private int startChapter;

    /** 장부의 유형(영어). 화면도 판정도 읽지 않는다 — 거르거나 셀 때 쓰려고 둔다. 응답에 나가지 않는다. */
    @Column(name = "thread_type", nullable = false, length = 40)
    private String threadType;

    /** 유형의 한국어 이름. 유형 거르기와 카드 꼬리표. */
    @Column(name = "thread_kind", nullable = false, length = 40)
    private String threadKind;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String fact;

    /** 인물 이름을 줄바꿈으로 이은 글. 없으면 빈 글. */
    @Column(name = "thread_related_people", nullable = false, columnDefinition = "text")
    private String threadRelatedPeople;

    /** 장부의 원문. 영어로 남는다. */
    @Column(nullable = false, columnDefinition = "text")
    private String excerpt;

    /** 연결된 장면의 번호("V12"). 없으면 null. */
    @Column(length = 16)
    private String scene;

    @Column(name = "scene_excerpt", nullable = false, columnDefinition = "text")
    private String sceneExcerpt;

    // ── 400화 기준의 회수 칸 셋. getter 없음. 아래 *At(chapter) 로만 나간다 ──

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "resolved_chapter")
    private Integer resolvedChapter;

    @Column(columnDefinition = "text")
    private String resolution;

    /**
     * 검색용 글. id · title · fact · 인물 · kind · "12화" 를 이어 소문자로 바꾸고 빈칸을 모두 없앤 것.
     * 화면의 검색 규칙({@code trailer/fe/lib/search.ts})과 같다. {@code resolution} 은 들어 있지 않다 —
     * 들어 있으면 가린 회수 기록이 검색으로 샌다. 응답에 나가지 않는다.
     */
    @Column(name = "search_text", nullable = false, columnDefinition = "text")
    private String searchText;

    /** 400화 장부 파일의 sha256. 줄마다 같다. 판정이 자기 파일과 같은 자료인지 이 값으로 본다. */
    @Column(name = "state_digest", nullable = false, length = 64)
    private String stateDigest;

    /** 400화 카드 파일의 sha256. 줄마다 같다. */
    @Column(name = "cards_digest", nullable = false, length = 64)
    private String cardsDigest;

    protected Foreshadowing() {
    }

    // ── 독자가 읽은 회차 N 으로 보는 값 ─────────────────────────────────────

    /** N화까지 읽은 독자에게 이 카드가 보이는가 — 심은 회차가 N 이하인가. */
    public boolean isPlantedBy(int chapter) {
        return startChapter <= chapter;
    }

    /** N화 독자에게 보이는 상태. N화 뒤에 회수된 복선은 아직 미회수다. */
    public String statusAt(int chapter) {
        return resolvedAfter(chapter) ? OPEN : status;
    }

    /** N화 독자에게 보이는 회수 회차. N화 뒤에 회수됐으면 null. */
    public Integer resolvedChapterAt(int chapter) {
        return resolvedAfter(chapter) ? null : resolvedChapter;
    }

    /** N화 독자에게 보이는 회수 기록. N화 뒤에 회수됐으면 null. */
    public String resolutionAt(int chapter) {
        return resolvedAfter(chapter) ? null : resolution;
    }

    private boolean resolvedAfter(int chapter) {
        return resolvedChapter != null && resolvedChapter > chapter;
    }

    /** 인물 이름 목록. 없으면 빈 목록. */
    public List<String> people() {
        return splitPeople(threadRelatedPeople);
    }

    /** 줄바꿈으로 이은 인물 칸을 목록으로. 빈 글은 빈 목록이다. */
    public static List<String> splitPeople(String column) {
        if (column == null || column.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(column.split(PEOPLE_SEPARATOR))
                .filter(name -> !name.isEmpty())
                .toList();
    }

    // ── 그대로 나가는 칸 ───────────────────────────────────────────────────

    public Long getId() {
        return id;
    }

    public String getThreadId() {
        return threadId;
    }

    public int getStartChapter() {
        return startChapter;
    }

    public String getThreadType() {
        return threadType;
    }

    public String getThreadKind() {
        return threadKind;
    }

    public String getTitle() {
        return title;
    }

    public String getFact() {
        return fact;
    }

    public String getExcerpt() {
        return excerpt;
    }

    public String getScene() {
        return scene;
    }

    public String getSceneExcerpt() {
        return sceneExcerpt;
    }

    public String getStateDigest() {
        return stateDigest;
    }

    public String getCardsDigest() {
        return cardsDigest;
    }
}
