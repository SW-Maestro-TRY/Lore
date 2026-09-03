package com.lore.zzal.game.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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

    @Schema(description = "한 판 치기 — 어느 쪽을 골랐는지만 보낸다. 맞았는지는 서버가 정한다")
    public record Guess(

            @Schema(description = "LEFT(왼쪽) · RIGHT(오른쪽)", example = "LEFT")
            @NotNull Side pick) {
    }
}
