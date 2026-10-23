package com.lore.webtoon.lorebook;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface PrivateLorebookRepository extends JpaRepository<PrivateLorebook,String> {
 List<PrivateLorebook> findAllByOwnerIdOrderByUpdatedAtDesc(Long ownerId);
 Optional<PrivateLorebook> findByIdAndOwnerId(String id,Long ownerId);
}
