package com.lore.zzal.game.dto;

import com.lore.zzal.game.GameKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 좌·우 맞히기 API 가 받는 것들. */
public final class GameRequests {

    private GameRequests() {
    }

    /**
     * 좌·우.
     *
     * ★ 저장은 'L'·'R' 한 글자인데 주고받는 것은 {@code LEFT}·{@code RIGHT} 다. 밖으로 나가는
     *   이름은 읽어서 뜻을 알 수 있어야 하고("L" 은 Left 인지 Lose 인지 모른다), 저장은
     *   다섯 판이 문자열 한 칸에 들어가는 편이 다루기 쉽다. 그 둘을 여기서 잇는다.
     */
    public enum Side {

        LEFT('L'),
        RIGHT('R');

        private final char code;

        Side(char code) {
            this.code = code;
        }

        public char code() {
            return code;
        }

        public static Side of(char code) {
            return code == 'L' ? LEFT : RIGHT;
        }
    }

    @Schema(name = "GuessRequest",
            description = "좌우 맞히기 요청. 선택한 방향만 전달하고 정답 여부는 서버가 판정한다")
    public record Guess(

            @Schema(description = "LEFT(왼쪽) · RIGHT(오른쪽)", example = "LEFT")
            @NotNull Side pick,
            @Schema(description="동일 회차 재전송 identity. expectedRound와 함께 전달") java.util.UUID requestId,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(4)
            @Schema(description="선택한 회차(0부터). requestId와 함께 전달") Integer expectedRound) {
        public Guess(Side pick) { this(pick,null,null); }
        @jakarta.validation.constraints.AssertTrue(message="requestId and expectedRound must be supplied together")
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isIdentityPairValid(){return (requestId==null)==(expectedRound==null);}
    }

    @Schema(description = "매치 시작 요청")
    public record Start(
            @Schema(description = "LEFT_RIGHT(좌우 맞히기) · RUN(달리기, 좌우 5승 이후 해금)", example = "LEFT_RIGHT")
            @NotNull GameKind kind, com.lore.zzal.pet.FirstIntro.Step firstIntroStep) {
        public Start(GameKind kind) { this(kind,null); }
    }

    @Schema(description = "달리기 종료 요청. 생존 시간 30,000ms 이상이면 승리하며 서버는 상한 60,000ms 만 검증한다")
    public record Finish(
            @Schema(example = "31200") @NotNull @Min(0) @Max(60_000) Long survivedMs) {
    }
}
