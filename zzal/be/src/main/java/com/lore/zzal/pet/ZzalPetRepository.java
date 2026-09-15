package com.lore.zzal.pet;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ZzalPetRepository extends JpaRepository<ZzalPet, Long> {

    List<ZzalPet> findByUserIdOrderByIdDesc(Long userId);

    /** 밤 스위프 — 함께 지내는 펫 전부(안 연 사람 것도 23:00 에 정산·큐 등록). */
    List<ZzalPet> findByPhase(PetPhase phase);

    /**
     * <b>끝난</b> 알을 최근 순으로. 부화 연속 실패 경보가 "연달아 몇 번인가" 를 이걸로 센다.
     *
     * ★★ 연속 실패를 메모리에 세지 않는 이유 — 그 숫자는 재시작하면 0 이 된다. 배포가 잦은 동안
     *    같은 사고가 여러 번 알려지거나(0부터 다시 세어 다시 임계를 넘음) 영영 안 알려진다.
     *    표를 다시 읽어 세면 서버가 몇 번을 뜨든 같은 답이 나온다.
     * ★ 굽는 중(DRAFT·HATCHING)은 빼고 부르는 쪽이 ALIVE·FAILED 만 준다 — 아직 성공도 실패도
     *   아닌 것이 사이에 끼면 "연달아" 가 끊긴 것처럼 보인다.
     * ★ 순서는 id 다. 만들어진 순서라 끝난 순서와 아주 조금 어긋날 수 있지만, 언제나 값이 있고
     *   절대 안 바뀌는 유일한 축이다(끝난 시각은 실패 경로에 따라 비어 있을 수 있다).
     */
    List<ZzalPet> findByPhaseInOrderByIdDesc(Collection<PetPhase> phases,
                                             org.springframework.data.domain.Pageable pageable);

    /** 지금 부화 중인 펫이 있는가. 있으면 새로 만들지 못한다("○○이가 부화 중이에요"). */
    Optional<ZzalPet> findFirstByUserIdAndPhase(Long userId, PetPhase phase);

    /**
     * 지금 <b>자리를 차지하고 있는</b> 펫 수 — 칸 수(user.petSlots)와 비교하는 자리.
     *
     * ★★ "FAILED 만 빼고 센다" 가 아니라 <b>세고 싶은 단계를 직접 준다</b>. 자리를 차지하는 것은
     *    HATCHING(굽는 중이라 결과를 받아야 함)과 ALIVE(함께 지내는 중) 뿐이다.
     * <p>
     * 전에는 {@code countByUserIdAndPhaseNot(userId, FAILED)} 였는데, 그러면 <b>DEAD 까지 세어</b>
     * 펫을 보내도(놓아주기) 자리가 안 비고 새로 시작할 수 없었다. 부정형("무엇이 아닌 것")으로
     * 세면 단계가 하나 늘 때마다 그 값이 조용히 자리를 먹는다 — 예외도 로그도 없이
     * "자리 없음" 만 뜬다. 그래서 긍정형(무엇을 세는가)으로 뒤집었다.
     * <p>
     * 떠난 아이(DEAD)와 태어나지 못한 알(FAILED)의 행은 지우지 않고 그대로 남긴다.
     * 이미 돈을 써서 구운 결과물이고, 재회 기능이 붙을 자리이기 때문이다.
     */
    long countByUserIdAndPhaseIn(Long userId, Collection<PetPhase> phases);

    /**
     * 오래 멈춰 있는 알. 서버가 재시작되면 메모리에서 돌던 부화가 사라지므로,
     * 다시 뜰 때 이걸로 찾아 이어서 굽는다(StuckHatchRecovery).
     */
    List<ZzalPet> findByPhaseAndHatchStartedAtBefore(PetPhase phase, java.time.Instant before);

    /**
     * 멈춘 알 복구용 — 여러 단계를 한 번에 집는다.
     *
     * ★ 이름을 받기 전(DRAFT)에도 굽는다(1.8 — 그림을 올리는 순간 시작). 그래서
     *   HATCHING 만 집으면 <b>이름 짓는 동안 서버가 죽은 사람</b>이 복구에서 빠진다.
     */
    List<ZzalPet> findByPhaseInAndHatchStartedAtBefore(java.util.Collection<PetPhase> phases,
                                                       java.time.Instant before);

    /**
     * 이 펫을 <b>잠그고</b> 꺼낸다.
     *
     * ★ 한 펫에 대해 "동시에 하나만" 이어야 하는 일을 시작할 때 쓴다. 검사와 저장 사이에
     *   다른 요청이 끼어들면 두 요청이 <b>둘 다 검사를 통과</b>해 두 개가 만들어진다
     *   (예: 놀이 시작 버튼을 빠르게 두 번 누르면 판이 둘 생기고 하루 횟수도 두 번 먹는다).
     *   펫을 잠그면 그 사이가 없어진다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ZzalPet p where p.id = :id")
    Optional<ZzalPet> findByIdForUpdate(@Param("id") Long id);
}
