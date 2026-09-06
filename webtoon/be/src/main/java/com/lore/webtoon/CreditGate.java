package com.lore.webtoon;

import com.lore.common.credit.CreditService;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * 로그인한 사람의 계정 크레딧으로 한 편 값을 받는다.
 *
 * <h2>왜 계정 쪽을 따로 두는가</h2>
 *
 * 하네스에도 크레딧이 있다({@code landing/credits.py}). 그런데 그건 브라우저가
 * 만든 uid 로 세서, 지우면 새 사람이 되고 값을 지어낼 수도 있다. 로그인 안 한
 * 사람에게는 그것 말고 셀 방법이 없으니 그대로 두고, <b>로그인한 사람은 계정
 * 것으로 받는다</b> — 계정은 지어낼 수 없다.
 *
 * <h2>두 번 받지 않는다</h2>
 *
 * 여기서 받으면 하네스에는 <b>이미 받았다고 알린다</b>({@link #BILLED_HEADER}).
 * 안 알리면 로그인한 사람만 두 곳에서 두 번 낸다. 이 표시를 하네스가 믿어도
 * 되는 이유는 하네스가 밖에 안 열려 있기 때문이다 — 프론트는 늘 이 스프링을
 * 거치고, 하네스 주소는 서버 안에서만 닿는다. 그 전제가 깨지면(하네스를 밖에
 * 열면) 이 표시부터 다시 봐야 한다.
 *
 * <h2>확인은 미리, 받는 것은 만들어진 뒤</h2>
 *
 * 잔액은 넘기기 <b>전에</b> 보고, 실제로 빼는 것은 작업이 만들어진 <b>뒤</b>다.
 * 먼저 빼면 작업 만들기가 실패했을 때 낸 것만 사라진다. 반대로 확인까지 미루면
 * 모자란 사람의 생성이 이미 시작된 뒤다 — 그때는 돈이 나가고 있다.
 * (하네스가 같은 순서를 쓰고 있고, 이슈 #16 에 그 이유가 적혀 있다.)
 */
@Service
public class CreditGate {

    private static final Logger log = LoggerFactory.getLogger(CreditGate.class);

    /**
     * "계정에서 이미 받았다" 는 표시. 하네스가 이 표시를 보면 자기 uid 크레딧을
     * 안 건드린다.
     */
    static final String BILLED_HEADER = "X-Lore-Account-Billed";

    private final CreditService credits;
    private final int cost;

    @Autowired
    public CreditGate(CreditService credits,
                      @Value("${lore.credit.cost-per-episode:12}") int cost) {
        this.credits = credits;
        this.cost = cost;
    }

    /** 이 사람의 잔액. 로그인 안 했으면 0 — 게스트는 크레딧으로 안 센다. */
    public int balanceOf(Long userId) {
        return userId == null ? 0 : credits.balance(userId);
    }

    /** 지금 로그인한 사람. 안 했으면 {@code null}. */
    public static Long currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Long id ? id : null;
    }

    /** 한 편에 드는 값. 화면이 만들기 전에 적어 두려고도 묻는다. */
    public int cost() {
        return cost;
    }

    /**
     * 낼 수 있는가. 아직 빼지는 않는다.
     *
     * @return 막을 이유(사람이 읽을 한 줄). 괜찮거나 로그인 안 했으면 {@code null}
     */
    public String whyBlocked(Long userId) {
        return whyBlocked(userId, cost);
    }

    /**
     * 값을 지정해서 묻는다 — 웹툰 한 편(12)과 캐릭터 한 장(1)은 값이 다르다.
     */
    public String whyBlocked(Long userId, int need) {
        if (userId == null || need <= 0) {
            return null;
        }
        int have = credits.balance(userId);
        if (have < need) {
            return "크레딧이 모자랍니다 (필요 " + need + " · 보유 " + have + ")";
        }
        return null;
    }

    /**
     * 만들어진 작업 값을 실제로 뺀다.
     *
     * <b>여기서 실패해도 요청을 실패시키지 않는다.</b> 작업은 이미 시작됐고
     * 돈도 나가기 시작했다 — 그 상태에서 사람에게 오류를 보여 주면, 만들어지고
     * 있는데 안 만들어진 줄 안다. 대신 크게 남긴다: 못 받은 것은 사람이 나중에
     * 맞춰야 하는 일이다.
     *
     * @param jobId 무엇에 대한 값인가. 같은 작업으로 두 번 불려도 한 번만 빠진다
     */
    public void charge(Long userId, String jobId) {
        charge(userId, cost, jobId, null);
    }

    /** 값과 사유를 지정해서 받는다. 같은 {@code ref} 로 두 번 불려도 한 번만 빠진다. */
    public void charge(Long userId, int amount, String ref, String memo) {
        if (userId == null || amount <= 0 || ref == null || ref.isBlank()) {
            return;
        }
        try {
            credits.spend(userId, amount, ref);
            if (memo != null) {
                log.debug("크레딧 {} 받음 (user={}, {})", amount, userId, memo);
            }
        } catch (BusinessException e) {
            // 미리 봤을 때는 있었는데 그 사이에 다른 창에서 썼다는 뜻이다.
            log.error("크레딧을 못 받았습니다 — 만들기는 이미 시작됐습니다 "
                    + "(user={}, ref={}, {})", userId, ref, e.getMessage());
        } catch (RuntimeException e) {
            log.error("크레딧을 못 받았습니다 (user={}, ref={})", userId, ref, e);
        }
    }

    /** 시작조차 못 했으면 돌려준다. 낸 적이 없으면 아무 일도 안 한다. */
    public void refund(Long userId, String jobId) {
        if (userId == null || jobId == null || jobId.isBlank()) {
            return;
        }
        try {
            credits.refund(userId, jobId, "만들기를 시작하지 못했습니다");
        } catch (RuntimeException e) {
            log.error("크레딧을 못 돌려줬습니다 (user={}, job={})", userId, jobId, e);
        }
    }

    /** 크레딧이 모자랄 때의 코드. 화면이 이걸 보고 "충전하러 가기" 를 띄운다. */
    public static ErrorCode notEnough() {
        return ErrorCode.CREDIT_NOT_ENOUGH;
    }
}
