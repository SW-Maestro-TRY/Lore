package com.lore.zzal.piece;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조각 세는 법(정본 6장 · 1.9).
 *
 * <h3>★ 여기서 지키는 것</h3>
 * 넷 다 <b>정상 경로에서는 티가 안 나고, 틀리면 조용히 틀린다.</b>
 * <ul>
 *   <li>넘친 만큼을 버리는가 — 안 버리면 다음 판이 한 칸 차 있는 채로 시작한다</li>
 *   <li>찍힌 칸을 더 안 세는가 — 세면 같은 일이 생긴다</li>
 *   <li>길이 둘인 칸에서 섞어 올려도 각자 제 목표를 채워야 하는가</li>
 *   <li>네 칸이 다 찬 판만 다음 기상에 0 이 되는가 — 아무 때나 0 이 되면 이틀치를 영영 못 채운다</li>
 * </ul>
 */
@DisplayName("조각 세는 법 (정본 6장 · 1.9)")
class ZzalPieceTest {

    private ZzalPiece piece() {
        return ZzalPiece.of(1L);
    }

    /** 그 행동을 n 번 센다. 마지막으로 찍힌 칸을 돌려준다. */
    private PieceKind count(ZzalPiece piece, PieceEvent event, int times) {
        PieceKind last = null;
        for (int i = 0; i < times; i++) {
            PieceKind got = piece.count(event);
            if (got != null) {
                last = got;
            }
        }
        return last;
    }

    @Nested
    @DisplayName("도장")
    class Stamp {

        @Test
        @DisplayName("★ 요구량에 닿으면 도장이 찍히고 횟수는 0 — 넘친 만큼은 버린다")
        void stampResetsAndDiscardsOverflow() {
            ZzalPiece piece = piece();

            // 밥 6회가 목표. 다섯 번까지는 아무 일도 없다
            assertThat(count(piece, PieceEvent.FEED, 5)).isNull();
            assertThat(piece.isDone(PieceKind.FOOD)).isFalse();
            assertThat(piece.countOf(PieceEvent.FEED)).isEqualTo(5);

            // 여섯 번째에 찍히고 횟수가 0 으로
            assertThat(piece.count(PieceEvent.FEED)).isEqualTo(PieceKind.FOOD);
            assertThat(piece.isDone(PieceKind.FOOD)).isTrue();
            assertThat(piece.countOf(PieceEvent.FEED)).as("넘친 만큼이 남지 않는다").isZero();
        }

        @Test
        @DisplayName("★★ 상훈님 예 — 1일차 4회, 2일차 4회를 해도 '5회 쓰고 3회 남음' 이 아니다")
        void overflowIsNotCarried() {
            ZzalPiece piece = piece();

            count(piece, PieceEvent.SNACK, 4);                  // 1일차 (하루 4개가 상한)
            assertThat(piece.isDone(PieceKind.PLAY)).isFalse();

            count(piece, PieceEvent.SNACK, 4);                  // 2일차에 네 번 더
            assertThat(piece.isDone(PieceKind.PLAY)).as("다섯 번째에 찍혔다").isTrue();
            assertThat(piece.countOf(PieceEvent.SNACK))
                    .as("도장 하나로 끝난다 — 남은 세 번이 다음으로 안 넘어간다").isZero();
        }

        @Test
        @DisplayName("★ 찍힌 칸은 더 세지 않는다")
        void doneSlotStopsCounting() {
            ZzalPiece piece = piece();
            count(piece, PieceEvent.FEED, 6);
            assertThat(piece.isDone(PieceKind.FOOD)).isTrue();

            assertThat(piece.count(PieceEvent.FEED)).as("더 먹여도 아무 일 없다").isNull();
            assertThat(piece.countOf(PieceEvent.FEED)).isZero();
        }

        @Test
        @DisplayName("★★ 길이 둘인 칸 — 섞어 올려도 한 길이 제 목표를 채워야 한다")
        void mixedPathsDoNotAddUp() {
            // 청결 = 목욕 2회 또는 청소 5회.
            // 한 칸에 횟수를 하나만 뒀다면 "청소 3 + 목욕 1 = 4" 가 목욕 목표(2)를 넘겨 찍혔을 것이다.
            ZzalPiece piece = piece();
            count(piece, PieceEvent.CLEAN, 3);
            assertThat(piece.count(PieceEvent.BATH)).as("목욕은 아직 한 번뿐").isNull();
            assertThat(piece.isDone(PieceKind.CLEAN)).isFalse();

            assertThat(piece.count(PieceEvent.BATH)).as("목욕 두 번째에 찍힌다").isEqualTo(PieceKind.CLEAN);
            assertThat(piece.countOf(PieceEvent.CLEAN)).as("같은 칸의 다른 길도 0 이 된다").isZero();
        }

