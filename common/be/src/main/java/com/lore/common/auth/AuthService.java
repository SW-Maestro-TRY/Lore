package com.lore.common.auth;

import com.lore.common.auth.jwt.JwtProvider;
import com.lore.common.auth.token.RefreshTokenService;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.user.AgreementType;
import com.lore.common.user.AuthProvider;
import com.lore.common.user.User;
import com.lore.common.user.UserAgreement;
import com.lore.common.user.UserAgreementRepository;
import com.lore.common.user.UserCredential;
import com.lore.common.user.UserCredentialRepository;
import com.lore.common.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * 가입·로그인·토큰 갱신·로그아웃.
 *
 * ★ 가입은 계정·수단·동의 세 표에 한꺼번에 쓴다. 중간에 실패하면 통째로 되돌아간다
 *   (@Transactional). 계정만 생기고 비밀번호가 없는 상태가 남으면 그 사람은
 *   가입도 로그인도 못 하는 유령이 된다.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final UserCredentialRepository credentialRepository;
    private final UserAgreementRepository agreementRepository;
    private final RefreshTokenService refreshTokenService;
    private final JwtProvider jwtProvider;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository,
                       UserCredentialRepository credentialRepository,
                       UserAgreementRepository agreementRepository,
                       RefreshTokenService refreshTokenService,
                       JwtProvider jwtProvider,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.agreementRepository = agreementRepository;
        this.refreshTokenService = refreshTokenService;
        this.jwtProvider = jwtProvider;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 회원가입. 필수 약관에 동의하지 않으면 성립하지 않는다.
     *
     * @param agreements 항목별 동의 여부. 선택 항목(마케팅)은 false 도 기록으로 남긴다 —
     *                   "안 물어본 것"과 "거부한 것"은 다르기 때문이다.
     */
    @Transactional
    public Tokens signUp(String email, String rawPassword, Map<AgreementType, Boolean> agreements,
                         String termsVersion, String userAgent, Instant now) {
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        if (!Boolean.TRUE.equals(agreements.get(AgreementType.TERMS))
                || !Boolean.TRUE.equals(agreements.get(AgreementType.PRIVACY))) {
            throw new BusinessException(ErrorCode.REQUIRED_AGREEMENT_MISSING);
        }

        User user = userRepository.save(User.signUp(email));
        credentialRepository.save(UserCredential.local(user, passwordEncoder.encode(rawPassword)));
        agreements.forEach((type, agreed) ->
                agreementRepository.save(UserAgreement.of(user, type, termsVersion, Boolean.TRUE.equals(agreed), now)));

        return issueTokens(user, userAgent, now);
    }

    /**
     * 로그인.
     *
     * ★ 실패 사유를 나누지 않는다. "그런 이메일 없음"과 "비밀번호 틀림"을 구분해 주면
     *   어떤 이메일이 가입돼 있는지 확인하는 수단이 된다.
     */
    @Transactional
    public Tokens login(String email, String rawPassword, String userAgent, Instant now) {
        User user = userRepository.findByEmail(email)
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED));

        UserCredential credential = credentialRepository.findByUserAndProvider(user, AuthProvider.LOCAL)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED));

        if (!passwordEncoder.matches(rawPassword, credential.getPasswordHash())) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        return issueTokens(user, userAgent, now);
    }

    /**
     * access 재발급. refresh 는 회전되어 새 것으로 바뀐다.
     *
     * ★ 쿠키가 아예 없는 경우를 먼저 막는다 — 비로그인 상태에서 화면이 401 을 받고
     *   갱신을 시도하면 여기로 들어오는데, 그대로 두면 해시 계산에서 NPE 가 나
     *   <b>401 이어야 할 것이 500</b> 이 된다. 그러면 화면은 "로그인이 풀렸다" 와
     *   "서버가 터졌다" 를 구분할 수 없다.
     */
    @Transactional
    public Tokens refresh(String rawRefreshToken, String userAgent, Instant now) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        RefreshTokenService.Rotated rotated = refreshTokenService.rotate(rawRefreshToken, userAgent, now);
        return new Tokens(
                jwtProvider.createAccessToken(rotated.user().getId(), now),
                rotated.rawToken());
    }

    /** 로그아웃. 이 기기의 refresh 만 폐기한다(다른 기기는 유지). */
    @Transactional
    public void logout(String rawRefreshToken, Instant now) {
        if (rawRefreshToken != null) {
            refreshTokenService.revoke(rawRefreshToken, now);
        }
    }

    private Tokens issueTokens(User user, String userAgent, Instant now) {
        return new Tokens(
                jwtProvider.createAccessToken(user.getId(), now),
                refreshTokenService.issue(user, userAgent, now));
    }

    /** 발급 결과. 컨트롤러가 이걸 쿠키로 바꿔 내보낸다. */
    public record Tokens(String accessToken, String refreshToken) {}
}
