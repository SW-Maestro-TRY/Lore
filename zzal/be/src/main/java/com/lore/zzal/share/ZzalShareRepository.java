package com.lore.zzal.share;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ZzalShareRepository extends JpaRepository<ZzalShare, Long> {

    Optional<ZzalShare> findByToken(String token);

    Optional<ZzalShare> findByPetIdAndMotionKey(Long petId, String motionKey);
}
