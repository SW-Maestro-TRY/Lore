package com.lore.webtoon.gift;

import com.lore.common.credit.CreditDomain;
import com.lore.common.credit.CreditReason;
import com.lore.common.credit.CreditService;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserGiftServiceTest {

    private final UserGiftRepository gifts = mock(UserGiftRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final CreditService credits = mock(CreditService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-23T08:00:00Z"), ZoneOffset.UTC);
    private final UserGiftService service = new UserGiftService(gifts, users, credits, clock);

    @Test
    void claimGrantsOnceForMatchingEmailAndIgnoresRepeat() {
        User target = mock(User.class);
        when(target.getEmail()).thenReturn("User@Example.com");
        when(users.findById(42L)).thenReturn(Optional.of(target));

        UserGift gift = new UserGift(
                "incident-20261123", "user@example.com",
                "잠시 오류가 있었습니다", "죄송합니다", 30, 1L, Instant.parse("2026-11-23T07:00:00Z"));
        when(gifts.findById(7L)).thenReturn(Optional.of(gift));
        when(gifts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(credits.grantOnce(eq(42L), eq(30), eq(CreditReason.REWARD), eq(CreditDomain.WEBTOON),
                eq("gift:incident-20261123"), eq("잠시 오류가 있었습니다"))).thenReturn(30);
        when(credits.balance(42L)).thenReturn(80);

        UserGiftService.ClaimResult first = service.claim(42L, 7L);
        assertThat(first.granted()).isEqualTo(30);
        assertThat(first.balance()).isEqualTo(80);
        assertThat(gift.getClaimedBy()).isEqualTo(42L);
        assertThat(gift.getClaimedAt()).isEqualTo(Instant.parse("2026-11-23T08:00:00Z"));

        // 이미 수령한 뒤 다시 호출하면 중복 지급하지 않고 0 을 돌려준다.
        UserGiftService.ClaimResult second = service.claim(42L, 7L);
        assertThat(second.granted()).isZero();
        assertThat(second.balance()).isEqualTo(80);
        verify(credits).grantOnce(42L, 30, CreditReason.REWARD, CreditDomain.WEBTOON,
                "gift:incident-20261123", "잠시 오류가 있었습니다");
    }

    @Test
    void claimRejectsDifferentUserEmail() {
        User other = mock(User.class);
        when(other.getEmail()).thenReturn("other@example.com");
        when(users.findById(99L)).thenReturn(Optional.of(other));

        UserGift gift = new UserGift(
                "incident-20261123", "user@example.com",
                "사과", "죄송합니다", 30, 1L, Instant.parse("2026-11-23T07:00:00Z"));
        when(gifts.findById(7L)).thenReturn(Optional.of(gift));

        assertThatThrownBy(() -> service.claim(99L, 7L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(credits, never()).grantOnce(any(), eq(30), any(), any(), any(), any());
    }

    @Test
    void pendingReturnsNormalizedEmailMatchesOnly() {
        User target = mock(User.class);
        when(target.getEmail()).thenReturn("  User@Example.com ");
        when(users.findById(42L)).thenReturn(Optional.of(target));

        UserGift gift = new UserGift(
                "incident-20261123", "user@example.com",
                "사과", "죄송합니다", 30, 1L, Instant.parse("2026-11-23T07:00:00Z"));
        when(gifts.findByEmailKeyAndClaimedAtIsNullOrderByIdAsc("user@example.com"))
                .thenReturn(List.of(gift));

        assertThat(service.pendingFor(42L)).containsExactly(gift);
        assertThat(service.pendingFor(null)).isEmpty();
    }
}
