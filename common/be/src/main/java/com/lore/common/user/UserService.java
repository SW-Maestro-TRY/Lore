package com.lore.common.user;

import com.lore.common.auth.token.RefreshTokenService;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** 계정 조회·탈퇴·동의 기록. */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final UserAgreementRepository agreementRepository;
    private final RefreshTokenService refreshTokenService;

    public UserService(UserRepository userRepository,
                       UserAgreementRepository agreementRepository,
                       RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.agreementRepository = agreementRepository;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional(readOnly = true)
    public User get(Long userId) {
        return userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    /**
     * 탈퇴. 표시만 남기고 30일 뒤에 실제로 지운다(2026-09-01 결정).
     *
     * 토큰은 즉시 전부 폐기한다 — JWT 는 취소가 안 되지만, refresh 를 끊으면
     * 새 access 를 못 받으므로 최대 30분 뒤 완전히 끊긴다.
     */
    @Transactional
    public void withdraw(Long userId, Instant now) {
        User user = get(userId);
        user.withdraw(now);
        refreshTokenService.revokeAll(user, now);
    }

    @Transactional(readOnly = true)
    public List<UserAgreement> agreements(Long userId) {
        return agreementRepository.findByUserOrderByAgreedAtDesc(get(userId));
    }

    /** 약관이 개정됐을 때 새 판에 다시 동의를 받는 자리. */
    @Transactional
    public void agree(Long userId, AgreementType type, String version, boolean agreed, Instant now) {
        agreementRepository.save(UserAgreement.of(get(userId), type, version, agreed, now));
    }
}
