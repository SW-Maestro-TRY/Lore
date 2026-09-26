package com.lore.zzal.wish;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface ZzalMotionWishRepository extends JpaRepository<ZzalMotionWish, Long> {

    /**
     * 이 아이에게 {@code from} 이후로 들어온 요청 수. 하루 상한을 세는 한 가지 질문이다.
     *
     * ★ 펫 기준으로 센다 — 상한의 목적이 "한 화면이 눌린 채 굴러가는 것" 을 막는 것이라,
     *   그 화면이 붙어 있는 대상(펫)으로 세는 것이 맞다. 여러 아이를 키우는 사람이
     *   아이마다 스무 줄을 쓸 수는 있지만, 그건 사람이 실제로 그만큼 바란 것이다.
     *
     * ★ 조회 조건이 마이그레이션의 (pet_id, created_at) 색인과 같은 순서다 — 줄이 쌓여도
     *   이 셈이 표를 통째로 훑지 않는다.
     */
    long countByPetIdAndCreatedAtGreaterThanEqual(Long petId, Instant from);
}
