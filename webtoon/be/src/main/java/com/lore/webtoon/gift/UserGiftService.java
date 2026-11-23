package com.lore.webtoon.gift;

import com.lore.common.credit.CreditDomain;
import com.lore.common.credit.CreditReason;
import com.lore.common.credit.CreditService;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 개별 사용자 사과·보상 크레딧 등록과 1회 수령.
 *
 * 수령 시 대상 계정 이메일을 검증하고 {@link CreditService#grantOnce} 로 원장 중복 지급을 막는다.
 */
@Service
public class UserGiftService {

    static final int MAX_CREDITS = 5_000;

    private final UserGiftRepository gifts;
    private final UserRepository users;
    private final CreditService credits;
    private final Clock clock;

    @Autowired
    public UserGiftService(UserGiftRepository gifts, UserRepository users, CreditService credits) {
        this(gifts, users, credits, Clock.systemUTC());
    }

    UserGiftService(UserGiftRepository gifts, UserRepository users, CreditService credits, Clock clock) {
        this.gifts = gifts;
        this.users = users;
        this.credits = credits;
        this.clock = clock;
    }

    /** 관리자가 특정 이메일 대상 보상을 등록한다. 같은 refKey 가 이미 있으면 그 행을 그대로 돌려준다(멱등). */
    @Transactional
    public UserGift create(String refKey, String email, String title, String message, int amount, Long adminId) {
        String key = clean(refKey, 120, "ref_key");
        String emailKey = normalizeEmail(email);
        if (emailKey.isEmpty() || !emailKey.contains("@")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "올바른 이메일을 적어 주세요");
        }
        String cleanTitle = clean(title, 160, "제목");
        String cleanMessage = clean(message, 4_000, "내용");
        if (amount < 1 || amount > MAX_CREDITS) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "크레딧은 1 이상 " + MAX_CREDITS + " 이하로 적어 주세요");
        }
        return gifts.findByRefKey(key).orElseGet(() ->
                gifts.save(new UserGift(key, emailKey, cleanTitle, cleanMessage, amount, adminId, Instant.now(clock))));
    }

    /** 지금 로그인한 계정이 아직 받지 않은 보상 목록. */
    @Transactional(readOnly = true)
    public List<UserGift> pendingFor(Long userId) {
        if (userId == null) {
            return List.of();
        }
        String emailKey = users.findById(userId)
                .map(User::getEmail)
                .map(UserGiftService::normalizeEmail)
                .orElse("");
        if (emailKey.isEmpty()) {
            return List.of();
        }
        return gifts.findByEmailKeyAndClaimedAtIsNullOrderByIdAsc(emailKey);
    }

    /** 보상을 수령해 크레딧을 1회 지급한다. */
    @Transactional
    public ClaimResult claim(Long userId, Long giftId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "로그인이 필요합니다");
        }
        User user = users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "계정을 찾을 수 없습니다"));
        UserGift gift = gifts.findById(giftId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "보상을 찾을 수 없습니다"));

        String myEmail = normalizeEmail(user.getEmail());
        if (myEmail.isEmpty() || !myEmail.equals(gift.getEmailKey())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "이 계정의 보상이 아닙니다");
        }

        int granted = 0;
        if (gift.getClaimedAt() == null) {
            granted = credits.grantOnce(
                    userId,
                    gift.getCredits(),
                    CreditReason.REWARD,
                    CreditDomain.WEBTOON,
                    "gift:" + gift.getRefKey(),
                    gift.getTitle());
            gift.markClaimed(userId, Instant.now(clock));
            gifts.save(gift);
        }
        int balance = credits.balance(userId);
        return new ClaimResult(gift.getId(), gift.getRefKey(), gift.getTitle(), granted, balance);
    }

    @Transactional(readOnly = true)
    public List<UserGift> listRecent() {
        return gifts.findTop50ByOrderByIdDesc();
    }

    static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String clean(String value, int maxLen, String what) {
        String s = value == null ? "" : value.trim();
        if (s.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, what + "을(를) 적어 주세요");
        }
        if (s.length() > maxLen) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, what + "은(는) " + maxLen + "자 이하여야 합니다");
        }
        return s;
    }

    public record ClaimResult(Long id, String refKey, String title, int granted, int balance) {
    }
}
