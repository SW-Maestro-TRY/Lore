package com.lore.zzal.share;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
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
import java.util.List;
import java.util.Optional;

import static com.lore.zzal.pet.AwakeClockTest.kst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    // ── 링크를 열 때 (M-19) ──────────────────────────────────────────────

    @Test
    @DisplayName("★ 없는 토큰은 404 한 가지 — 토큰을 찍어 보는 사람에게 단서를 주지 않는다")
    void unknownTokenIsNotFound() {
        when(shareRepository.findByToken("없는토큰")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.open("없는토큰"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_SHARE_NOT_FOUND);
    }

    @Test
    @DisplayName("★ 링크는 있는데 펫이 사라졌으면 404 — 그리고 조회수도 안 오른다")
    void missingPetIsNotFoundAndNotCounted() {
        ZzalShare share = ZzalShare.issue(PET, "shy", T0);
        when(shareRepository.findByToken("tok")).thenReturn(Optional.of(share));
        ZzalPetRepository empty = mock(ZzalPetRepository.class);
        when(empty.findById(any())).thenReturn(Optional.empty());
        ShareService svc = new ShareService(shareRepository, empty, motionRepository,
                new MotionCatalog("", "", "v1"), "https://lorecomic.com/s");

        assertThatThrownBy(() -> svc.open("tok"))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_SHARE_NOT_FOUND);

        assertThat(share.getViews()).as("보여 준 것이 없으니 센 것도 없다").isZero();
    }

    @Test
    @DisplayName("카탈로그에 없는 동작 키도 404 — 다만 조회수는 이미 올라간 뒤다(판정 순서)")
    void unknownMotionKeyIsNotFound() {
        ZzalShare share = ZzalShare.issue(PET, "없는동작", T0);
        when(shareRepository.findByToken("tok")).thenReturn(Optional.of(share));

        assertThatThrownBy(() -> service.open("tok"))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_SHARE_NOT_FOUND);

        // ★ 지금 코드는 조회수를 먼저 올리고 키를 나중에 본다. 이 줄은 <b>지금 그렇다</b>는 기록이다 —
        //   순서를 바꾸는 날 여기가 깨져서 "왜 바꿨나" 를 다시 묻게 된다.
        assertThat(share.getViews()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 연 만큼 센다 — 무엇이 실제로 퍼졌는지 보는 유일한 숫자다")
    void everyOpenIsCounted() {
        ZzalShare share = ZzalShare.issue(PET, "shy", T0);
        when(shareRepository.findByToken("tok")).thenReturn(Optional.of(share));

        for (int i = 1; i <= 5; i++) {
            service.open("tok");
            assertThat(share.getViews()).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("★ 토큰은 22 글자 URL-safe — 주소에 그대로 실리고 훑어서 찾을 수 없어야 한다")
    void tokenIsUrlSafeAndLongEnough() {
        for (int i = 0; i < 50; i++) {
            String token = ZzalShare.issue(PET, "shy", T0).getToken();
            assertThat(token).hasSize(22).matches("[A-Za-z0-9_-]+");
        }
    }

    @Test
    @DisplayName("★ 주소는 끝의 / 가 있든 없든 슬래시 하나로 이어진다")
    void urlJoinsWithExactlyOneSlash() {
        ZzalPetRepository pets = mock(ZzalPetRepository.class);
        when(pets.findById(any())).thenReturn(Optional.of(pet));
        ZzalShare share = ZzalShare.issue(PET, "shy", T0);
        when(shareRepository.findByPetIdAndMotionKey(PET, "shy")).thenReturn(Optional.of(share));

        for (String base : List.of("https://lorecomic.com/s", "https://lorecomic.com/s/")) {
            ShareService svc = new ShareService(shareRepository, pets, motionRepository,
                    new MotionCatalog("", "", "v1"), base);
            assertThat(svc.issue(PET, "shy", T0).url())
                    .isEqualTo("https://lorecomic.com/s/" + share.getToken());
        }
    }

    @Test
    @DisplayName("★ 이미 낸 링크는 그대로 준다 — 누를 때마다 주소가 달라지면 무엇이 퍼졌는지 셀 수 없다")
    void issuingTwiceGivesTheSameLink() {
        ZzalShare share = ZzalShare.issue(PET, "shy", T0);
        when(shareRepository.findByPetIdAndMotionKey(PET, "shy")).thenReturn(Optional.of(share));

        ShareResponses.Issued first = service.issue(PET, "shy", T0);
        ShareResponses.Issued second = service.issue(PET, "shy", T0.plusSeconds(600));

        assertThat(second.token()).isEqualTo(first.token());
        assertThat(second.url()).isEqualTo(first.url());
        org.mockito.Mockito.verify(shareRepository, org.mockito.Mockito.never()).saveAndFlush(any());
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
