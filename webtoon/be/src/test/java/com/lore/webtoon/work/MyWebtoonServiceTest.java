package com.lore.webtoon.work;

import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.credit.BrowserLink;
import com.lore.webtoon.credit.BrowserLinkRepository;
import com.lore.common.exception.BusinessException;
import com.lore.webtoon.runs.RunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 계정에 이어진 브라우저들의 작품을 모으는 자리.
 *
 * 스프링을 안 띄운다 — 여기서 보는 것은 "누구 것인가를 어떻게 가리고, 받은
 * 것을 어떻게 합치는가" 라서 가짜 저장소면 충분하다.
 *
 * <p><b>2026-09-12에 기준이 바뀌었다.</b> 예전에는 브라우저 uid 마다 하네스
 * (serve.py)에게 목록을 묻고 합쳤다. 지금은 파이썬을 서버로 띄우지 않으므로
 * 물을 곳이 없다 — 누구 것인가는 DB(WorkLedger)가, 카드 내용은 RunService 가
 * 안다. 그래서 이 파일의 검사도 하네스 응답이 아니라 <b>그 둘</b>을 세운다.
 */
class MyWebtoonServiceTest {

    private FakeLinks links;
    private WorkLedger ledger;
    private PageStore pageStore;
    private RunService runService;
    private MyWebtoonService service;

    @BeforeEach
    void setUp() {
        links = new FakeLinks();
        /* 작품 표 자체는 여기서 볼 것이 아니다(WorkLedgerTest 가 본다). */
        ledger = mock(WorkLedger.class);
        /* 그림 자리를 옮기는 일은 여기서 볼 것이 아니다(PageStoreTest 가 본다). */
        pageStore = mock(PageStore.class);
        /* 카드 만드는 규칙은 여기서 볼 것이 아니다(RunServiceTest 가 본다). */
        runService = mock(RunService.class);
        when(runService.cardOf(anyString())).thenReturn(null);
        when(ledger.runIdsOf(anyLong())).thenReturn(List.of());
        service = new MyWebtoonService(links, ledger, pageStore, runService);
    }

    /** 이 작품이 이 사람 것이고, 카드도 만들어진다고 세워 둔다. */
    private void 작품이_있다(Long userId, String... runIds) {
        when(ledger.runIdsOf(userId)).thenReturn(List.of(runIds));
        for (String runId : runIds) {
            when(runService.cardOf(runId)).thenReturn(Map.of("run_id", runId));
        }
    }

    @Test
    @DisplayName("기기를 여러 개 써도 한 목록으로 모인다")
    void 여러_브라우저를_합친다() {
        service.link(7L, "uidA");
        service.link(7L, "uidB");
        작품이_있다(7L, "r1", "r2", "r3");

        assertThat(service.myRuns(7L)).extracting(m -> m.get("run_id"))
                .containsExactly("r1", "r2", "r3");
    }

    @Test
    @DisplayName("같은 작품이 두 번 나와도 한 줄만 남는다")
    void 겹치는_것을_거른다() {
        when(ledger.runIdsOf(7L)).thenReturn(List.of("r1", "r1", "r2"));
        when(runService.cardOf("r1")).thenReturn(Map.of("run_id", "r1"));
        when(runService.cardOf("r2")).thenReturn(Map.of("run_id", "r2"));

        assertThat(service.myRuns(7L)).extracting(m -> m.get("run_id"))
                .containsExactly("r1", "r2");
    }

    @Test
    @DisplayName("한 편을 못 읽어도 나머지는 준다")
    void 하나가_실패해도_나머지는_남는다() {
        when(ledger.runIdsOf(7L)).thenReturn(List.of("r1", "깨진것"));
        when(runService.cardOf("r1")).thenReturn(Map.of("run_id", "r1"));
        // 카드를 못 만들면 null 이 온다 — 그 한 편만 빠지고 목록은 살아야 한다.
        when(runService.cardOf("깨진것")).thenReturn(null);

        assertThat(service.myRuns(7L)).extracting(m -> m.get("run_id")).containsExactly("r1");
    }

    @Test
    @DisplayName("남의 계정 것은 안 섞인다")
    void 계정별로_갈린다() {
        작품이_있다(7L, "r1");
        작품이_있다(9L, "r9");

        assertThat(service.myRuns(7L)).extracting(m -> m.get("run_id")).containsExactly("r1");
        assertThat(service.myRuns(9L)).extracting(m -> m.get("run_id")).containsExactly("r9");
    }

    @Test
    @DisplayName("같은 브라우저를 또 이어도 한 번만 남는다")
    void 두_번_이어도_한_줄() {
        assertThat(service.link(7L, "uidA")).isTrue();
        assertThat(service.link(7L, "uidA")).isFalse();
        assertThat(links.rows).hasSize(1);
    }

    @Test
    @DisplayName("이상한 uid 는 아예 안 받는다")
    void uid_를_다듬는다() {
        assertThat(MyWebtoonService.normalize("umt747mfwy4k8hbj8")).isEqualTo("umt747mfwy4k8hbj8");
        assertThat(MyWebtoonService.normalize("  u1  ")).isEqualTo("u1");
        assertThat(MyWebtoonService.normalize(null)).isEmpty();
        assertThat(MyWebtoonService.normalize("")).isEmpty();
        // 경로·질의를 비집고 들어갈 수 있는 글자
        assertThat(MyWebtoonService.normalize("../secret")).isEmpty();
        assertThat(MyWebtoonService.normalize("a&owner=b")).isEmpty();
        assertThat(MyWebtoonService.normalize("a".repeat(65))).isEmpty();

        // 안 받은 값은 저장도 안 된다 — 로그인 자체를 막지는 않는다
        assertThat(service.link(7L, "../secret")).isFalse();
        assertThat(links.rows).isEmpty();
    }

