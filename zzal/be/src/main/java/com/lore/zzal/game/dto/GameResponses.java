package com.lore.zzal.game.dto;

import com.lore.zzal.game.GameService;
import com.lore.zzal.game.ZzalGame;
import com.lore.zzal.game.dto.GameRequests.Side;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 좌·우 맞히기 API 가 돌려주는 것들.
 *
 * <h3>★★ 여기에 답을 담을 칸이 없다</h3>
 * 남은 판의 답은 <b>어느 응답에도 자리가 없다.</b> 있어야 할 것만 칸을 만들어 두면,
 * 나중에 누가 "화면에서 미리 그리기 편하니까" 싣는 일이 애초에 일어나지 않는다.
 * {@link Guess#answer} 하나만 답인데, 그건 이미 친 판의 것이고
 * {@code GameService} 가 저장된 답을 읽지 않고 되돌려 만든 값이다.
 */
public final class GameResponses {

    private GameResponses() {
    }

    /**
     * 지금 판의 상태 — 시작과 새로고침 복구가 같은 모양으로 답한다.
     *
     * ★ 두 API 가 같은 모양인 것은 화면을 위해서다. 새로고침으로 들어온 사람과 방금 시작한
     *   사람이 다른 응답을 받으면, 화면이 "지금 어느 쪽이지" 를 판단하게 된다.
     */
    @Schema(description = "지금 치고 있는 판. 답은 들어 있지 않다")
    public record State(

            @Schema(description = "치고 있는 판이 있는가. false 면 아래 세 칸이 비어 있다")
            boolean playing,

            @Schema(example = "12") Long gameId,

            @Schema(description = "LEFT_RIGHT · RUN", example = "LEFT_RIGHT") String kind,

            @Schema(description = "지금 몇 번째 판인가(0부터). 0 이면 아직 한 번도 안 쳤다. 달리기는 0", example = "0")
            Integer round,

            @Schema(description = "지금까지 몇 번 맞혔나", example = "0") Integer hits,

            @Schema(description = "한 판에 몇 번 겨루나", example = "5") int rounds,
            @Schema(description = "몇 번 이상 맞히면 이기나", example = "3") int winAt,

            @Schema(description = "오늘 더 할 수 있는 판 수(지금 치고 있는 판은 뺀 값)", example = "4")
            int remainingToday,

            @Schema(description = "이번 시작으로 열린 2층 동작 seq(13번 놀라기 = 3판). 행동 응답 = 상태") List<Integer> justUnlocked,
            @Schema(description = "달리기가 열려 있는가(좌우 5승)") boolean runUnlocked) {

        public static State of(GameService.Started s, int remainingToday) {
            return of(s.game(), remainingToday, s.justUnlocked(), s.runUnlocked());
        }

        public static State of(ZzalGame game, int remainingToday, List<Integer> justUnlocked, boolean runUnlocked) {
            return new State(true, game.getId(), game.getKind().name(), game.round(), game.getHits(),
                    ZzalGame.ROUNDS, ZzalGame.WIN_AT, remainingToday, justUnlocked, runUnlocked);
        }

        /** 치던 판이 없을 때. 화면은 이걸 보고 "시작" 을 그린다. */
        public static State idle(int remainingToday, boolean runUnlocked) {
            return new State(false, null, null, null, null,
                    ZzalGame.ROUNDS, ZzalGame.WIN_AT, remainingToday, List.of(), runUnlocked);
        }
    }

    @Schema(description = "한 판 친 결과 — 방금 친 판의 답만 들어 있다")
    public record Guess(

            @Schema(example = "12") Long gameId,

            @Schema(description = "방금 친 판(0부터)", example = "0") int round,

            @Schema(description = "내가 고른 쪽", example = "LEFT") Side pick,

            @Schema(description = "★ 방금 친 판의 답. 남은 판의 답은 어디에도 없다", example = "RIGHT")
            Side answer,

            @Schema(description = "맞혔는가") boolean hit,

            @Schema(description = "지금까지 맞힌 수", example = "1") int hits,

            @Schema(description = "다섯 판을 다 쳤는가") boolean finished,

            @Schema(description = "이겼는가. **끝났을 때만** 채워진다. 아직 치는 중에는 null 이다 — "
                    + "맞힌 수가 이미 3이어도 '이겼다' 를 미리 알려주면 남은 판을 칠 이유가 사라진다")
            Boolean win,

            @Schema(description = "다음에 칠 판(0부터). 끝났으면 null", example = "1") Integer nextRound,

            @Schema(description = "한 판에 몇 번 겨루나", example = "5") int rounds,
            @Schema(description = "몇 번 이상 맞히면 이기나", example = "3") int winAt,

            @Schema(description = "오늘 더 할 수 있는 판 수", example = "4") int remainingToday,

            @Schema(description = "이번 판으로 열린 2층 동작 seq") List<Integer> justUnlocked,
            @Schema(description = "달리기가 열려 있는가(이 판의 승리로 5승이 됐으면 여기서 true 로 바뀐다)") boolean runUnlocked) {

        public static Guess of(GameService.GuessResult r, int remainingToday) {
            ZzalGame game = r.game();
            boolean finished = game.isFinished();
            return new Guess(
                    game.getId(),
                    r.round(),
                    Side.of(r.pick()),
                    Side.of(r.answer()),
                    r.hit(),
                    game.getHits(),
                    finished,
                    finished ? game.isWin() : null,
                    finished ? null : game.round(),
                    ZzalGame.ROUNDS,
                    ZzalGame.WIN_AT,
                    remainingToday,
                    r.justUnlocked(),
                    r.runUnlocked());
        }
    }

    @Schema(description = "달리기 끝 결과")
    public record RunResult(
            @Schema(example = "12") Long gameId,
            @Schema(description = "살아남은 ms(상한 60,000 으로 잘림)", example = "31200") long survivedMs,
            @Schema(description = "30,000 이상이면 승리") boolean win,
            @Schema(description = "오늘 더 할 수 있는 판 수") int remainingToday,
            @Schema(description = "이번 판으로 열린 2층 동작 seq") List<Integer> justUnlocked,
            @Schema(description = "달리기가 열려 있는가") boolean runUnlocked) {
    }
}
