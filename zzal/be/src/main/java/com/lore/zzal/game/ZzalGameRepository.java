package com.lore.zzal.game;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ZzalGameRepository extends JpaRepository<ZzalGame, Long> {

    /**
     * 이 펫에서 <b>아직 안 끝난</b> 판. 새로고침 복구가 이걸로 돈다.
     *
     * ★ 다섯 왕복짜리 놀이라 중간에 새로고침이 반드시 일어난다. 이게 없으면 사용자는
     *   진행 중인 판을 다시 못 잡고, 그 판은 영원히 안 끝난 채 남아 하루 횟수만 먹는다.
     * ★ userId 가 아니라 petId 로 찾고 소유권은 펫에서 판정한다 — 판 자체에도 userId 가
     *   있지만, "남의 펫 번호로 물어보면 404" 라는 판정을 펫 API 와 <b>같은 자리</b>에서
     *   하지 않으면 두 곳의 규칙이 갈라진다.
     * ★ 여러 개가 남아 있을 리 없지만(시작할 때 진행 중인 판을 먼저 돌려준다) 동시에 두 번
     *   눌리는 경우가 있으므로 First…OrderByIdDesc 로 하나만 집는다.
     */
    Optional<ZzalGame> findFirstByPetIdAndFinishedAtIsNullOrderByIdDesc(Long petId);

    /**
     * 오늘 시작한 판 수 — 하루 제한이 세는 값.
     *
     * ★ "끝낸 판" 이 아니라 <b>"시작한 판"</b> 을 센다. 끝낸 것만 세면 지고 있는 판을
     *   버리고 새로 시작하는 것이 공짜가 되어, 제한이 있으나 마나가 된다.
     * ★ 펫이 아니라 사람(userId) 기준이다. 펫 기준이면 펫을 새로 만들어 초기화할 수 있다.
     */
    long countByUserIdAndStartedAtGreaterThanEqual(Long userId, Instant from);

    /**
     * 한 판을 <b>잠그고</b> 꺼낸다. 칠 때 쓴다.
     *
     * ★★ 왜 잠그는가 — 사용자가 마지막 판에서 버튼을 빠르게 두 번 누르면 두 요청이
     *   <b>동시에</b> "아직 안 끝났다" 를 통과한다. 둘 다 판을 끝내고 둘 다 보상을 부르면
     *   <b>한 판으로 보상을 두 번</b> 받는다. 먼저 친 것이 나중 것에 덮여 사라지기도 한다.
     *
     * ★ 지금은 보상이 NONE 이라 아무 일도 안 일어나지만, <b>나중에 값만 바꿔 켜는</b>
     *   구조로 두려면 그때 이미 막혀 있어야 한다. 켠 뒤에 고치면 그 사이에 두 배로
     *   받아 간 사람이 생기고, 그건 되돌릴 수 없다(하루 횟수 제한을 지금 넣은 것과 같은 이유).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from ZzalGame g where g.id = :id")
    Optional<ZzalGame> findByIdForUpdate(@Param("id") Long id);
}
