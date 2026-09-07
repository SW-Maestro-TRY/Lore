package com.lore.common.credit;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 크레딧 — 잔액 · 지급 · 차감 · 환원 · 내역.
 *
 * <b>결제는 여기 없다</b>(#155). 여기가 하는 일은 "지금 얼마인가" 와 "왜 그
 * 숫자인가" 뿐이고, 그 값이 어디서 들어왔는지(결제인지 무료인지)는 이유
 * ({@link CreditReason}) 하나로만 구분한다. 그래서 나중에 PG 를 붙일 때
 * 이쪽은 안 고쳐도 된다 — 충전 줄을 하나 더 적으면 그만이다.
 *
 * <h2>계정에만 붙는다</h2>
 *
 * 지금 제품의 크레딧은 브라우저가 만든 uid 로 센다
 * ({@code haeun/landing/credits.py}). 그건 지우면 새 사람이 되고 값을 지어낼
 * 수도 있어서, <b>로그인 안 한 사람에게만</b> 쓸 수 있는 방식이다. 여기는
 * 계정 것이라 지어낼 수 없다.
 *
 * 둘을 억지로 합치지 않는다 — 게스트는 지금처럼 uid 로 세고(그리고 하루 몫이
 * IP 로 한 번 더 막히고, #228), 로그인하면 이쪽으로 넘어온다. 합치려면 지어낼
 * 수 있는 값을 계정 잔액에 더해야 하는데, 그 순간 계정 쪽도 지어낼 수 있게
 * 된다.
 */
@Service
public class CreditService {

    private static final Logger log = LoggerFactory.getLogger(CreditService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 한 번에 돌려줄 내역 줄 수의 위. 화면이 더 달라고 해도 여기서 끊는다. */
    private static final int MAX_HISTORY = 200;

    private final CreditEventRepository events;
    private final int welcome;
    private final int daily;
    private final Clock clock;

    /* 생성자가 둘이라(아래는 검사에서 시계를 갈아 끼우려고 둔 것) 스프링이
       어느 것으로 만들지 못 고른다 — 표시가 없으면 인자 없는 생성자를 찾다가
       서버가 아예 안 뜬다. 검사로는 안 잡힌다: 검사는 이 클래스를 손으로
       만들므로 스프링이 고를 일이 없다. */
    @Autowired
    public CreditService(CreditEventRepository events,
                         @Value("${lore.credit.welcome:12}") int welcome,
                         @Value("${lore.credit.daily:20}") int daily) {
        this(events, welcome, daily, Clock.system(ZONE));
    }

    CreditService(CreditEventRepository events, int welcome, int daily, Clock clock) {
        this.events = events;
        this.welcome = welcome;
        this.daily = daily;
        this.clock = clock;
    }

    /** 지금 잔액. */
    @Transactional(readOnly = true)
    public int balance(Long userId) {
        return events.balanceOf(userId);
    }

    /**
     * 오늘 몫까지 챙겨 준 다음의 잔액.
     *
     * 화면이 잔액을 물을 때마다 부른다 — 따로 "받기" 단추를 두지 않는다.
     * 받으러 눌러야 하는 무료 크레딧은 안 누른 사람에게는 없는 것과 같고,
     * 그 단추를 누르는 일 자체가 사람이 할 일이 아니다.
     */
    @Transactional
    public int balanceWithDaily(Long userId) {
        LocalDate today = LocalDate.now(clock);
        grantOnce(userId, welcome, CreditReason.WELCOME, "welcome");
        grantOnce(userId, daily, CreditReason.DAILY, today.toString());
        return events.balanceOf(userId);
    }

    /**
     * 한 번만 주는 몫. 이미 줬으면 아무 일도 안 한다.
     *
     * @param refId 무엇에 대한 지급인가 — 가입은 {@code "welcome"}, 날마다 주는
     *              것은 그 날짜. 이 값이 곧 "같은 일인가" 의 기준이다
     * @return 이번에 실제로 준 양 (이미 줬으면 0)
     */
    @Transactional
    public int grantOnce(Long userId, int amount, CreditReason reason, String refId) {
        return grantOnce(userId, amount, reason, CreditDomain.COMMON, refId);
    }

    /** 어디서 준 것인지까지 적는다. 가입 축하·오늘의 무료는 {@code COMMON} 이다. */
    @Transactional
    public int grantOnce(Long userId, int amount, CreditReason reason,
                         CreditDomain domain, String refId) {
        if (amount <= 0) {
            return 0;                       // 0 이면 그 몫을 안 주는 설정이다
        }
        return write(userId, amount, reason, domain, refId, reason.label()) ? amount : 0;
    }

    /**
     * 만들면서 쓴 몫을 뺀다.
     *
     * <b>모자라면 아예 안 뺀다.</b> 있는 만큼만 빼고 넘어가면 잔액이 0 이 된
     * 채로 만들기가 시작되고, 그 사람은 낸 것보다 많이 받은 것이 된다.
     *
     * @param refId 무엇을 만들다 쓴 것인가(작품 id). 같은 작품으로 두 번
     *              불려도 한 번만 빠진다 — 재시도·중복 클릭이 여기서 걸린다
     * @return 이번에 실제로 뺀 양 (이미 뺐으면 0)
     * @throws BusinessException 잔액이 모자랄 때
     */
    @Transactional
    public int spend(Long userId, int amount, String refId) {
        return spend(userId, CreditDomain.COMMON, amount, refId, null);
    }

    /**
     * 어느 서비스에서 무엇에 썼는지까지 적는다.
     *
     * @param domain 웹툰인지 짤인지. 내역 화면이 이것으로 갈라 보여 주고,
     *               도메인별 지출도 이것으로 센다
     * @param memo   사람이 읽을 한 줄(작품 이름 등). 비우면 이유의 기본 문구
     */
    @Transactional
    public int spend(Long userId, CreditDomain domain, int amount, String refId, String memo) {
        if (amount <= 0) {
            return 0;
        }
        if (events.existsByUserIdAndReasonAndRefId(userId, CreditReason.SPEND, refId)) {
            return 0;                       // 이미 낸 것 — 두 번 받지 않는다
        }
        int have = events.balanceOf(userId);
        if (have < amount) {
            throw new BusinessException(ErrorCode.CREDIT_NOT_ENOUGH,
                    "크레딧이 모자랍니다 (필요 " + amount + " · 보유 " + have + ")");
        }
        String line = memo == null || memo.isBlank()
                ? (domain == null ? CreditDomain.COMMON : domain).label() + "에서 사용"
                : memo;
        return write(userId, -amount, CreditReason.SPEND, domain, refId, line) ? amount : 0;
    }

    /**
     * 시작조차 못 한 것을 돌려준다.
     *
     * 뺀 줄을 지우지 않고 반대 줄을 하나 더 적는다 — 지우면 원래 얼마를 냈는지가
     * 사라진다. 낸 적이 없으면 아무 일도 안 한다(안 낸 것을 돌려주면 그게 곧
     * 무한 크레딧이다).
     *
     * @return 돌려준 양 (돌려줄 것이 없으면 0)
     */
    @Transactional
    public int refund(Long userId, String refId, String why) {
        List<CreditEvent> mine = events.historyOf(userId, PageRequest.of(0, MAX_HISTORY));
        List<CreditEvent> paidRows = mine.stream()
                .filter(e -> e.getReason() == CreditReason.SPEND && e.getRefId().equals(refId))
                .toList();
        int paid = paidRows.stream().mapToInt(e -> -e.getDelta()).sum();
        if (paid <= 0) {
            return 0;
        }
        /* **도메인은 낸 줄에서 물려받는다.** 돌려주는 쪽이 따로 정하게 두면
           웹툰에서 낸 것을 짤로 돌려준 것처럼 적힐 수 있고, 그러면 도메인별
           합계가 어긋난다. 금액을 부르는 쪽이 안 정하는 것과 같은 이유다. */
        CreditDomain domain = paidRows.get(0).getDomain();
        return write(userId, paid, CreditReason.REFUND, domain, refId,
                why == null || why.isBlank() ? CreditReason.REFUND.label() : why) ? paid : 0;
    }

    /** 최근 내역. 잔액과 같은 자료에서 나오므로 둘이 어긋날 수 없다. */
    @Transactional(readOnly = true)
    public List<CreditEvent> history(Long userId, int limit) {
        int size = Math.clamp(limit, 1, MAX_HISTORY);
        return events.historyOf(userId, PageRequest.of(0, size));
    }

    /**
     * 한 줄 적는다. 이미 있으면 조용히 넘어간다.
     *
     * 있는지 <b>보고</b> 나서 <b>적는</b> 사이에 남이 먼저 적을 수 있다. 그
     * 틈은 DB 의 유일키가 막아 주는데, 그때 올라오는 예외를 안 잡으면 멀쩡한
     * 요청이 500 으로 떨어진다 — 이미 원하던 대로 되어 있는데도.
     *
     * <b>이 트랜잭션은 따로 연다.</b> 유일키 위반은 지금 트랜잭션 전체를
     * 되돌릴 수 없는 상태로 만들기 때문에, 같은 트랜잭션 안에서 잡아 봐야
     * 뒤이은 조회가 다시 터진다(잔액을 못 읽는다).
     *
     * @return 이번에 새로 적었으면 true
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean write(Long userId, int delta, CreditReason reason, CreditDomain domain,
                  String refId, String memo) {
        if (events.existsByUserIdAndReasonAndRefId(userId, reason, refId)) {
            return false;
        }
        try {
            events.saveAndFlush(CreditEvent.of(
                    userId, delta, reason, domain, refId, memo, Instant.now(clock)));
            return true;
        } catch (DataIntegrityViolationException race) {
            log.debug("같은 크레딧 기록이 거의 동시에 들어왔습니다 (user={}, {} {})",
                    userId, reason, refId);
            return false;
        }
    }
}
