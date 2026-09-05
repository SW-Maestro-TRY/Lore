package com.lore.common.auth.token;

import com.lore.common.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface UserRefreshTokenRepository extends JpaRepository<UserRefreshToken, Long> {

    Optional<UserRefreshToken> findByTokenHash(String tokenHash);

    /**
     * 이 사람의 살아 있는 토큰을 전부 폐기한다.
     *
     * 쓰는 곳 둘 — (1) 이미 폐기된 토큰이 다시 들어왔을 때(탈취 신호) (2) 탈퇴할 때.
     * 한 줄씩 불러와 고치면 기기 수만큼 쿼리가 나가므로 한 번에 처리한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("update UserRefreshToken t set t.revokedAt = :now where t.user = :user and t.revokedAt is null")
    int revokeAllByUser(@Param("user") User user, @Param("now") Instant now);
}
