package com.lore.common.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserAgreementRepository extends JpaRepository<UserAgreement, Long> {

    List<UserAgreement> findByUserOrderByAgreedAtDesc(User user);
}
