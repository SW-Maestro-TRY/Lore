package com.lore.webtoon;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BrowserLinkRepository extends JpaRepository<BrowserLink, Long> {

    List<BrowserLink> findByUserId(Long userId);

    boolean existsByUserIdAndBrowserUid(Long userId, String browserUid);
}
