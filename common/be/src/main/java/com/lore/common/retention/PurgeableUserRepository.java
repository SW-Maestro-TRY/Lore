package com.lore.common.retention;

import com.lore.common.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * 파기 때만 쓰는 질문들.
 *
 * ★ {@code UserRepository} 에 안 붙이고 따로 둔다 — 여기 있는 것은 전부
 *   <b>지우는</b> 문이다. 평범한 조회와 섞어 두면 자동완성으로 고른 한 줄이
 *   계정을 지우는 사고가 난다.
 */
public interface PurgeableUserRepository extends JpaRepository<User, Long> {

    /**
     * 지울 때가 된 계정.
     *
     * ★ {@code status} 가 아니라 {@code deletedAt} 으로 고른다. 표시가 둘로
     *   나뉘어 있어서(둘 다 {@code withdraw()} 가 한 번에 찍는다) 어느 쪽을
     *   봐도 같지만, <b>기준이 되는 것은 시각</b>이다 — 기간을 재려면 시각이
     *   있어야 하고, 시각이 없는 행은 애초에 지울 때를 정할 수 없다.
     */
    @Query("select u.id from User u where u.deletedAt is not null and u.deletedAt < :cut order by u.id")
    List<Long> idsToPurge(@Param("cut") Instant cut);

    @Modifying
    @Query("delete from UserAgreement a where a.user.id = :userId")
    int deleteAgreements(@Param("userId") Long userId);

    @Modifying
    @Query("delete from UserCredential c where c.user.id = :userId")
    int deleteCredentials(@Param("userId") Long userId);

    @Modifying
    @Query("delete from UserRefreshToken t where t.user.id = :userId")
    int deleteRefreshTokens(@Param("userId") Long userId);

    @Modifying
    @Query("delete from User u where u.id = :userId")
    int deleteUser(@Param("userId") Long userId);
}
