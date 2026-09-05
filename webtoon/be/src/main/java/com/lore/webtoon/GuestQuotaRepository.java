package com.lore.webtoon;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface GuestQuotaRepository extends JpaRepository<GuestQuota, Long> {

    Optional<GuestQuota> findByIpHashAndDay(String ipHash, LocalDate day);
}
