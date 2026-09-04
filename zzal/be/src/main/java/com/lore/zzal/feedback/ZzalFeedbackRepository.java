package com.lore.zzal.feedback;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ZzalFeedbackRepository extends JpaRepository<ZzalFeedback, Long> {

    /**
     * 이 사람이 이 펫에 남긴 후기. 없으면 비어 있다.
     *
     * ★ 메서드가 하나뿐인 이유 — "이미 냈는가" 와 "무엇을 냈는가" 가 같은 질문이다.
     *   {@code existsBy…} 를 따로 두면 두 질문이 서로 다른 조건으로 갈라질 수 있고,
     *   그때는 화면이 "이미 냈다" 를 띄우면서 내용은 못 보여주는 상태가 된다.
     *
     * ★ 이 조회가 중복을 <b>막는 것은 아니다.</b> 두 요청이 동시에 들어오면 둘 다 "없다" 를
     *   통과한다. 진짜 방어는 (user_id, pet_id) 유니크 제약이고, 이 조회는 그 앞에서
     *   흔한 경우를 먼저 걸러 사용자에게 제대로 된 말을 돌려주기 위한 것이다
     *   (FeedbackService.submit 의 두 겹 방어 주석 참고).
     */
    Optional<ZzalFeedback> findByUserIdAndPetId(Long userId, Long petId);
}
