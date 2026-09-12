package com.lore.zzal.share;

import com.lore.zzal.PetFixture;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionSpec;
import com.lore.zzal.motion.MotionStatus;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.motion.ZzalMotionRepository;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalPetRepository;
import com.lore.zzal.share.dto.ShareResponses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 공유 — 링크가 가리키는 그림이 실제로 있는 그림인가(P-2).
 *
 * ★ 이 패키지는 시험이 0건이었다. 그 사이에 "가장 자랑하고 싶은 그림이 남에게는 404" 가 숨어 있었다.
 */
@DisplayName("공유 링크")
class ShareServiceTest {

    private static final Instant T0 = kst("2026-09-05 12:00");
    private static final Long USER = 1L;
    private static final Long PET = 7L;

    private ZzalShareRepository shareRepository;
    private ZzalMotionRepository motionRepository;
    private ShareService service;
    private ZzalPet pet;

    @BeforeEach
    void setUp() {
        shareRepository = mock(ZzalShareRepository.class);
        motionRepository = mock(ZzalMotionRepository.class);
        when(motionRepository.findByPetIdAndSeq(anyLong(), anyInt())).thenReturn(Optional.empty());

        pet = PetFixture.hatching(USER, "여울", null, "k", T0);
        pet.markAlive("s", "i", T0);
        ReflectionTestUtils.setField(pet, "id", PET);

        ZzalPetRepository petRepository = mock(ZzalPetRepository.class);
        when(petRepository.findById(any())).thenReturn(Optional.of(pet));

        service = new ShareService(shareRepository, petRepository, motionRepository,
                new MotionCatalog("", "", "v1"), "https://lorecomic.com/s");
    }

    @Test
    @DisplayName("★★ 도착한 심화 행동은 그 행의 실제 키로 나간다 — basic/{key}.webp 로 조립하면 404 다 (P-2)")
    void revealedAdvancedMotionUsesItsOwnKey() {
        MotionSpec shy = MotionCatalog.ALL.stream().filter(m -> m.key().equals("shy")).findFirst().orElseThrow();
        String baked = "images/zzal/pets/7/motions/91/motion.webp";
        when(motionRepository.findByPetIdAndSeq(PET, shy.seq())).thenReturn(Optional.of(revealed(shy, baked)));
        when(shareRepository.findByToken("tok")).thenReturn(Optional.of(ZzalShare.issue(PET, "shy", T0)));

        ShareResponses.Public open = service.open("tok");

        assertThat(open.imageKey()).isEqualTo(baked);
        assertThat(open.imageKey()).doesNotContain("/basic/");
    }

    @Test
    @DisplayName("아직 안 도착한 동작은 기본 행동 그림 그대로")
    void basicMotionKeepsBasicKey() {
        when(shareRepository.findByToken("tok")).thenReturn(Optional.of(ZzalShare.issue(PET, "shy", T0)));

        assertThat(service.open("tok").imageKey()).isEqualTo("images/zzal/pets/7/basic/shy.webp");
    }

    @Test
    @DisplayName("★★ 연타로 같은 순간에 두 번 발급해도 500 이 아니라 먼저 들어온 링크를 준다 (P-6)")
    void concurrentIssueReusesTheWinnersLink() {
        ZzalShare winner = ZzalShare.issue(PET, "shy", T0);
        // 조회는 두 번 — 처음엔 없다, 제약에 걸린 뒤엔 이긴 쪽이 넣어 둔 줄이 보인다
        when(shareRepository.findByPetIdAndMotionKey(PET, "shy"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        // 늦게 도착한 쪽은 uk_zzal_shares_pet_motion 에 걸린다
        when(shareRepository.save(any())).thenThrow(new DataIntegrityViolationException("uk_zzal_shares_pet_motion"));
        when(shareRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uk_zzal_shares_pet_motion"));

        assertThatCode(() -> {
            ShareResponses.Issued issued = service.issue(PET, "shy", T0);
            assertThat(issued.token()).isEqualTo(winner.getToken());
            assertThat(issued.url()).isEqualTo("https://lorecomic.com/s/" + winner.getToken());
        }).doesNotThrowAnyException();
    }

    /**
     * 밤새 구워 아침에 도착한 심화 행동 한 행.
     *
     * ★ 정식 전이(BAKING → toReview → approve → reveal)는 저장소를 끼고 도는 길이라, 여기서는
     *   <b>도착한 결과 상태</b>만 만든다. 이 시험이 보는 것은 전이가 아니라 "그 뒤에 무엇을 내보내나" 다.
     */
    private static ZzalMotion revealed(MotionSpec spec, String imageKey) {
        ZzalMotion row = ZzalMotion.forCatalog(PET, spec, T0);
        ReflectionTestUtils.setField(row, "status", MotionStatus.OPEN);
        ReflectionTestUtils.setField(row, "imageKey", imageKey);
        ReflectionTestUtils.setField(row, "revealedAt", T0);
        return row;
    }
}
