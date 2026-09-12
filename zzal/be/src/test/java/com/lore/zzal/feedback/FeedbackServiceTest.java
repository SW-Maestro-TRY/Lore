package com.lore.zzal.feedback;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.feedback.dto.FeedbackRequests.Tag;
import com.lore.zzal.feedback.dto.FeedbackResponses;
import com.lore.zzal.game.RewardService;
import com.lore.zzal.pet.PetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 후기 — <b>중복 방어 두 겹</b>과 칩 정규화(M-22).
 *
 * <h3>★ 왜 이 시험이 필요한가</h3>
 * 이 패키지는 시험이 0건이었다. 그런데 여기에는 <b>일부러 두 겹으로 만든 방어</b>가 있고
 * (미리 조회 + 유니크 제약 catch), 그 두 번째 겹은 정상 경로에서 <b>한 번도 안 돈다.</b>
 * {@code saveAndFlush} 를 {@code save} 로 바꾸는 한 줄이면 catch 가 무력화돼 두 번째 제출이
 * <b>500</b> 이 되고, 보상을 켜는 날에는 그게 곧 <b>이중 지급</b>이다.
 *
 * <h3>★ 칩 정규화도 여기서 본다</h3>
 * 화면이 토글을 잘못 다뤄 같은 값을 두 번 보내면, 그대로 저장했을 때 <b>한 사람이 두 명처럼 잡힌다.</b>
 * 반대로 읽을 때는 모르는 칩을 <b>버려야</b> 한다 — 칩 목록에서 무엇을 빼는 날, 그 값으로 저장된 사람이
 * 자기 후기를 영영 못 보게 되는 쪽이 훨씬 나쁘다.
 */
