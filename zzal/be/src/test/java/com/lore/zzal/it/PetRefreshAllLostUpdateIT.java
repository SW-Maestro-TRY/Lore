package com.lore.zzal.it;

import com.lore.zzal.pet.ZzalPet;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설계 4-4 — 펫 목록 조회({@code PetService.refreshAll})가 그 사이 커밋된 call-back 을 덮지 않는다.
 *
 * <h3>지키는 결함</h3>
 * {@code refreshAll} 은 잠금 없이 목록을 먼저 읽고({@code findByUserIdOrderByIdDesc}) 펫마다 잠근다. 예전에는
 * {@code findMine}(FOR UPDATE)으로 잠갔는데, 같은 영속성 컨텍스트에 이미 올라온 엔티티는 잠금 조회가 와도
 * Hibernate 가 <b>먼저 읽은 값</b>을 그대로 돌려줘서, 목록 읽기와 잠금 사이에 커밋된 call-back 을 정산(touch) 뒤
 * 더티 체킹이 낡은 칸(여행 시작 시각 등)으로 덮었다. 그때 이 시험은 빨강이었다("여행 상태가 되살아남").
 *
 * <h3>지금</h3>
 * 잠글 때 {@code refresh(…, PESSIMISTIC_WRITE)} 로 잠그며 DB 의 최신 값으로 다시 읽은 뒤 정산해 초록이다.
 * 펫 표 조회 수는 예전과 같다(목록 1 + 펫마다 1) — 아래 두 번째 시험이 고정한다.
 *
 * ★ 이 시험은 목록 읽기를 {@code refreshAll} 바로 앞에서 같은 트랜잭션으로 한 번 해 둔다.
 *   {@code refreshAll} 안의 목록 읽기는 같은 컨텍스트에서 그 낡은 인스턴스를 그대로 받으므로,
 *   "목록 읽기 → (다른 트랜잭션 커밋) → 잠금" 순서와 같다. 코드를 바꾸지 않고 틈을 고정하는 방법이다.
 */
@ZzalIntegrationTest
class PetRefreshAllLostUpdateIT extends ZzalItSupport {

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

    @org.springframework.beans.factory.annotation.Autowired jakarta.persistence.EntityManagerFactory emf;

    /**
     * 잠금과 다시 읽기를 합쳐도 펫 표 조회 수는 예전과 같다 — 목록 1번 + 펫마다 1번(잠그며 다시 읽기) = 1 + N.
     * Hibernate 통계로 센다: 펫을 부르는 질의 실행 수(목록 1)와 펫 엔티티 읽기 수(목록 N + 다시 읽기 N = 2N).
     * 잠금 조회({@code findByIdForUpdate})를 따로 더 부르면 질의 수가, 다시 읽기를 빼면 읽기 수가 어긋난다.
     */
    @Test
    void refreshAllReadsPetTableOncePlusOncePerPet() {
        Long user = newUserId();
        Instant now = Instant.now();
        int n = 3;
        for (int i = 0; i < n; i++) {
            transactions.execute(status -> {
                ZzalPet created = petRepository.save(ZzalPet.draft(user, newUploadedImageKey(user), now));
                created.character("여울", null, null, "현대", null, null, now);
                created.markAlive("images/zzal/pets/sheet.png", "test", now);
                return created;
            });
        }
        var stats = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean was = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        try {
            stats.clear();
            transactions.executeWithoutResult(status -> petService.refreshAll(user, Instant.now()));
            long petQueries = java.util.Arrays.stream(stats.getQueries())
                    .filter(q -> q.toLowerCase().contains("from zzalpet "))
                    .mapToLong(q -> stats.getQueryStatistics(q).getExecutionCount()).sum();
            long petLoads = stats.getEntityStatistics(ZzalPet.class.getName()).getLoadCount();
            System.out.printf("[P4a-3] 펫 %d마리 목록 정산: 펫 질의 %d · 펫 읽기 %d · 준비된 문장 전체 %d%n",
                    n, petQueries, petLoads, stats.getPrepareStatementCount());
            assertThat(petQueries).as("펫을 부르는 질의는 목록 하나뿐").isEqualTo(1);
            assertThat(petLoads).as("목록으로 N + 잠그며 다시 읽기 N").isEqualTo(2L * n);
        } finally {
            stats.setStatisticsEnabled(was);
        }
    }
}
