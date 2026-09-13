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
    @Schema(description = "진행 중인 매치 상태. 정답은 포함하지 않는다")
    public record State(

            @Schema(description = "진행 중인 매치 존재 여부. false 이면 gameId·kind·round 가 비어 있다")
            boolean playing,

            @Schema(example = "12") Long gameId,

            @Schema(description = "LEFT_RIGHT · RUN", example = "LEFT_RIGHT") String kind,

            @Schema(description = "현재 회차(0부터). 0 이면 아직 진행 이력이 없다. 달리기는 항상 0", example = "0")
            Integer round,

            @Schema(description = "현재까지 맞힌 횟수", example = "0") Integer hits,

            @Schema(description = "한 매치의 총 회차", example = "5") int rounds,
            @Schema(description = "승리에 필요한 정답 수", example = "3") int winAt,

            @Schema(description = "오늘 남은 매치 수. 진행 중인 매치는 제외한 값이다", example = "4")
            int remainingToday,

            @Schema(description = "이번 시작으로 해금된 동작 seq") List<Integer> justUnlocked,
            @Schema(description = "달리기 해금 여부. 좌우 맞히기 5승이 조건이다") boolean runUnlocked) {

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

    @Schema(name = "GuessResult",
            description = "좌우 맞히기 1회 진행 결과. 방금 진행한 회차의 정답만 포함한다")
    public record Guess(

            @Schema(example = "12") Long gameId,

            @Schema(description = "방금 진행한 회차(0부터)", example = "0") int round,

            @Schema(description = "선택한 방향", example = "LEFT") Side pick,

            @Schema(description = "방금 진행한 회차의 정답. 남은 회차의 정답은 응답에 포함하지 않는다", example = "RIGHT")
            Side answer,

            @Schema(description = "정답 여부") boolean hit,

            @Schema(description = "현재까지 맞힌 횟수", example = "1") int hits,

            @Schema(description = "5회를 모두 진행했는지 여부") boolean finished,

            @Schema(description = "승리 여부. 매치가 끝났을 때만 채워지며 진행 중에는 null 이다. "
                    + "정답 수가 이미 승리 조건을 넘겼더라도 남은 회차를 진행할 유인을 유지하기 위해 미리 알리지 않는다")
            Boolean win,

            @Schema(description = "다음 회차(0부터). 매치가 끝났으면 null", example = "1") Integer nextRound,

            @Schema(description = "한 매치의 총 회차", example = "5") int rounds,
            @Schema(description = "승리에 필요한 정답 수", example = "3") int winAt,

            @Schema(description = "오늘 남은 매치 수", example = "4") int remainingToday,

            @Schema(description = "이번 회차로 해금된 동작 seq") List<Integer> justUnlocked,
            @Schema(description = "달리기 해금 여부. 이번 승리로 5승에 도달하면 true 로 바뀐다") boolean runUnlocked) {

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

    @Schema(description = "달리기 종료 결과")
    public record RunResult(
            @Schema(example = "12") Long gameId,
            @Schema(description = "생존 시간(ms). 상한 60,000 으로 잘린다", example = "31200") long survivedMs,
            @Schema(description = "승리 여부. 생존 시간 30,000ms 이상이면 승리한다") boolean win,
            @Schema(description = "오늘 남은 매치 수") int remainingToday,
            @Schema(description = "이번 매치로 해금된 동작 seq") List<Integer> justUnlocked,
            @Schema(description = "달리기 해금 여부") boolean runUnlocked) {

        public static RunResult of(GameService.RunResult r, int remainingToday) {
            ZzalGame game = r.game();
            return new RunResult(game.getId(),
                    game.getSurvivedMs() == null ? 0L : game.getSurvivedMs(), r.win(),
                    remainingToday, r.justUnlocked(), r.runUnlocked());
        }
    }
}