@DisplayName("후기 — 한 사람이 한 펫에 한 번")
class FeedbackServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-11T03:00:00Z");
    private static final Long USER = 1L;
    private static final Long PET = 7L;

    private ZzalFeedbackRepository repository;
    private RewardService rewards;
    private FeedbackService service;
    private final List<ZzalFeedback> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(ZzalFeedbackRepository.class);
        rewards = mock(RewardService.class);
        PetService pets = mock(PetService.class);          // 소유권 판정은 펫 서비스의 몫이라 여기서는 통과시킨다
        stored.clear();

        when(repository.findByUserIdAndPetId(anyLong(), anyLong()))
                .thenAnswer(inv -> stored.stream().findFirst());
        when(repository.saveAndFlush(any())).thenAnswer(inv -> {
            ZzalFeedback f = inv.getArgument(0);
            stored.add(f);
            return f;
        });

        service = new FeedbackService(repository, pets, rewards);
    }

    private ZzalFeedback submit(int rating, List<Tag> tags, String text) {
        return service.submit(USER, PET, rating, tags, text, T0);
    }

    // ── 첫 겹 — 미리 조회 ─────────────────────────────────────────────────

    @Test
    @DisplayName("★ 두 번째 제출은 ZZAL_FEEDBACK_ALREADY_SUBMITTED — 저장도 보상도 한 번뿐")
    void secondSubmitIsRefused() {
        submit(5, List.of(Tag.LOOKS_SAME), "좋아요");

        assertThatThrownBy(() -> submit(1, List.of(Tag.LOOKS_OFF), "역시 별로"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_FEEDBACK_ALREADY_SUBMITTED);

        assertThat(stored).hasSize(1);
        verify(repository, times(1)).saveAndFlush(any());
        verify(rewards, times(1)).forFeedback(PET, T0);
    }

    // ── 둘째 겹 — 유니크 제약 ─────────────────────────────────────────────

    @Test
    @DisplayName("★★ 두 요청이 동시에 미리 조회를 통과해도 500 이 아니라 '이미 냈다' 로 답한다 — 보상도 안 나간다")
    void concurrentSubmitFallsBackToTheConstraint() {
        // 미리 조회는 둘 다 "없다" 를 본다(두 번째 요청이 아직 커밋 전이다).
        when(repository.findByUserIdAndPetId(anyLong(), anyLong())).thenReturn(Optional.empty());
        // 진 쪽은 uk_feedback_user_pet 에 걸린다.
        when(repository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uk_feedback_user_pet"));

        assertThatThrownBy(() -> submit(4, List.of(), null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ZZAL_FEEDBACK_ALREADY_SUBMITTED);

        verify(rewards, never()).forFeedback(anyLong(), any());
    }

    // ── 칩 정규화 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 같은 칩을 두 번 보내도 한 번만 저장된다 — 세는 순간 한 사람이 두 명이 되지 않게")
    void duplicateTagsAreCollapsed() {
        submit(3, List.of(Tag.LOOKS_SAME, Tag.MOTION_ODD, Tag.LOOKS_SAME), null);

        assertThat(stored.get(0).getTags()).isEqualTo("LOOKS_SAME,MOTION_ODD");
    }

    @Test
    @DisplayName("고른 순서는 그대로 둔다 — '무엇을 먼저 골랐나' 를 나중에 볼 여지")
    void tagOrderIsKept() {
        submit(3, List.of(Tag.WANT_MORE, Tag.LOOKS_SAME), null);

        assertThat(stored.get(0).getTags()).isEqualTo("WANT_MORE,LOOKS_SAME");
    }

    @Test
    @DisplayName("칩을 하나도 안 고르면 null — 빈 문자열과 섞이지 않게")
    void noTagsIsNull() {
        submit(3, List.of(), null);
        assertThat(stored.get(0).getTags()).isNull();

        stored.clear();
        when(repository.findByUserIdAndPetId(anyLong(), anyLong())).thenReturn(Optional.empty());
        service.submit(USER, 8L, 3, null, null, T0);
        assertThat(stored.get(0).getTags()).isNull();
    }

    // ── 자유롭게 쓴 말 ────────────────────────────────────────────────────

    @Test
    @DisplayName("★ 글 앞뒤 공백은 다듬고, 공백뿐인 글은 안 쓴 것으로 본다")
    void textIsTrimmedAndBlankBecomesNull() {
        submit(5, List.of(), "  기대돼요  ");
        assertThat(stored.get(0).getText()).isEqualTo("기대돼요");

        stored.clear();
        when(repository.findByUserIdAndPetId(anyLong(), anyLong())).thenReturn(Optional.empty());
        service.submit(USER, 9L, 5, List.of(), "   ", T0);
        assertThat(stored.get(0).getText()).isNull();
    }

    // ── 읽을 때 — 모르는 칩 ───────────────────────────────────────────────

    @Test
    @DisplayName("★★ 저장된 문자열에 모르는 칩·빈 칸이 섞여 있어도 아는 칩만 남는다 — 조회 전체가 막히면 안 된다")
    void unknownStoredTagsAreDropped() {
        ZzalFeedback old = ZzalFeedback.of(USER, PET, 4, "LOOKS_SAME,OLD_TAG,,MOTION_ODD", null, T0);

        FeedbackResponses.Submitted response = FeedbackResponses.Submitted.of(old);

        assertThat(response.tags()).containsExactly(Tag.LOOKS_SAME, Tag.MOTION_ODD);
        assertThat(response.submitted()).isTrue();
    }

    @Test
    @DisplayName("아직 안 낸 사람의 응답은 빈 칸뿐 — 화면이 '어느 쪽이지' 를 고민하지 않게")
    void noneIsEmpty() {
        FeedbackResponses.Submitted none = FeedbackResponses.Submitted.none();

        assertThat(none.submitted()).isFalse();
        assertThat(none.tags()).isEmpty();
        assertThat(none.rating()).isNull();
        assertThat(none.text()).isNull();
        assertThat(none.createdAt()).isNull();
    }
}
