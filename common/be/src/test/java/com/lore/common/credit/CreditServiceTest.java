package com.lore.common.credit;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 크레딧 규칙.
 *
 * 저장소는 목록 하나로 흉내 낸다 — 여기서 보고 싶은 것은 JPA 가 아니라
 * <b>무엇을 몇 번 적는가</b>다. 돈에 준하는 값이라 "두 번 빠지지 않는가",
 * "안 낸 것을 돌려주지 않는가" 가 이 파일의 전부다.
 */
class CreditServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Clock DAY1 = Clock.fixed(Instant.parse("2026-09-06T05:00:00Z"), ZONE);
    private static final Clock DAY2 = Clock.fixed(Instant.parse("2026-09-07T05:00:00Z"), ZONE);

    private static final long ME = 1L;

    private final List<CreditEvent> rows = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();

    private CreditEventRepository repo;

    @BeforeEach
    void 저장소를_세운다() {
        repo = mock(CreditEventRepository.class);
        when(repo.balanceOf(anyLong())).thenAnswer(c ->
                rows.stream().filter(e -> e.getUserId().equals(c.<Long>getArgument(0)))
                        .mapToInt(CreditEvent::getDelta).sum());
        when(repo.existsByUserIdAndReasonAndDomainAndRefId(anyLong(), any(), any(), any()))
                .thenAnswer(c -> rows.stream().anyMatch(
                        e -> e.getUserId().equals(c.<Long>getArgument(0))
                                && e.getReason() == c.getArgument(1)
                                && e.getDomain() == c.getArgument(2)
                                && e.getRefId().equals(c.getArgument(3))));
        when(repo.saveAndFlush(any(CreditEvent.class))).thenAnswer(c -> {
            CreditEvent e = c.getArgument(0);
            setId(e, ids.incrementAndGet());
            rows.add(e);
            return e;
        });
        when(repo.findByUserIdAndReasonAndDomainAndRefId(anyLong(), any(), any(), any()))
                .thenAnswer(c -> rows.stream().filter(
                        e -> e.getUserId().equals(c.<Long>getArgument(0))
                                && e.getReason() == c.getArgument(1)
                                && e.getDomain() == c.getArgument(2)
                                && e.getRefId().equals(c.getArgument(3))).toList());
        when(repo.historyOf(anyLong(), any(Pageable.class))).thenAnswer(c -> {
            long who = c.getArgument(0);
            Pageable page = c.getArgument(1);
            return rows.stream().filter(e -> e.getUserId() == who)
                    .sorted(Comparator.comparing(CreditEvent::getId).reversed())
                    .limit(page.getPageSize()).toList();
        });
    }

    /** 진짜 DB 가 해 주는 일. 순서(내역의 최근 것부터)를 보려면 있어야 한다. */
    private static void setId(CreditEvent e, long id) {
        try {
            var f = CreditEvent.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(e, id);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private CreditService service(int welcome, int daily, Clock clock) {
        return new CreditService(repo, new CreditLedger(repo, clock), welcome, daily, clock);
    }

    @Test
    @DisplayName("처음 물으면 가입 축하와 오늘 몫을 함께 받는다")
    void 첫_조회() {
        assertThat(service(12, 20, DAY1).balanceWithDaily(ME)).isEqualTo(32);
    }

    @Test
    @DisplayName("같은 날 몇 번을 물어도 더 안 준다 — 물어보는 것이 곧 버는 일이 되면 안 된다")
    void 같은_날_여러_번_물어도_안_는다() {
        CreditService credits = service(12, 20, DAY1);
        credits.balanceWithDaily(ME);
        credits.balanceWithDaily(ME);
        assertThat(credits.balanceWithDaily(ME)).isEqualTo(32);
    }

    @Test
    @DisplayName("날이 바뀌면 오늘 몫만 다시 준다 — 가입 축하는 한 번뿐이다")
    void 날이_바뀌면_오늘_몫만() {
        service(12, 20, DAY1).balanceWithDaily(ME);
        assertThat(service(12, 20, DAY2).balanceWithDaily(ME)).isEqualTo(52);
    }

    @Test
    @DisplayName("쓰면 준다")
    void 차감() {
        CreditService credits = service(12, 20, DAY1);
        credits.balanceWithDaily(ME);
        assertThat(credits.spend(ME, 12, "run-1")).isEqualTo(12);
        assertThat(credits.balance(ME)).isEqualTo(20);
    }

    @Test
    @DisplayName("같은 작품으로 두 번 불러도 한 번만 빠진다 — 재시도·중복 클릭이 여기서 걸린다")
    void 같은_작품은_한_번만_빠진다() {
        CreditService credits = service(12, 20, DAY1);
        credits.balanceWithDaily(ME);
        assertThat(credits.spend(ME, 12, "run-1")).isEqualTo(12);
        assertThat(credits.spend(ME, 12, "run-1")).isZero();
        assertThat(credits.balance(ME)).isEqualTo(20);
    }

    @Test
    @DisplayName("모자라면 아예 안 뺀다 — 있는 만큼만 빼면 낸 것보다 많이 받은 것이 된다")
    void 모자라면_안_뺀다() {
        CreditService credits = service(5, 0, DAY1);
        credits.balanceWithDaily(ME);
        assertThatThrownBy(() -> credits.spend(ME, 12, "run-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("모자");
        assertThat(credits.balance(ME)).isEqualTo(5);
    }

    @Test
    @DisplayName("모자랄 때는 402 — 400 도 403 도 아니고 더 내면 되는 상태다")
    void 모자람은_402() {
        CreditService credits = service(1, 0, DAY1);
        credits.balanceWithDaily(ME);
        assertThatThrownBy(() -> credits.spend(ME, 12, "run-1"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CREDIT_NOT_ENOUGH));
    }

    @Test
    @DisplayName("어디서 썼는지가 장부에 남는다 — 장부를 세 도메인이 같이 쓴다")
    void 도메인이_남는다() {
        CreditService credits = service(12, 20, DAY1);
        credits.balanceWithDaily(ME);
        credits.spend(ME, CreditDomain.WEBTOON, 12, "run-1", "남은 시간만큼");

        CreditEvent spent = credits.history(ME, 10).stream()
                .filter(e -> e.getReason() == CreditReason.SPEND).findFirst().orElseThrow();
        assertThat(spent.getDomain()).isEqualTo(CreditDomain.WEBTOON);
        assertThat(spent.getMemo()).isEqualTo("남은 시간만큼");
    }

    @Test
    @DisplayName("받은 것은 공통이다 — 웹툰으로 적어 두면 도메인별 지출에 받은 것이 섞인다")
    void 받은_것은_공통() {
        CreditService credits = service(12, 20, DAY1);
        credits.balanceWithDaily(ME);
        assertThat(credits.history(ME, 10)).allSatisfy(e ->
                assertThat(e.getDomain()).isEqualTo(CreditDomain.COMMON));
    }

    @Test
    @DisplayName("돌려준 줄은 낸 줄과 같은 도메인으로 적힌다 — 안 그러면 도메인별 합계가 어긋난다")
    void 환원은_낸_곳으로_적힌다() {
        CreditService credits = service(12, 20, DAY1);
        credits.balanceWithDaily(ME);
        credits.spend(ME, CreditDomain.WEBTOON, 12, "run-1", "남은 시간만큼");
        assertThat(credits.refund(ME, CreditDomain.WEBTOON, "run-1", "못 만들었습니다"))
                .isEqualTo(12);

        CreditEvent back = credits.history(ME, 10).stream()
                .filter(e -> e.getReason() == CreditReason.REFUND).findFirst().orElseThrow();
        assertThat(back.getDomain()).isEqualTo(CreditDomain.WEBTOON);
    }

    @Test
    @DisplayName("서비스가 다르면 같은 이름이어도 다른 일이다 — refId 는 서비스마다 자기 방식으로 짓는다")
    void 도메인이_다르면_따로_센다() {
        CreditService credits = service(12, 20, DAY1);
        credits.balanceWithDaily(ME);

        /* 웹툰의 작품 번호와 짤의 것이 우연히 같을 수 있다. 「같은 일인가」의
           기준에서 도메인이 빠져 있으면 뒤엣것이 "이미 낸 것" 으로 밀려 조용히
           안 빠진다 — 만든 사람은 두 편을 받는데 낸 것은 한 편이다. */
        assertThat(credits.spend(ME, CreditDomain.WEBTOON, 12, "1", null)).isEqualTo(12);
        assertThat(credits.spend(ME, CreditDomain.ZZAL, 12, "1", null)).isEqualTo(12);
        assertThat(credits.balance(ME)).isEqualTo(8);

        // 돌려주는 것도 그 서비스 것만 돌아온다.
        assertThat(credits.refund(ME, CreditDomain.ZZAL, "1", null)).isEqualTo(12);
        assertThat(credits.balance(ME)).isEqualTo(20);
        assertThat(credits.refund(ME, CreditDomain.ZZAL, "1", null)).isZero();   // 두 번은 없다
    }

    @Test
    @DisplayName("돌려주면 낸 만큼 돌아오고, 뺀 줄은 그대로 남는다")
    void 환원() {
        CreditService credits = service(12, 20, DAY1);
        credits.balanceWithDaily(ME);
        credits.spend(ME, 12, "run-1");

        assertThat(credits.refund(ME, CreditDomain.COMMON, "run-1", "그리지 못했습니다")).isEqualTo(12);
        assertThat(credits.balance(ME)).isEqualTo(32);
        assertThat(rows).anyMatch(e -> e.getReason() == CreditReason.SPEND);   // 안 지웠다
    }

    @Test
    @DisplayName("두 번 돌려주지 않는다")
    void 환원은_한_번만() {
        CreditService credits = service(12, 20, DAY1);
        credits.balanceWithDaily(ME);
        credits.spend(ME, 12, "run-1");
        credits.refund(ME, CreditDomain.COMMON, "run-1", null);

        assertThat(credits.refund(ME, CreditDomain.COMMON, "run-1", null)).isZero();
        assertThat(credits.balance(ME)).isEqualTo(32);
    }

    @Test
    @DisplayName("내역이 아무리 길어도 돌려준다 — 낸 줄이 최근 목록 밖으로 밀려나도")
    void 오래된_것도_돌려준다() {
        CreditService credits = service(12, 20, DAY1);
        credits.balanceWithDaily(ME);
        credits.spend(ME, 12, "run-1");

        /* 낸 뒤로 줄을 잔뜩 쌓는다. 전에는 환원이 최근 200줄만 훑어서, 여기서
           낸 기록이 목록 밖으로 밀려나면 조용히 0 을 돌려줬다 — 만들기가
           실패했는데 아무 말 없이 안 돌려주는 상태다. */
        for (int i = 0; i < 250; i++) {
            credits.grantOnce(ME, 1, CreditReason.REWARD, "덤-" + i);
        }

        assertThat(credits.refund(ME, CreditDomain.COMMON, "run-1", "그리지 못했습니다")).isEqualTo(12);
    }

    @Test
    @DisplayName("낸 적 없는 것은 안 돌려준다 — 그게 곧 무한 크레딧이다")
    void 안_낸_것은_안_돌려준다() {
        CreditService credits = service(12, 20, DAY1);
        credits.balanceWithDaily(ME);
        assertThat(credits.refund(ME, CreditDomain.COMMON, "없는-작품", null)).isZero();
        assertThat(credits.balance(ME)).isEqualTo(32);
    }

    @Test
    @DisplayName("남의 잔액은 안 섞인다")
    void 사람마다_따로() {
        CreditService credits = service(12, 20, DAY1);
        credits.balanceWithDaily(ME);
        credits.balanceWithDaily(2L);
        credits.spend(ME, 12, "run-1");

        assertThat(credits.balance(ME)).isEqualTo(20);
        assertThat(credits.balance(2L)).isEqualTo(32);
    }

    @Test
    @DisplayName("내역은 최근 것부터, 잔액과 같은 자료에서 나온다")
    void 내역() {
        CreditService credits = service(12, 20, DAY1);
        credits.balanceWithDaily(ME);
        credits.spend(ME, 12, "run-1");

        List<CreditEvent> lines = credits.history(ME, 50);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0).getReason()).isEqualTo(CreditReason.SPEND);
        assertThat(lines.stream().mapToInt(CreditEvent::getDelta).sum())
                .isEqualTo(credits.balance(ME));
    }

    @Test
    @DisplayName("내역은 아무리 달라 해도 200줄에서 끊는다")
    void 내역_상한() {
        CreditService credits = service(12, 20, DAY1);
        credits.balanceWithDaily(ME);
        for (int i = 0; i < 300; i++) {
            credits.grantOnce(ME, 1, CreditReason.ADJUST, "adj-" + i);
        }
        assertThat(credits.history(ME, 99_999)).hasSize(200);
    }

    @Test
    @DisplayName("0으로 두면 그 몫을 안 준다 — 끄는 스위치다")
    void 영이면_안_준다() {
        assertThat(service(0, 0, DAY1).balanceWithDaily(ME)).isZero();
    }
}
