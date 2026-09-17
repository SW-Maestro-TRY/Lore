package com.lore.zzal.guard;

import com.lore.common.user.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 부화를 시작하려는 사람의 줄({@code users})을 <b>잠그고</b> 꺼낸다.
 *
 * <h3>★★ 왜 필요한가 — 세는 것과 만드는 것 사이에 남이 끼어든다</h3>
 * "이 사람이 몇 마리 만들었나" 를 세고 나서 만들기까지의 틈에 같은 사람의 두 번째 요청이
 * <b>똑같이 세고 똑같이 통과</b>한다. 둘 다 200 이고 로그도 조용한데 굽기는 두 번 나간다
 * ($0.25 가 그대로 두 배다). 상한을 아무리 촘촘히 적어도 이 틈이 있으면 넘어간다.
 * 그래서 <b>세기 전에</b> 그 사람의 줄을 잠근다 — 같은 사람의 두 요청이 줄을 선다.
 *
 * <h3>★ 왜 펫이 아니라 사람을 잠그나</h3>
 * 막으려는 것이 "이 사람의 두 번째 부화" 라, 잠글 것은 <b>두 요청이 공유하는 것</b>이어야 한다.
 * 아직 펫이 없는 순간이 문제의 순간이므로 펫으로는 잠글 수 없다. 사람 줄은 반드시 있다.
 *
 * <h3>★ {@code UserRepository} 를 고치지 않는다</h3>
 * 그건 {@code common/} — 세 도메인이 함께 쓰는 자리다. 잠금은 zzal 의 사정이므로
 * <b>여기에 따로</b> 둔다. 표도 엔티티도 그대로 쓰고, 읽는 방법만 하나 더 얹는다.
 */
public interface HatchUserLockRepository extends Repository<User, Long> {

    /**
     * 그 사람 줄을 이 트랜잭션이 끝날 때까지 잠근다({@code select … for update}).
     *
     * @return 그런 사람이 없으면 비어 있다
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> lockForHatch(@Param("id") Long id);
}
