package com.lore.zzal.scene;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ZzalSceneRepository extends JpaRepository<ZzalScene, Long> {

    /** 최근 것부터. 화면은 맨 앞 하나만 쓰고, 앨범은 셋을 다 쓴다. */
    List<ZzalScene> findByPetIdOrderBySceneAtDescIdDesc(Long petId);

    long countByPetId(Long petId);
}
