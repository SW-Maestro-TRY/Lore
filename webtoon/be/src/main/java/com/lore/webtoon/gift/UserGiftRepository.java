package com.lore.webtoon.gift;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserGiftRepository extends JpaRepository<UserGift, Long> {

    Optional<UserGift> findByRefKey(String refKey);

    List<UserGift> findByEmailKeyAndClaimedAtIsNullOrderByIdAsc(String emailKey);

    List<UserGift> findTop50ByOrderByIdDesc();
}
