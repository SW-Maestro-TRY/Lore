package com.lore.webtoon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 계정에 이어진 브라우저들의 작품을 모으는 자리.
 *
 * 스프링을 안 띄운다 — 여기서 보는 것은 "무엇을 하네스에 묻고, 받은 것을 어떻게
 * 합치는가" 라서 가짜 저장소와 가짜 하네스면 충분하다.
 */
class MyWebtoonServiceTest {

    private FakeLinks links;
    private HarnessGateway gateway;
    private MyWebtoonService service;

    @BeforeEach
    void setUp() {
        links = new FakeLinks();
        gateway = mock(HarnessGateway.class);
        service = new MyWebtoonService(links, gateway, new ObjectMapper());
    }

    private void harnessReturns(String uid, String json) {
        when(gateway.forward(eq(HttpMethod.GET), eq("/api/runs"), eq("owner=" + uid),
                             any(), any(HttpHeaders.class)))
                .thenReturn(ResponseEntity.ok(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("기기를 여러 개 써도 한 목록으로 모인다")
    void 여러_브라우저를_합친다() {
        service.link(7L, "uidA");
        service.link(7L, "uidB");
        harnessReturns("uidA", "{\"runs\":[{\"run_id\":\"r1\"},{\"run_id\":\"r2\"}]}");
        harnessReturns("uidB", "{\"runs\":[{\"run_id\":\"r3\"}]}");

        assertThat(service.myRuns(7L)).extracting(m -> m.get("run_id"))
                .containsExactly("r1", "r2", "r3");
    }

    @Test
    @DisplayName("기기 하나를 못 읽어도 나머지는 준다")
    void 하나가_실패해도_나머지는_남는다() {
        service.link(7L, "uidA");
        service.link(7L, "uidB");
        harnessReturns("uidA", "{\"runs\":[{\"run_id\":\"r1\"}]}");
        // 하네스가 안 떠 있을 때 게이트웨이가 주는 모양(502 + 한글 사유)
        when(gateway.forward(eq(HttpMethod.GET), eq("/api/runs"), eq("owner=uidB"),
                             any(), any(HttpHeaders.class)))
                .thenReturn(ResponseEntity.status(502).body("{\"error\":\"x\"}".getBytes()));

        assertThat(service.myRuns(7L)).extracting(m -> m.get("run_id")).containsExactly("r1");
    }

    @Test
    @DisplayName("남의 계정 것은 안 섞인다")
    void 계정별로_갈린다() {
        service.link(7L, "uidA");
        service.link(9L, "uidB");
        harnessReturns("uidA", "{\"runs\":[{\"run_id\":\"r1\"}]}");
        harnessReturns("uidB", "{\"runs\":[{\"run_id\":\"r9\"}]}");

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
