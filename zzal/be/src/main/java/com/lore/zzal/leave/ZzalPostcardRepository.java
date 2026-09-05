package com.lore.zzal.leave;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ZzalPostcardRepository extends JpaRepository<ZzalPostcard, Long> {

    /**
     * 쓴 순서대로. ★ {@code seq} 만으로 정렬하면 <b>두 번째 여행부터 순서가 섞인다</b> —
     * 여행이 끝나면 번호가 1부터 다시 시작하는데 옛 엽서는 그대로 남아 있기 때문이다(#235 리뷰 하-2).
     */
    List<ZzalPostcard> findByPetIdOrderByWrittenAtAscIdAsc(Long petId);
}
