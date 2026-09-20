package com.lore.zzal.piece;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 완성은 <b>쓰인 뒤에만</b> 비운다(1.9).
 *
 * <h3>★★ 무엇이 새어 나갔나</h3>
 * 네 칸이 찬 판을 비우는 자리는 <b>다음 기상</b>이고, 굽기를 거는 자리는 <b>밤</b>이다.
 * 그 사이가 벌어져 양쪽으로 샜다.
 * <ul>
 *   <li><b>두 번 걸림</b> — 직접 재워 한 번 걸고, 같은 밤 스위프가 또 건다. 판이 아직 안 비워져
 *       {@code isComplete()} 가 계속 참이기 때문이다. 심화 둘이 구워지고 돈도 검수도 두 배다.
 *       옛 코드는 {@code consumePieceStreak()} 으로 막았는데 1.9 에서 그 칸이 사라지며 같이 없어졌다</li>
 *   <li><b>안 걸림</b> — 직접 재우지 않아 자동 취침으로 넘어가고 스위프도 꺼져 있으면, 아무것도 안 걸린 채
 *       다음 기상에 판이 비워진다. <b>이틀 걸려 채운 조각이 조용히 사라진다</b></li>
 * </ul>
 */
@DisplayName("조각 완성 — 쓰인 뒤에만 비운다")
class PieceConsumptionTest {

    /** 네 칸을 다 채운다. */
    private ZzalPiece complete() {
        ZzalPiece piece = ZzalPiece.of(1L);
        for (PieceEvent event : PieceEvent.values()) {
            for (int i = 0; i < 20 && !piece.isDone(event.kind()); i++) {
                piece.count(event);
            }
        }
        assertThat(piece.isComplete()).isTrue();
        return piece;
    }

    @Test
    @DisplayName("★★ 같은 완성을 두 번 가져갈 수 없다 — 재우기와 밤 스위프가 같은 밤에 둘 다 돈다")
    void consumedOnlyOnce() {
        ZzalPiece piece = complete();

        assertThat(piece.consume()).isTrue();
        assertThat(piece.consume()).isFalse();      // 스위프가 또 와도 안 걸린다
    }

    @Test
    @DisplayName("★★ 안 쓰인 완성은 기상에 비우지 않는다 — 이틀 걸려 채운 것이 사라지면 안 된다")
    void unconsumedSurvivesWake() {
        ZzalPiece piece = complete();

        assertThat(piece.resetOnWakeIfComplete()).isFalse();
        assertThat(piece.isComplete()).isTrue();    // 다음 밤에 걸릴 수 있게 남아 있다
    }

    @Test
    @DisplayName("쓰인 완성은 기상에 비운다 — 다음 날 새 판을 시작한다")
    void consumedResetsOnWake() {
        ZzalPiece piece = complete();
        piece.consume();

        assertThat(piece.resetOnWakeIfComplete()).isTrue();
        assertThat(piece.isComplete()).isFalse();
        assertThat(piece.doneCount()).isZero();
        assertThat(piece.isConsumed()).isFalse();   // 새 판은 다시 안 쓰인 상태로 시작한다
    }

    @Test
    @DisplayName("다 안 찬 판은 쓸 수도 비울 수도 없다")
    void incompleteIsUntouched() {
        ZzalPiece piece = ZzalPiece.of(1L);
        piece.count(PieceEvent.FEED);

        assertThat(piece.consume()).isFalse();
        assertThat(piece.resetOnWakeIfComplete()).isFalse();
        assertThat(piece.countOf(PieceEvent.FEED)).isEqualTo(1);
    }
}
