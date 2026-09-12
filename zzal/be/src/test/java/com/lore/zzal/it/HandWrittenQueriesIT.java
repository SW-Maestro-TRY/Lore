package com.lore.zzal.it;

import com.lore.common.auth.token.UserRefreshTokenRepository;
import com.lore.common.credit.CreditEventRepository;
import com.lore.common.user.User;
import com.lore.zzal.game.ZzalGameRepository;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.generation.GenStepRecordRepository;
import com.lore.zzal.motion.ZzalMotionRepository;
import com.lore.zzal.pet.ZzalPetRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시나리오 2 — <b>손으로 쓴 질의(JPQL)가 전부 실제로 DB 에서 돈다.</b>
 *
 * <h3>왜 부팅만으로는 부족한가</h3>
 * 스프링은 부팅 때 JPQL 을 <b>해석</b>한다. 그래서 문법·이름 오류는 컨텍스트가 뜨면 이미 걸러졌다.
 * 그런데 해석을 통과하고 <b>실행에서만</b> 터지는 것들이 남는다 —
 * 잠금 힌트({@code PESSIMISTIC_WRITE})가 트랜잭션 밖에서 불리는 것, {@code coalesce(sum(...), 0)} 의
 * 실제 타입, 빈 {@code IN} 절, {@code @Modifying} 이 트랜잭션 없이 도는 것. 여기서는 <b>한 번씩 실제로 부른다.</b>
 *
 * <h3>★ 무엇을 세었나</h3>
 * {@code @Query} 가 붙은 메서드는 zzal 에 10개, zzal 이 쓰는 공통(common)에 3개로 <b>모두 13개</b>다.
 * 목록을 손으로 적는 대신 여기 시험이 그 13개를 한 줄씩 부른다 — 새 질의가 생기면 여기 한 줄을 더한다.
 *
 * ★ 결과가 비어 있어도 된다. 이 시험이 보는 것은 "돌긴 도는가" 다.
 */
@ZzalIntegrationTest
@DisplayName("시나리오 2 — 수기 JPQL 13개가 전부 실제로 실행된다")
class HandWrittenQueriesIT extends ZzalItSupport {

    @Autowired ZzalMotionRepository motionRepository;
    @Autowired ZzalGameRepository gameRepository;
    @Autowired GenJobRepository jobRepository;
    @Autowired GenStepRecordRepository stepRepository;
    @Autowired ZzalPetRepository pets;
    @Autowired CreditEventRepository creditEvents;
    @Autowired UserRefreshTokenRepository refreshTokens;

    /** 없는 번호로 불러도 된다 — 질의가 도는지를 본다. 있는 줄이 필요한 것은 잠금 세 개뿐이다. */
    private static final long ABSENT = 999_999L;

    @Test
    @DisplayName("zzal 10개 — 집기·되돌리기·잠금 3종·비용 2종·성공 단계 3종")
    void everyZzalQueryRuns() {
        // 1·2) 집기와 되돌리기. @Modifying 이라 바꾼 줄 수가 돌아온다(없는 줄이면 0).
        assertThat(motionRepository.claim(ABSENT, Instant.now(), "zzal-it")).isZero();
        assertThat(motionRepository.releaseClaim(ABSENT)).isZero();

        // 3·4·5) 잠금(SELECT … FOR UPDATE). ★ 트랜잭션 밖에서 부르면 잠금이 뜻을 잃거나 터진다 —
        //        그래서 실제 서비스와 같이 트랜잭션 안에서 부른다.
        transactions.executeWithoutResult(status -> {
            assertThat(motionRepository.findByIdForUpdate(ABSENT)).isEmpty();
            assertThat(gameRepository.findByIdForUpdate(ABSENT)).isEmpty();
            assertThat(pets.findByIdForUpdate(ABSENT)).isEmpty();
        });

        // 6) 기간 내 총 비용 — 한 줄도 없으면 null 이 아니라 0 이어야 한다(coalesce).
        BigDecimal since = jobRepository.sumCostSince(Instant.now().minusSeconds(3600));
        assertThat(since).isNotNull().isEqualByComparingTo(BigDecimal.ZERO);

        // 7) 그 모션들에 들어간 돈. ★ 빈 목록은 주지 않는다 — 빈 IN 절은 DB 마다 다르게 군다.
        BigDecimal byMotions = jobRepository.sumCostByMotionIds(List.of(ABSENT, ABSENT + 1));
        assertThat(byMotions).isNotNull().isEqualByComparingTo(BigDecimal.ZERO);

        // 8·9·10) 성공한 단계 — 펫 단위 · 펫+버전 단위 · 모션 단위(서브질의가 들어 있다).
        assertThat(stepRepository.findSucceededByPet(ABSENT, GenKind.HATCH)).isEmpty();
        assertThat(stepRepository.findSucceededByPetAndVersion(ABSENT, GenKind.HATCH, "v2")).isEmpty();
        assertThat(stepRepository.findSucceededByMotion(ABSENT)).isEmpty();
    }

    @Test
    @DisplayName("zzal 이 기대는 공통 3개 — 잔액·내역·토큰 일괄 폐기")
    void everyCommonQueryRuns() {
        User user = userRepository.findById(newUserId()).orElseThrow();

        // 11) 잔액 = 움직인 것 전부의 합. 한 줄도 없으면 0.
        assertThat(creditEvents.balanceOf(user.getId())).isZero();

        // 12) 최근 내역(페이지).
        assertThat(creditEvents.historyOf(user.getId(), PageRequest.of(0, 5))).isEmpty();

        // 13) 살아 있는 토큰 일괄 폐기. ★ @Modifying 이라 트랜잭션이 없으면 실행에서 터진다.
        transactions.executeWithoutResult(status ->
                assertThat(refreshTokens.revokeAllByUser(user, Instant.now())).isZero());
    }
}
