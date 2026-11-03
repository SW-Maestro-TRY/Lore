package com.lore.webtoon.invite;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface InviteRepository extends JpaRepository<Invite, Long> {

    Optional<Invite> findByInviteeId(Long inviteeId);

    long countByInviterId(Long inviterId);

    long countByInviterIdAndCompletedAtIsNotNull(Long inviterId);

    /** 이번 달에 가입 보상을 준 사람 수 — 한 달 상한을 셀 때. */
    long countByInviterIdAndSignupRewardedTrueAndSignedUpAtGreaterThanEqual(Long inviterId, Instant since);

    long countByInviterIdAndCompleteRewardedTrue(Long inviterId);
}