    @Test
    @DisplayName("공개를 내리면 그림 자리도 같이 옮긴다")
    void 공개_전환은_그림까지_옮긴다() {
        when(ledger.mayChange("r1", 7L)).thenReturn(true);

        assertThat(service.setVisibility(7L, "r1", false)).isFalse();

        verify(ledger).setPublic("r1", false);
        /* 안 옮기면 비공개로 내려도 CloudFront 가 계속 내준다 — 스위치가
           거짓말을 하는 셈이다. 그래서 이 한 줄이 빠지는 것을 여기서 잡는다. */
        verify(pageStore).moveAll("r1", false);
    }

    @Test
    @DisplayName("그림을 못 옮겨도 공개 전환 자체는 끝난 것으로 답한다")
    void 그림_옮기기_실패는_던지지_않는다() {
        when(ledger.mayChange("r1", 7L)).thenReturn(true);
        doThrow(new IllegalStateException("S3 가 안 됨")).when(pageStore).moveAll(anyString(), anyBoolean());

        /* 이미 DB 는 바뀌었고 목록에서도 내려가 있다. 남은 것은 "주소를 아는
           사람에게 아직 열린다" 이고, 그건 크게 로그로 남기고 다시 시도할 일이다. */
        assertThat(service.setVisibility(7L, "r1", false)).isFalse();
        verify(ledger).setPublic("r1", false);
    }

    @Test
    @DisplayName("내 것이 아니면 못 바꾼다 — 표에 쓰지도 않는다")
    void 남의_작품은_못_바꾼다() {
        when(ledger.mayChange("남의것", 7L)).thenReturn(false);

        assertThatThrownBy(() -> service.setVisibility(7L, "남의것", true))
                .isInstanceOf(BusinessException.class);
        verify(ledger, never()).setPublic(anyString(), anyBoolean());
        verify(pageStore, never()).moveAll(anyString(), anyBoolean());
    }

    /** JPA 없이 도는 가짜 저장소. 이 서비스가 쓰는 세 가지만 진짜처럼 군다. */
    private static class FakeLinks implements BrowserLinkRepository {
        final List<BrowserLink> rows = new ArrayList<>();

        @Override public List<BrowserLink> findByUserId(Long userId) {
            return rows.stream().filter(r -> r.getUserId().equals(userId)).toList();
        }

        @Override public boolean existsByUserIdAndBrowserUid(Long userId, String uid) {
            return rows.stream().anyMatch(
                    r -> r.getUserId().equals(userId) && r.getBrowserUid().equals(uid));
        }

        @Override @SuppressWarnings("unchecked")
        public <S extends BrowserLink> S save(S entity) {
            rows.add(entity);
            return entity;
        }

        // 아래는 안 쓰는 것들 — JpaRepository 를 구현하느라 있어야 할 뿐이다.
        @Override public <S extends BrowserLink> List<S> saveAll(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public java.util.Optional<BrowserLink> findById(Long id) { throw new UnsupportedOperationException(); }
        @Override public boolean existsById(Long id) { throw new UnsupportedOperationException(); }
        @Override public List<BrowserLink> findAll() { return List.copyOf(rows); }
        @Override public List<BrowserLink> findAllById(Iterable<Long> ids) { throw new UnsupportedOperationException(); }
        @Override public long count() { return rows.size(); }
        @Override public void deleteById(Long id) { throw new UnsupportedOperationException(); }
        @Override public void delete(BrowserLink e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllById(Iterable<? extends Long> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll(Iterable<? extends BrowserLink> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { rows.clear(); }
        @Override public void flush() { }
        @Override public <S extends BrowserLink> S saveAndFlush(S e) { return save(e); }
        @Override public <S extends BrowserLink> List<S> saveAllAndFlush(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<BrowserLink> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch() { rows.clear(); }
        @Override public BrowserLink getOne(Long id) { throw new UnsupportedOperationException(); }
        @Override public BrowserLink getById(Long id) { throw new UnsupportedOperationException(); }
        @Override public BrowserLink getReferenceById(Long id) { throw new UnsupportedOperationException(); }
        @Override public <S extends BrowserLink> java.util.Optional<S> findOne(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
        @Override public <S extends BrowserLink> List<S> findAll(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
        @Override public <S extends BrowserLink> List<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public <S extends BrowserLink> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Pageable p) { throw new UnsupportedOperationException(); }
        @Override public <S extends BrowserLink> long count(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
        @Override public <S extends BrowserLink> boolean exists(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
        @Override public <S extends BrowserLink, R> R findBy(org.springframework.data.domain.Example<S> ex, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> fn) { throw new UnsupportedOperationException(); }
        @Override public List<BrowserLink> findAll(org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public org.springframework.data.domain.Page<BrowserLink> findAll(org.springframework.data.domain.Pageable p) { throw new UnsupportedOperationException(); }
    }
}
