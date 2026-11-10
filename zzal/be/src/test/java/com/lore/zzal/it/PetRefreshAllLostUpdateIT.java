package com.lore.zzal.it;

import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설계 4-4 확인 — 펫 목록 조회({@code PetService.refreshAll})가 그 사이 커밋된 call-back 을 덮는가.
 *
 * <h3>재현하는 순서</h3>
 * {@code refreshAll} 은 잠금 없이 목록을 먼저 읽고({@code findByUserIdOrderByIdDesc}), 그 뒤 펫마다
 * {@code findMine}(FOR UPDATE)으로 잠근다. 같은 영속성 컨텍스트에 이미 올라온 엔티티는 잠금 조회가 와도
 * Hibernate 가 <b>먼저 읽은 값</b>을 그대로 돌려준다. 그래서 목록 읽기와 잠금 사이에 다른 트랜잭션이
 * call-back 을 커밋하면, 정산(touch) 뒤 더티 체킹이 낡은 칸(여행 시작 시각 등)까지 다시 써서 덮는다.
 *
 * ★ 이 시험은 목록 읽기를 {@code refreshAll} 바로 앞에서 같은 트랜잭션으로 한 번 해 둔다.
 *   {@code refreshAll} 안의 목록 읽기는 같은 컨텍스트에서 그 낡은 인스턴스를 그대로 받으므로,
 *   "목록 읽기 → (다른 트랜잭션 커밋) → 잠금" 순서와 같다. 코드를 바꾸지 않고 틈을 고정하는 방법이다.
 *
 * <h3>왜 꺼 두나</h3>
 * 원인(목록 읽기가 잠금 전이라 낡은 엔티티가 잠금 뒤에도 쓰인다)이 P4a 파일 범위({@code chat/**}) 밖의
 * {@code PetService.refreshAll} 이라 이번 조각에서 고치지 않는다(계획서 P4-0 "범위 밖이면 고치지 않고 남긴다").
 * 고칠 때 이 줄을 지우면 빨강에서 시작한다.
 */
@ZzalIntegrationTest
class PetRefreshAllLostUpdateIT extends ZzalItSupport {

    @Disabled("결함(설계 4-4) — refreshAll 의 잠금 없는 목록 읽기 뒤에 커밋된 call-back 이 덮인다. P4a 범위 밖, 고칠 때 켠다")
    @Test
    void callBackCommittedBetweenListReadAndLockSurvivesRefreshAll() throws Exception {
        Long user = newUserId();
        Instant now = Instant.now();
        ZzalPet pet = transactions.execute(status -> {
            ZzalPet created = petRepository.save(ZzalPet.draft(user, newUploadedImageKey(user), now));
            created.character("여울", null, null, "현대", null, null, now);
            created.markAlive("images/zzal/pets/sheet.png", "test", now);
            ReflectionTestUtils.setField(created, "tripStartedAt", now.minus(Duration.ofHours(2)));
            return created;
        });
        motionSeeder.seed(pet.getId(), now);

        CountDownLatch listed = new CountDownLatch(1);
        CountDownLatch calledBack = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var refresher = pool.submit(() -> transactions.executeWithoutResult(status -> {
                petRepository.findByUserIdOrderByIdDesc(user);      // refreshAll 의 첫 줄과 같은 잠금 없는 읽기
                listed.countDown();
                try {
                    assertThat(calledBack.await(20, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                petService.refreshAll(user, Instant.now());
            }));
            assertThat(listed.await(20, TimeUnit.SECONDS)).isTrue();
            petService.callBack(user, pet.getId(), Instant.now());   // 다른 트랜잭션이 먼저 커밋
            assertThat(petRepository.findById(pet.getId()).orElseThrow().isTraveling()).isFalse();
            calledBack.countDown();
            refresher.get(30, TimeUnit.SECONDS);
        }

        assertThat(petRepository.findById(pet.getId()).orElseThrow().isTraveling())
                .as("call-back 이 커밋된 뒤 목록 조회가 끝나도 여행에서 돌아온 상태가 남아야 한다")
                .isFalse();
    }
}
