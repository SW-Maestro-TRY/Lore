package com.lore.webtoon;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;

/**
 * 로그인 안 한 사람의 오늘 몫을 센다.
 *
 * {@link SpendGuard} 가 "오늘 전체" 를 막는다면 여기는 "한 사람" 을 막는다.
 * 전체 상한만 있으면 한 사람이 하루 몫을 통째로 써 버릴 수 있고, 그러면 상한이
 * 있으나 마나다 — 다른 사람은 여전히 아무것도 못 만든다.
 *
 * <h2>로그인한 사람은 안 센다</h2>
 *
 * 계정은 지어낼 수 없으므로 크레딧으로 세면 된다(#16). 여기서 또 세면 로그인한
 * 사람이 오히려 더 막히는 이상한 일이 된다 — 로그인할 이유가 없어진다.
 *
 * <h2>얼마나 막는가</h2>
 *
 * 기본 하루 2회다. 한 편에 실측 1,148원이고, "한번 만들어 보고 마음에 들면
 * 로그인" 이 이 제품이 바라는 흐름이라 그 한 번을 넉넉히 두 번으로 잡았다.
 * 이 숫자는 {@code lore.webtoon.spend.guest-free} 로 바꾼다.
 *
 * <h2>한계 — 알고 두는 것</h2>
 *
 * IP 는 공유되고(카페 · 회사 · 이동통신 NAT) 바꿀 수도 있다. 그러니 이것은
 * 담장이지 벽이 아니다. 마음먹고 우회하는 사람은 못 막고, 막으려 들면 아무
 * 잘못 없는 사람이 먼저 막힌다. 실제 방어선은 전체 일일 상한 쪽이다.
 *
 * 세는 것과 쓰는 것 사이에 아주 짧은 틈이 있어, 같은 순간에 두 번 누르면 한
 * 번 더 나갈 수 있다. 일부러 잠그지 않았다 — 그 틈으로 새는 것은 많아야 한두
 * 편이고, 그 한두 편도 전체 상한 안쪽이다. 잠그면 만들기 시작이 그만큼 느려진다.
 */
@Service
public class GuestGate {

    private static final Logger log = LoggerFactory.getLogger(GuestGate.class);

    /** 사람이 "오늘" 이라고 부르는 날과 같아야 한다. */
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final GuestQuotaRepository quotas;
    private final long freePerDay;
    private final String salt;
    private final Clock clock;

    public GuestGate(GuestQuotaRepository quotas,
                     @Value("${lore.webtoon.spend.guest-free:2}") long freePerDay,
                     @Value("${lore.webtoon.spend.ip-salt:}") String salt) {
        this(quotas, freePerDay, salt, Clock.system(ZONE));
    }

    GuestGate(GuestQuotaRepository quotas, long freePerDay, String salt, Clock clock) {
        this.quotas = quotas;
        this.freePerDay = freePerDay;
        this.salt = salt == null ? "" : salt;
        this.clock = clock;
    }

    /**
     * 지금 이 사람이 한 편 더 만들어도 되는가. 되면 <b>그 자리에서 한 번
     * 썼다고 적는다</b> — 묻기만 하고 안 적으면 아무리 물어도 늘 통과한다.
     *
     * @return 막을 이유(사람이 읽을 한 줄). 괜찮으면 {@code null}
     */
    @Transactional
    public String useOrBlock(HttpServletRequest request) {
        if (loggedIn()) {
            return null;                     // 계정은 크레딧으로 센다 (#16)
        }
        if (freePerDay <= 0) {
            return null;                     // 0 이면 안 센다 — 끄는 스위치
        }

        String who = hash(clientIp(request));
        LocalDate today = LocalDate.now(clock);
        GuestQuota quota = quotas.findByIpHashAndDay(who, today)
                .orElseGet(() -> new GuestQuota(who, today));

        if (quota.getUsed() >= freePerDay) {
            log.info("게스트 하루 몫을 다 썼습니다 ({}/{}편)", quota.getUsed(), freePerDay);
            return "오늘 무료로 만들 수 있는 " + freePerDay + "편을 다 쓰셨어요 — "
                    + "로그인하시면 이어서 만들 수 있어요.";
        }

        quota.use();
        quotas.save(quota);
        return null;
    }

    /** 로그인해 있는가. {@code @LoginUser} 는 없으면 예외를 던지므로 직접 본다. */
    private boolean loggedIn() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Long;
    }

    /**
     * 진짜 접속 주소.
     *
     * 운영에서는 앞에 CloudFront 가 서 있어서 {@code getRemoteAddr()} 은 늘
     * 그 쪽 주소다 — 그대로 쓰면 <b>온 세상이 한 사람</b>이 되어 아무도 못
     * 만들게 된다. 그래서 {@code X-Forwarded-For} 의 <b>맨 앞</b>을 본다.
     *
     * 맨 앞은 클라이언트가 지어낼 수 있는 값이라 믿을 것이 못 된다. 알고
     * 둔다 — 위 머리말대로 이것은 담장이고, 지어내는 사람까지 막으려면
     * 프록시 수를 세어 뒤에서부터 짚어야 하는데 그 수가 바뀌면 조용히
     * 어긋난다. 잘못 막는 것보다 못 막는 편이 낫다.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "" : remote;
    }

    /** 주소를 그대로 안 남긴다 — 세는 데는 "같은 곳인가" 만 필요하다. */
    private String hash(String ip) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] out = sha.digest((salt + "|" + ip).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 이 없습니다", e);
        }
    }
}
