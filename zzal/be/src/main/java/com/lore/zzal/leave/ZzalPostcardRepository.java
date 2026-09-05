package com.lore.zzal.leave;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ZzalPostcardRepository extends JpaRepository<ZzalPostcard, Long> {

    List<ZzalPostcard> findByPetIdOrderBySeqAsc(Long petId);
}
