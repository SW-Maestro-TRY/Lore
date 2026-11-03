package com.lore.webtoon.invite;

import com.lore.common.credit.CreditDomain;
import com.lore.common.credit.CreditReason;
import com.lore.common.credit.CreditService;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import com.lore.webtoon.credit.BrowserLinkRepository;
import com.lore.webtoon.job.JobDoneListener;
import com.lore.webtoon.job.WebtoonJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * 친구 초대 보상(2026-11-03).
 *
 * <h2>규칙</h2>
 * <ul>
 *   <li>사람마다 초대 코드 하나. 링크는 {@code /webtoon?ref=<code>&utm_source=invite&utm_medium=friend}</li>
 *   <li>그 링크로 들어온 브라우저에서 <b>새 계정</b>이 만들어지면 초대한 사람에게 가입 보상(기본 10C)</li>
 *   <li>그 친구가 웹툰을 <b>처음 완성</b>하면 초대한 사람에게 완성 보상(기본 10C) 한 번 더</li>
 *   <li>친구에게 더 주는 것은 없다 — 가입 크레딧(WELCOME)은 그대로</li>
 * </ul>
 *
 * <h2>막는 것</h2>
 * <ul>
 *   <li>내 코드로 내가 가입: 초대자와 피초대자가 같으면 안 적는다</li>
 *   <li>같은 기기: 가입한 브라우저 uid 가 초대자의 기기({@code webtoon_browser_link})면 안 적는다</li>
 *   <li>옛 계정 로그인: 계정이 만들어진 지 {@code signup-window} 를 넘겼으면 새 계정이 아니다</li>
 *   <li>한 달 상한(기본 10명): 넘기면 줄은 남기되 보상은 안 준다 — 완성 보상도 같이 안 준다</li>
 * </ul>
 *
 * 보상 자체는 {@link CreditService#grantOnce} 가 refId 로 한 번만 넣는다(출석 보상과 같은 길).
 */
@Service
public class InviteService implements JobDoneListener {

    private static final Logger log = LoggerFactory.getLogger(InviteService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 코드에 쓰는 글자 — 0·O·1·I 처럼 헷갈리는 것은 뺐다. */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LEN = 8;

    static final String MEMO = "친구 초대 보상";
    static final String REF_SIGNUP = "invite-signup-";
    static final String REF_COMPLETE = "invite-complete-";

    private final InviteCodeRepository codes;
    private final InviteRepository invites;
    private final BrowserLinkRepository links;
    private final UserRepository users;
    private final CreditService credits;
    private final int signupCredits;
    private final int completeCredits;
    private final int monthlyCap;
    private final Duration signupWindow;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public InviteService(InviteCodeRepository codes, InviteRepository invites, BrowserLinkRepository links,
                         UserRepository users, CreditService credits,
                         @Value("${lore.invite.signup-credits:10}") int signupCredits,
                         @Value("${lore.invite.complete-credits:10}") int completeCredits,
                         @Value("${lore.invite.monthly-cap:10}") int monthlyCap,
                         @Value("${lore.invite.signup-window-minutes:30}") long signupWindowMinutes) {
        this(codes, invites, links, users, credits, signupCredits, completeCredits, monthlyCap,
                Duration.ofMinutes(signupWindowMinutes), Clock.system(ZONE));
    }

    InviteService(InviteCodeRepository codes, InviteRepository invites, BrowserLinkRepository links,
                  UserRepository users, CreditService credits,
                  int signupCredits, int completeCredits, int monthlyCap, Duration signupWindow, Clock clock) {
        this.codes = codes;
        this.invites = invites;
        this.links = links;
        this.users = users;
        this.credits = credits;
        this.signupCredits = signupCredits;
        this.completeCredits = completeCredits;
        this.monthlyCap = monthlyCap;
        this.signupWindow = signupWindow;
        this.clock = clock;
    }

    /** 내 초대 코드. 없으면 지금 만든다. */
    @Transactional
    public String codeOf(Long userId) {
        return codes.findById(userId).map(InviteCode::getCode).orElseGet(() -> {
            for (int i = 0; i < 5; i++) {
                String code = newCode();
                if (codes.findByCode(code).isEmpty()) {
                    return codes.save(new InviteCode(userId, code, Instant.now(clock))).getCode();
                }
            }
            throw new IllegalStateException("초대 코드를 만들지 못했습니다");
        });
    }

    /** 「친구 초대」 칸에 보일 것. */
    @Transactional
    public Summary summary(Long userId) {
        String code = codeOf(userId);
        long joined = invites.countByInviterId(userId);
        long completed = invites.countByInviterIdAndCompletedAtIsNotNull(userId);
        long monthly = rewardedThisMonth(userId);
        return new Summary(code, joined, completed, earned(userId), signupCredits, completeCredits,
                monthlyCap, Math.max(0, monthlyCap - monthly));
    }

    /**
     * 초대 링크로 들어온 브라우저에서 새 계정이 만들어졌다 — 화면이 로그인 확인 직후 한 번 부른다.
     *
     * 옛 계정으로 로그인한 경우도 같은 길로 오므로, 여기서 새 계정인지(가입한 지 얼마 안 됐는지)를
     * 본다. 결과가 무엇이든 화면은 들고 있던 코드를 지운다.
     */
    @Transactional
    public Claim claim(Long userId, String rawCode, String browserUid) {
        String code = normalizeCode(rawCode);
        if (code.isEmpty()) return Claim.NO_CODE;
        if (invites.findByInviteeId(userId).isPresent()) return Claim.ALREADY;

        Optional<InviteCode> owner = codes.findByCode(code);
        if (owner.isEmpty()) return Claim.UNKNOWN_CODE;
        Long inviterId = owner.get().getUserId();
        if (inviterId.equals(userId)) return Claim.SELF;

        Instant now = Instant.now(clock);
        Instant createdAt = users.findById(userId).map(User::getCreatedAt).orElse(null);
        if (createdAt == null || createdAt.isBefore(now.minus(signupWindow))) return Claim.NOT_NEW;

        String uid = browserUid == null ? "" : browserUid.trim();
        if (!uid.isEmpty() && links.existsByUserIdAndBrowserUid(inviterId, uid)) return Claim.SAME_DEVICE;

        boolean underCap = rewardedThisMonth(inviterId) < monthlyCap;
        Invite invite = invites.save(new Invite(inviterId, userId, code, uid.isEmpty() ? null : uid, now));
        if (underCap) {
            int given = credits.grantOnce(inviterId, signupCredits, CreditReason.REWARD, CreditDomain.WEBTOON,
                    REF_SIGNUP + userId, MEMO);
            if (given > 0) {
                invite.signupRewarded();
                invites.save(invite);
            }
            return given > 0 ? Claim.REWARDED : Claim.RECORDED;
        }
        return Claim.CAPPED;
    }

    /** 작업이 완성됐다 — 피초대자의 첫 완성이면 초대한 사람에게 완성 보상. */
    @Override
    public void onDone(WebtoonJob job) {
        try {
            if (job.getUserId() != null) onCompleted(job.getUserId());
        } catch (RuntimeException e) {
            log.warn("친구 초대 완성 보상을 적지 못함 user={}: {}", job.getUserId(), e.toString());
        }
    }

    /** 이 사람이 웹툰을 완성했다. 초대받은 사람이고 처음이면 초대한 사람에게 준다. 아니면 아무 일도 없다. */
    @Transactional
    public boolean onCompleted(Long inviteeId) {
        Invite invite = invites.findByInviteeId(inviteeId).orElse(null);
        if (invite == null || invite.getCompletedAt() != null) return false;
        boolean rewarded = false;
        if (invite.isSignupRewarded()) {
            rewarded = credits.grantOnce(invite.getInviterId(), completeCredits, CreditReason.REWARD,
                    CreditDomain.WEBTOON, REF_COMPLETE + inviteeId, MEMO) > 0;
        }
        invite.completed(Instant.now(clock), rewarded);
        invites.save(invite);
        return rewarded;
    }

    private long rewardedThisMonth(Long inviterId) {
        Instant monthStart = LocalDate.now(clock).withDayOfMonth(1).atStartOfDay(ZONE).toInstant();
        return invites.countByInviterIdAndSignupRewardedTrueAndSignedUpAtGreaterThanEqual(inviterId, monthStart);
    }

    /** 받은 크레딧 — 보상 줄 수로 센다(가입 보상 줄 × 가입 몫 + 완성 보상 줄 × 완성 몫). */
    private long earned(Long inviterId) {
        long signup = invites.countByInviterIdAndSignupRewardedTrueAndSignedUpAtGreaterThanEqual(inviterId, Instant.EPOCH);
        long complete = invites.countByInviterIdAndCompleteRewardedTrue(inviterId);
        return signup * signupCredits + complete * completeCredits;
    }

    private String newCode() {
        StringBuilder sb = new StringBuilder(CODE_LEN);
        for (int i = 0; i < CODE_LEN; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    static String normalizeCode(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toUpperCase();
        return s.matches("[A-Z0-9]{4,16}") ? s : "";
    }

    public enum Claim {
        /** 초대한 사람에게 가입 보상이 들어갔다. */
        REWARDED,
        /** 줄은 남겼지만 보상은 이미 들어가 있었다(재시도). */
        RECORDED,
        /** 이번 달 상한을 넘겨 보상 없이 줄만 남겼다. */
        CAPPED,
        NO_CODE, UNKNOWN_CODE, SELF, ALREADY, NOT_NEW, SAME_DEVICE
    }

    /**
     * @param code         내 초대 코드
     * @param joined       내 링크로 가입한 사람 수(보상 여부와 상관없이)
     * @param completed    그중 웹툰을 완성한 사람 수
     * @param earned       지금까지 받은 크레딧
     * @param monthlyLeft  이번 달 보상이 남은 사람 수
     */
    public record Summary(String code, long joined, long completed, long earned,
                          int signupCredits, int completeCredits, int monthlyCap, long monthlyLeft) {
    }
}
