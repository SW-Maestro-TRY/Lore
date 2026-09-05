package com.lore.zzal.motion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 부화 완료 훅 — 동작 18행. 두 번 불러도 안전해야 한다(부화 재시도·재기동 복구가 다시 부른다). */
@DisplayName("동작 18행 앉히기")
class MotionSeederTest {

    private static final Instant HATCHED = Instant.parse("2026-09-05T03:00:00Z");

    @Test
    @DisplayName("★ 18행 — 1층 8종은 unlockedAt=부화 시각, 나머지는 null, 전부 NONE, name=key, seq=13장 번호")
    void seedsEighteen() {
        ZzalMotionRepository repo = mock(ZzalMotionRepository.class);
        when(repo.findByPetIdOrderBySeqAsc(anyLong())).thenReturn(List.of());
        MotionSeeder seeder = new MotionSeeder(repo, new MotionCatalog("", "", "v1"));

        assertThat(seeder.seed(7L, HATCHED)).isEqualTo(18);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ZzalMotion>> rows = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(rows.capture());
        List<ZzalMotion> saved = rows.getValue();
        assertThat(saved).hasSize(18);
        assertThat(saved).allSatisfy(m -> {
            assertThat(m.getPetId()).isEqualTo(7L);
            assertThat(m.getStatus()).isEqualTo(MotionStatus.NONE);
        });
        assertThat(saved.stream().filter(m -> m.getLayer() == MotionLayer.BASIC_1)).hasSize(8)
                .allSatisfy(m -> assertThat(m.getUnlockedAt()).isEqualTo(HATCHED));
        assertThat(saved.stream().filter(m -> m.getLayer() != MotionLayer.BASIC_1))
                .allSatisfy(m -> assertThat(m.getUnlockedAt()).isNull());
        assertThat(saved.get(0).getSeq()).isEqualTo(1);
        assertThat(saved.get(0).getName()).isEqualTo("base");
        assertThat(saved.get(16).getSeq()).isEqualTo(101);
        assertThat(saved.get(16).getName()).isEqualTo("roll");
    }

    @Test
    @DisplayName("이미 있으면 건너뛴다 — 두 번 불러도 행이 안 는다")
    void idempotent() {
        ZzalMotionRepository repo = mock(ZzalMotionRepository.class);
        MotionCatalog catalog = new MotionCatalog("", "", "v1");
        List<ZzalMotion> existing = catalog.all().stream().map(s -> ZzalMotion.forCatalog(7L, s, HATCHED)).toList();
        when(repo.findByPetIdOrderBySeqAsc(7L)).thenReturn(existing);

        assertThat(new MotionSeeder(repo, catalog).seed(7L, HATCHED)).isZero();
        verify(repo, never()).saveAll(any());
    }

    @Test
    @DisplayName("일부만 있으면 빠진 것만 채운다(부화 재시도 중 끊긴 경우)")
    void fillsMissingOnly() {
        ZzalMotionRepository repo = mock(ZzalMotionRepository.class);
        MotionCatalog catalog = new MotionCatalog("", "", "v1");
        when(repo.findByPetIdOrderBySeqAsc(7L)).thenReturn(
                catalog.all().stream().limit(5).map(s -> ZzalMotion.forCatalog(7L, s, HATCHED)).toList());

        assertThat(new MotionSeeder(repo, catalog).seed(7L, HATCHED)).isEqualTo(13);
    }
}