        @Test
        @DisplayName("네 칸을 다 채우면 complete")
        void completeWhenAllFour() {
            ZzalPiece piece = piece();
            count(piece, PieceEvent.FEED, 6);
            count(piece, PieceEvent.GAME, 5);
            count(piece, PieceEvent.BATH, 2);
            count(piece, PieceEvent.CHAT, 5);

            assertThat(piece.doneCount()).isEqualTo(4);
            assertThat(piece.isComplete()).isTrue();
        }
    }

    @Nested
    @DisplayName("다음 기상")
    class Wake {

        @Test
        @DisplayName("★★ 네 칸이 다 찬 판만 0 으로 돌아간다")
        void resetsOnlyWhenComplete() {
            ZzalPiece piece = piece();
            count(piece, PieceEvent.FEED, 6);
            count(piece, PieceEvent.GAME, 5);
            count(piece, PieceEvent.CLEAN, 2);      // 청결은 아직 (5회가 목표)

            assertThat(piece.resetOnWakeIfComplete()).as("두 칸뿐이면 안 건드린다").isFalse();
            assertThat(piece.isDone(PieceKind.FOOD)).isTrue();
            assertThat(piece.countOf(PieceEvent.CLEAN)).as("진행도 그대로").isEqualTo(2);
        }

        @Test
        @DisplayName("★★ 네 칸이 다 찬 뒤 기상 — 도장도 횟수도 전부 0")
        void resetsEverythingWhenComplete() {
            ZzalPiece piece = piece();
            count(piece, PieceEvent.FEED, 6);
            count(piece, PieceEvent.GAME, 5);
            count(piece, PieceEvent.BATH, 2);
            count(piece, PieceEvent.CHAT, 5);
            count(piece, PieceEvent.PET, 2);        // 교감은 이미 찍혀서 안 세어진다

            // ★ 굽기가 이 완성을 가져간 뒤에만 비운다(1.9) — 안 쓰인 완성을 비우면
            //   이틀 걸려 채운 조각이 아무것도 남기지 않고 사라진다.
            piece.consume();

            assertThat(piece.resetOnWakeIfComplete()).isTrue();
            assertThat(piece.doneCount()).isZero();
            for (PieceEvent event : PieceEvent.values()) {
                assertThat(piece.countOf(event)).as("%s 횟수", event).isZero();
            }
        }

        @Test
        @DisplayName("★ 다 찬 판은 다음 기상까지 아무것도 안 센다 — 네 칸이 전부 찍혀 있으므로")
        void nothingCountsWhileComplete() {
            ZzalPiece piece = piece();
            count(piece, PieceEvent.FEED, 6);
            count(piece, PieceEvent.GAME, 5);
            count(piece, PieceEvent.BATH, 2);
            count(piece, PieceEvent.CHAT, 5);

            for (PieceEvent event : PieceEvent.values()) {
                assertThat(piece.count(event)).as("%s", event).isNull();
                assertThat(piece.countOf(event)).isZero();
            }
        }
    }

    @Nested
    @DisplayName("기분 좋은 날의 선물")
    class Bonus {

        @Test
        @DisplayName("★ 아직 안 찬 것 중 앞선 칸을 채운다")
        void grantsFirstOpen() {
            ZzalPiece piece = piece();
            count(piece, PieceEvent.FEED, 6);                   // FOOD 가 찼다

            assertThat(piece.firstOpen()).isEqualTo(PieceKind.PLAY);
            assertThat(piece.grant(PieceKind.PLAY)).isTrue();
            assertThat(piece.doneCount()).isEqualTo(2);
            assertThat(piece.firstOpen()).isEqualTo(PieceKind.CLEAN);
        }

        @Test
        @DisplayName("이미 찍힌 칸에는 아무 일도 없다")
        void grantOnDoneIsNoop() {
            ZzalPiece piece = piece();
            count(piece, PieceEvent.FEED, 6);
            assertThat(piece.grant(PieceKind.FOOD)).isFalse();
            assertThat(piece.doneCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("네 칸이 다 차 있으면 채울 칸이 없다")
        void noOpenWhenComplete() {
            ZzalPiece piece = piece();
            for (PieceKind kind : PieceKind.values()) {
                piece.grant(kind);
            }
            assertThat(piece.firstOpen()).isNull();
        }
    }

    @Nested
    @DisplayName("화면에 그릴 진행도")
    class Progress {

        @Test
        @DisplayName("★ 길이 둘인 칸에서는 가장 많이 간 길을 보여 준다")
        void showsNearestPath() {
            ZzalPiece piece = piece();
            count(piece, PieceEvent.CLEAN, 2);      // 2/5 = 0.4
            count(piece, PieceEvent.BATH, 1);       // 1/2 = 0.5  ← 이쪽이 더 가깝다

            assertThat(piece.progress().get(PieceKind.CLEAN)).containsExactly(1, 2);
        }

        @Test
        @DisplayName("찍힌 칸은 1/1 로 보인다")
        void doneShowsFull() {
            ZzalPiece piece = piece();
            count(piece, PieceEvent.FEED, 6);
            assertThat(piece.progress().get(PieceKind.FOOD)).containsExactly(1, 1);
        }
    }
}
