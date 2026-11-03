package com.lore.webtoon.invite;

import com.lore.common.credit.CreditDomain;
import com.lore.common.credit.CreditReason;
import com.lore.common.credit.CreditService;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import com.lore.webtoon.credit.BrowserLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 친구 초대 보상 규칙.
 *
 * 저장소는 목록으로 흉내 내고, 크레딧 장부는 refId 로 한 번만 들어가는 것만 흉내 낸다.
 * 보고 싶은 것은 「누가 언제 얼마를 받나」와 「무엇이 막히나」다.
 */
class InviteServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-11-03T11:00:00Z");
    private static final long INVITER = 1L;
    private static final long FRIEND = 2L;
    private static final String CODE = "K7PQ2XHM";

    private final List<Invite> rows = new ArrayList<>();
    private final Set<String> granted = new HashSet<>();

    private InviteCodeRepository codes;
    private InviteRepository invites;
    private BrowserLinkRepository links;
    private UserRepository users;
    private CreditService credits;
    private InviteService service;

    @BeforeEach
    void 저장소를_세운다() {
        codes = mock(InviteCodeRepository.class);
        when(codes.findByCode(CODE)).thenReturn(Optional.of(new InviteCode(INVITER, CODE, NOW)));
        when(codes.findById(INVITER)).thenReturn(Optional.of(new InviteCode(INVITER, CODE, NOW)));

        invites = mock(InviteRepository.class);
        when(invites.save(any())).thenAnswer(c -> {
            Invite i = c.getArgument(0);
            if (!rows.contains(i)) rows.add(i);
            return i;
        });
        when(invites.findByInviteeId(anyLong())).thenAnswer(c -> rows.stream()
                .filter(i -> i.getInviteeId().equals(c.<Long>getArgument(0))).findFirst());
        when(invites.countByInviterId(anyLong())).thenAnswer(c -> rows.stream()
                .filter(i -> i.getInviterId().equals(c.<Long>getArgument(0))).count());
        when(invites.countByInviterIdAndCompletedAtIsNotNull(anyLong())).thenAnswer(c -> rows.stream()
                .filter(i -> i.getInviterId().equals(c.<Long>getArgument(0)) && i.getCompletedAt() != null).count());
        when(invites.countByInviterIdAndSignupRewardedTrueAndSignedUpAtGreaterThanEqual(anyLong(), any()))
                .thenAnswer(c -> rows.stream()
                        .filter(i -> i.getInviterId().equals(c.<Long>getArgument(0)) && i.isSignupRewarded()
                                && !i.getSignedUpAt().isBefore(c.getArgument(1))).count());
        when(invites.countByInviterIdAndCompleteRewardedTrue(anyLong())).thenAnswer(c -> rows.stream()
                .filter(i -> i.getInviterId().equals(c.<Long>getArgument(0)) && i.isCompleteRewarded()).count());

        links = mock(BrowserLinkRepository.class);
        users = mock(UserRepository.class);
        madeAt(FRIEND, NOW.minus(Duration.ofMinutes(1)));

        credits = mock(CreditService.class);
        when(credits.grantOnce(anyLong(), anyInt(), any(), any(), anyString(), anyString()))
                .thenAnswer(c -> granted.add(c.getArgument(4)) ? c.<Integer>getArgument(1) : 0);

        service = new InviteService(codes, invites, links, users, credits,
                10, 10, 10, Duration.ofMinutes(30), Clock.fixed(NOW, ZONE));
    }

    /** 가입 시각만 있는 사람. 스텁 안에서 또 스텁하면 Mockito 가 막으므로 먼저 만들고 넣는다. */
    private void madeAt(long userId, Instant at) {
        User u = mock(User.class);
        when(u.getCreatedAt()).thenReturn(at);
        when(users.findById(userId)).thenReturn(Optional.of(u));
    }

    @Test
    @DisplayName("초대 링크로 들어와 가입하면 초대한 사람에게 10C")
    void signupRewardsInviter() {
        assertThat(service.claim(FRIEND, CODE, "uid-friend")).isEqualTo(InviteService.Claim.REWARDED);

        verify(credits).grantOnce(eq(INVITER), eq(10), eq(CreditReason.REWARD), eq(CreditDomain.WEBTOON),
                eq("invite-signup-2"), eq(InviteService.MEMO));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isSignupRewarded()).isTrue();
    }

    @Test
    @DisplayName("그 친구가 웹툰을 처음 완성하면 10C 더 — 두 번째 완성은 아무 일도 없다")
    void firstCompletionRewardsOnce() {
        service.claim(FRIEND, CODE, "uid-friend");

        assertThat(service.onCompleted(FRIEND)).isTrue();
        assertThat(service.onCompleted(FRIEND)).isFalse();

        verify(credits).grantOnce(eq(INVITER), eq(10), eq(CreditReason.REWARD), eq(CreditDomain.WEBTOON),
                eq("invite-complete-2"), eq(InviteService.MEMO));
        assertThat(service.summary(INVITER).earned()).isEqualTo(20);
        assertThat(service.summary(INVITER).completed()).isEqualTo(1);
    }

    @Test
    @DisplayName("초대받지 않은 사람의 완성은 아무에게도 안 준다")
    void completionWithoutInviteIsNoop() {
        assertThat(service.onCompleted(99L)).isFalse();
        verify(credits, never()).grantOnce(anyLong(), anyInt(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("내 코드로 내가 가입한 것은 안 적는다")
    void selfInviteIsRejected() {
        madeAt(INVITER, NOW);
        assertThat(service.claim(INVITER, CODE, "uid-x")).isEqualTo(InviteService.Claim.SELF);
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("초대한 사람의 기기에서 만든 계정은 안 적는다")
    void sameDeviceIsRejected() {
        when(links.existsByUserIdAndBrowserUid(INVITER, "uid-inviter")).thenReturn(true);
        assertThat(service.claim(FRIEND, CODE, "uid-inviter")).isEqualTo(InviteService.Claim.SAME_DEVICE);
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("옛 계정으로 로그인한 것은 새 가입이 아니다")
    void oldAccountIsNotNew() {
        madeAt(FRIEND, NOW.minus(Duration.ofDays(3)));
        assertThat(service.claim(FRIEND, CODE, "uid-friend")).isEqualTo(InviteService.Claim.NOT_NEW);
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("같은 사람이 두 번 보내도 한 줄·한 번만")
    void secondClaimIsIgnored() {
        service.claim(FRIEND, CODE, "uid-friend");
        assertThat(service.claim(FRIEND, CODE, "uid-friend")).isEqualTo(InviteService.Claim.ALREADY);
        assertThat(rows).hasSize(1);
        assertThat(granted).hasSize(1);
    }

    @Test
    @DisplayName("한 달 상한(10명)을 넘기면 줄은 남고 보상은 없다 — 완성 보상도 같이 없다")
    void monthlyCapStopsRewards() {
        for (long friend = 10; friend < 20; friend++) {
            madeAt(friend, NOW);
            assertThat(service.claim(friend, CODE, "uid-" + friend)).isEqualTo(InviteService.Claim.REWARDED);
        }
        madeAt(20L, NOW);

        assertThat(service.claim(20L, CODE, "uid-20")).isEqualTo(InviteService.Claim.CAPPED);
        assertThat(service.onCompleted(20L)).isFalse();

        assertThat(rows).hasSize(11);
        assertThat(granted).hasSize(10);
        assertThat(service.summary(INVITER).joined()).isEqualTo(11);
        assertThat(service.summary(INVITER).monthlyLeft()).isZero();
    }

    @Test
    @DisplayName("모르는 코드·빈 코드는 아무것도 안 한다")
    void unknownCode() {
        assertThat(service.claim(FRIEND, "nope", "uid")).isEqualTo(InviteService.Claim.UNKNOWN_CODE);
        assertThat(service.claim(FRIEND, " ", "uid")).isEqualTo(InviteService.Claim.NO_CODE);
        assertThat(rows).isEmpty();
    }

    @Test
    @DisplayName("코드는 사람마다 하나 — 없으면 만들고, 있으면 그대로")
    void codeIsStable() {
        when(codes.findById(5L)).thenReturn(Optional.empty());
        when(codes.findByCode(anyString())).thenReturn(Optional.empty());
        when(codes.save(any())).thenAnswer(c -> c.getArgument(0));

        String made = service.codeOf(5L);
        ArgumentCaptor<InviteCode> saved = ArgumentCaptor.captor();
        verify(codes).save(saved.capture());
        assertThat(made).hasSize(8).matches("[A-HJ-NP-Z2-9]{8}");
        assertThat(saved.getValue().getUserId()).isEqualTo(5L);
        assertThat(service.codeOf(INVITER)).isEqualTo(CODE);
    }
}
