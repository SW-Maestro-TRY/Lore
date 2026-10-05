package com.lore.piecemaker.resultview;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.piecemaker.metapixel.MetaPixelSignalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ResultViewServiceTest {
    private final ResultViewRepository repository = mock(ResultViewRepository.class);
    private final MetaPixelSignalService metaPixel = mock(MetaPixelSignalService.class);
    private final ResultViewService service = new ResultViewService(repository, true, metaPixel);
    private static final String NORMAL = "{\"grade\":\"insufficient\",\"reason\":\"근거가 아직 부족합니다\",\"support\":[],\"against\":[]}";

    @BeforeEach
    void ownNormalResult() {
        when(repository.lockOwnedResult(7, 11)).thenReturn(Optional.of(new ResultViewRepository.OwnedResult("COMPLETE", NORMAL)));
    }

    @Test
    @DisplayName("AC-2-11-1 정상 판정은 판정 보류 등급도 첫 열람으로 저장한다")
    void storesNormalResultIncludingInsufficientGrade() {
        Instant at = Instant.parse("2026-10-05T10:00:00Z");
        when(repository.insertFirst(11, 7)).thenReturn(Optional.of(at));
        assertThat(service.record(11L, "7")).isEqualTo(new ResultViewResponse(true, at, null));
        verify(repository, never()).firstViewedAt(anyLong());
    }

    @Test
    @DisplayName("AC-2-11-2 다시 호출해도 최초 저장 시각을 유지한다")
    void duplicateReturnsOriginalTimestamp() {
        Instant original = Instant.parse("2026-10-04T10:00:00Z");
        when(repository.insertFirst(11, 7)).thenReturn(Optional.empty());
        when(repository.firstViewedAt(11)).thenReturn(original);
        assertThat(service.record(11L, "7")).isEqualTo(new ResultViewResponse(false, original, null));
        verifyNoInteractions(metaPixel);
    }

    @Test
    @DisplayName("AC-2-11-3 남의 결과와 없는 결과는 동일하게 404 코드다")
    void missingOrOtherOwnerIsNotFound() {
        when(repository.lockOwnedResult(7, 11)).thenReturn(Optional.empty());
        assertError(() -> service.record(11L, "7"), ErrorCode.PIECE_MAKER_HYPOTHESIS_NOT_FOUND);
        verify(repository, never()).insertFirst(anyLong(), anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    @DisplayName("AC-2-11-3 유효하지 않은 가설 번호는 404 코드다")
    void invalidIdIsNotFound(String id) {
        assertError(() -> service.record(11L, id), ErrorCode.PIECE_MAKER_HYPOTHESIS_NOT_FOUND);
        verify(repository, never()).insertFirst(anyLong(), anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "FAILED"})
    @DisplayName("AC-2-11-4 대기와 실패 결과는 세지 않는다")
    void pendingAndFailedAreRejected(String status) {
        when(repository.lockOwnedResult(7, 11)).thenReturn(Optional.of(new ResultViewRepository.OwnedResult(status, NORMAL)));
        assertError(() -> service.record(11L, "7"), ErrorCode.INVALID_INPUT);
        verify(repository, never()).insertFirst(anyLong(), anyLong());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"{", "null", "[]", "{}",
            "{\"grade\":\"unknown\",\"reason\":\"x\",\"support\":[],\"against\":[]}",
            "{\"grade\":\"likely\",\"reason\":\" \",\"support\":[],\"against\":[]}",
            "{\"grade\":\"likely\",\"reason\":17,\"support\":[],\"against\":[]}",
            "{\"grade\":\"likely\",\"reason\":\"x\",\"support\":[17],\"against\":[]}",
            "{\"grade\":\"likely\",\"reason\":\"x\",\"support\":[\"no-card\"],\"against\":[]}",
            "{\"grade\":\"likely\",\"reason\":\"x\",\"support\":[],\"against\":null}"})
    @DisplayName("AC-2-11-4 COMPLETE라도 판정 내용이 깨졌으면 세지 않는다")
    void brokenJudgementIsRejected(String judgement) {
        when(repository.lockOwnedResult(7, 11)).thenReturn(Optional.of(new ResultViewRepository.OwnedResult("COMPLETE", judgement)));
        assertError(() -> service.record(11L, "7"), ErrorCode.INVALID_INPUT);
        verify(repository, never()).insertFirst(anyLong(), anyLong());
    }

    @Test
    @DisplayName("AC-2-11-5 인증 없이 직접 서비스 호출해도 기록하지 않는다")
    void requiresLogin() {
        assertError(() -> service.record(null, "7"), ErrorCode.UNAUTHORIZED);
        verify(repository, never()).insertFirst(anyLong(), anyLong());
    }

    @Test
    @DisplayName("AC-2-11-6 DB 저장 실패는 성공으로 바꾸지 않는다")
    void databaseFailureIsPropagated() {
        var failure = new DataAccessResourceFailureException("test database unavailable");
        when(repository.insertFirst(11, 7)).thenThrow(failure);
        assertThatThrownBy(() -> service.record(11L, "7")).isSameAs(failure);
    }

    @Test
    @DisplayName("AC-2-11-8 행동 기록이 꺼져 있으면 정상 결과도 DB 조회·저장 없이 409 코드다")
    void disabledTrackingDoesNotAccessRepository() {
        var disabled = new ResultViewService(repository, false, metaPixel);
        assertError(() -> disabled.record(11L, "7"), ErrorCode.PIECE_MAKER_RESULT_VIEW_DISABLED);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("AC-2-11-8 기록 비활성화보다 로그인 검사를 먼저 한다")
    void disabledTrackingStillRequiresLoginFirst() {
        var disabled = new ResultViewService(repository, false, metaPixel);
        assertError(() -> disabled.record(null, "7"), ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(repository);
    }

    private static void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(expected));
    }
}
