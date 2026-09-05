package com.lore.common.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserCredentialRepository extends JpaRepository<UserCredential, Long> {

    Optional<UserCredential> findByUserAndProvider(User user, AuthProvider provider);

    /** 소셜 로그인이 붙었을 때 그쪽 번호로 사람을 찾는 자리. 지금은 안 쓰지만 구조를 미리 맞춰 둔다. */
    Optional<UserCredential> findByProviderAndProviderUserId(AuthProvider provider, String providerUserId);
}
