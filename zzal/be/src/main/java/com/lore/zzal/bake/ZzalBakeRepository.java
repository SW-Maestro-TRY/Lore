package com.lore.zzal.bake;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ZzalBakeRepository extends JpaRepository<ZzalBake, Long> {

    List<ZzalBake> findByPetId(Long petId);

    List<ZzalBake> findByStatus(BakeStatus status);

    /** 같은 동작을 두 번 굽지 않게 확인한다. */
    boolean existsByPetIdAndMotionKey(Long petId, String motionKey);
}
