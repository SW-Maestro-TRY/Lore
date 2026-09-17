package com.lore.common.auth.token;

import com.lore.common.auth.jwt.JwtProvider;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * refresh 토큰 <b>절대 만료 상한</b> 규칙.
 *
 * <p>슬라이딩 만료(회전할 때마다 +14일)만 있으면 매일 쓰는 사용자는 영원히 로그인 상태가 된다.
 * 여기서 확인하는 것은 그 위에 얹은 상한이 실제로 동작하는가다 —
 * (1) 로그인은 상한을 새로 찍고, (2) 회전은 그 상한을 물려받기만 하며(다시 90일을 주지 않고),
 * (3) 상한이 지난 토큰의 회전은 <b>거부</b>되고(재로그인), (4) 그 거부가 탈취(재사용) 대응과
 * 뒤섞이지 않는지.</p>
 *
 * <p>저장소는 목록 하나로 흉내 낸다 — JPA 가 아니라 "무엇을 어떤 만료로 저장하는가" 를 본다.
 * 원문→해시는 실제 서비스 코드가 하므로, 테스트는 해시를 모른 채 tokenHash 로만 조회를 흉내 낸다.</p>
 */
@DisplayName("refresh 토큰 — 절대 만료 상한")
class RefreshTokenServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-16T00:00:00Z");
    private static final Duration REFRESH = Duration.ofDays(14);
    private static final Duration ABSOLUTE = Duration.ofDays(90);

    private final List<UserRefreshToken> rows = new ArrayList<>();

    private UserRefreshTokenRepository repository;
    private RefreshTokenRevoker revoker;
    private JwtProvider jwtProvider;
    private RefreshTokenService service;
    private User user;

    @BeforeEach
    void setUp() {
        repository = mock(UserRefreshTokenRepository.class);
        revoker = mock(RefreshTokenRevoker.class);
        jwtProvider = mock(JwtProvider.class);
        user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(jwtProvider.refreshExpiry()).thenReturn(REFRESH);
        when(jwtProvider.refreshAbsoluteExpiry()).thenReturn(ABSOLUTE);

        when(repository.save(any(UserRefreshToken.class))).thenAnswer(c -> {
            UserRefreshToken t = c.getArgument(0);
            rows.add(t);
            return t;
        });
        when(repository.findByTokenHash(any())).thenAnswer(c -> {
            String hash = c.getArgument(0);
            if (hash == null) {
                return Optional.empty();
            }
            return rows.stream().filter(t -> hash.equals(t.getTokenHash())).findFirst();
        });

        service = new RefreshTokenService(repository, revoker, jwtProvider);
    }

    private UserRefreshToken lastSaved() {
        return rows.get(rows.size() - 1);
    }

    @Test
    @DisplayName("로그인은 상한을 now+90일로 새로 찍고, expiresAt 은 now+14일이다")
    void loginStampsAbsolute() {
        service.issue(user, "agent", T0);

        UserRefreshToken saved = lastSaved();
        assertThat(saved.getAbsoluteExpiresAt()).isEqualTo(T0.plus(ABSOLUTE));
        assertThat(saved.getExpiresAt()).isEqualTo(T0.plus(REFRESH)); // min(14d, 90d) = 14d
    }

    @Test
    @DisplayName("회전은 옛 상한을 물려받고(다시 90일 주지 않고), 상한 근처면 expiresAt 이 상한으로 깎인다")
    void rotationInheritsAbsoluteAndCaps() {
        // 여러 번 회전해 상한(T0+90d) 가까이 온 '현재 토큰'을 직접 심는다 —
        // 아직 만료 전(expiresAt 미래)이지만 상한까지 5일밖에 안 남았다.
        Instant absolute = T0.plus(ABSOLUTE);
        Instant now = absolute.minus(Duration.ofDays(5));       // 상한 5일 전
        UserRefreshToken current = UserRefreshToken.issue(
                user, "hash-current", now.plus(REFRESH) /* 미래 */, absolute, "agent", now.minus(REFRESH));
        rows.add(current);
        doReturn(Optional.of(current)).when(repository).findByTokenHash(any());

        RefreshTokenService.Rotated rotated = service.rotate("any-raw", "agent", now);

        UserRefreshToken rolled = lastSaved();
        // 물려받음 — now+90d 가 아니라 여전히 T0+90d
        assertThat(rolled.getAbsoluteExpiresAt()).isEqualTo(absolute);
        // 슬라이딩(now+14d)이 상한(now+5d)을 넘으므로 상한으로 깎인다
        assertThat(rolled.getExpiresAt()).isEqualTo(absolute);
        assertThat(rotated.user()).isSameAs(user);
    }

    @Test
    @DisplayName("상한이 아직 멀면 회전 expiresAt 은 슬라이딩(now+14일) 그대로다")
    void rotationUsesSlidingWhenFarFromAbsolute() {
        String raw = service.issue(user, "agent", T0);          // 상한 = T0+90d
        Instant t1 = T0.plus(Duration.ofDays(10));              // 상한까지 넉넉

        service.rotate(raw, "agent", t1);

        UserRefreshToken rolled = lastSaved();
        assertThat(rolled.getExpiresAt()).isEqualTo(t1.plus(REFRESH)); // t1+14d < T0+90d
        assertThat(rolled.getAbsoluteExpiresAt()).isEqualTo(T0.plus(ABSOLUTE));
    }

    @Test
    @DisplayName("★ 실패 경로 — 상한이 지난 토큰의 회전은 거부되고(재로그인), 탈취 대응(전체 폐기)은 일어나지 않는다")
    void rotationRejectedWhenAbsolutePassed() {
        // 슬라이딩 만료(expiresAt)는 아직 미래지만 절대 상한만 과거인 토큰을 심는다 —
        // 거부의 근거가 오직 절대 상한임을 분리해 확인한다.
        Instant now = T0.plus(Duration.ofDays(100));
        UserRefreshToken past = UserRefreshToken.issue(
                user, "hash-absolute-passed",
                now.plus(REFRESH),          // expiresAt: 미래
                T0.plus(ABSOLUTE),          // absoluteExpiresAt: 과거(T0+90d < now(T0+100d))
                "agent", T0);
        rows.add(past);

        // rotate 는 원문을 해시해 조회하므로, 이 테스트에서는 서비스가 만드는 해시와 무관하게
        // findByTokenHash 를 직접 흉내 내 심은 토큰을 돌려주게 한다.
        doReturn(Optional.of(past)).when(repository).findByTokenHash(any());

        assertThatThrownBy(() -> service.rotate("any-raw", "agent", now))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);

        // 상한 초과는 정상 만료지 탈취가 아니다 — 전체 폐기를 부르면 안 된다.
        verify(revoker, never()).revokeAll(any(), any());
    }

    @Test
    @DisplayName("이미 폐기된 토큰 재사용은 여전히 탈취로 보고 전체 폐기한다 — 상한 초과와 구분된다")
    void reuseOfRevokedStillTriggersRevokeAll() {
        UserRefreshToken revoked = UserRefreshToken.issue(
                user, "hash-revoked",
                T0.plus(REFRESH), T0.plus(ABSOLUTE), "agent", T0);
        revoked.revoke(T0.plus(Duration.ofDays(1)));
        doReturn(Optional.of(revoked)).when(repository).findByTokenHash(any());

        assertThatThrownBy(() -> service.rotate("any-raw", "agent", T0.plus(Duration.ofDays(2))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);

        verify(revoker, times(1)).revokeAll(eq(user), any());
    }

    @Test
    @DisplayName("레거시 토큰(상한 null)의 회전은 깨지지 않는다 — 상한 없이 슬라이딩만, 새 토큰도 null")
    void legacyNullAbsoluteRotatesLeniently() {
        UserRefreshToken legacy = UserRefreshToken.issue(
                user, "hash-legacy",
                T0.plus(REFRESH), null /* 상한 없음 */, "agent", T0);
        rows.add(legacy);
        doReturn(Optional.of(legacy)).when(repository).findByTokenHash(any());

        Instant t1 = T0.plus(Duration.ofDays(5));
        RefreshTokenService.Rotated rotated = service.rotate("hash-legacy", "agent", t1);

        UserRefreshToken rolled = lastSaved();
        assertThat(rolled.getAbsoluteExpiresAt()).isNull();          // 여전히 상한 없음
        assertThat(rolled.getExpiresAt()).isEqualTo(t1.plus(REFRESH)); // 슬라이딩만
        assertThat(rotated.user()).isSameAs(user);
    }

    @Test
    @DisplayName("레거시 상한 null 토큰은 isUsable 이 상한 없이 통과된다")
    void isUsablePassesWhenAbsoluteNull() {
        UserRefreshToken legacy = UserRefreshToken.issue(
                user, "h", T0.plus(REFRESH), null, "agent", T0);
        assertThat(legacy.isUsable(T0.plus(Duration.ofDays(1)))).isTrue();
    }

    @Test
    @DisplayName("상한이 지나면 expiresAt 이 미래여도 isUsable 이 false 다(이중 방어)")
    void isUsableFalseWhenAbsolutePassedEvenIfExpiresFuture() {
        Instant now = T0.plus(Duration.ofDays(100));
        UserRefreshToken t = UserRefreshToken.issue(
                user, "h", now.plus(REFRESH) /* 미래 */, T0.plus(ABSOLUTE) /* 과거 */, "agent", T0);
        assertThat(t.isUsable(now)).isFalse();
    }
}
