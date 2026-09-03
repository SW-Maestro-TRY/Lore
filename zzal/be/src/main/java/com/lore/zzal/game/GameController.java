package com.lore.zzal.game;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import com.lore.zzal.game.dto.GameRequests;
import com.lore.zzal.game.dto.GameResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 좌·우 맞히기 API.
 *
 * 주소는 펫 API 와 같은 규칙이다 — 내 것은 {@code me} 밑. 주소에 남의 번호를 넣을 자리가
 * 없어서 남의 데이터를 건드리는 실수 자체가 불가능해진다.
 *
 * <h3>★ 왜 왕복이 다섯 번인가</h3>
 * 화면이 다섯 번을 혼자 치고 결과만 보내면 왕복이 한 번이지만, 그러면
 * <b>개발자도구로 이겼다고 말하면 그만이다.</b> 보상이 {@code NONE} 인 지금은 무해해 보여도
 * 켜는 순간 그게 무한 이득이 된다. 답은 서버가 쥐고 한 판에 하나씩만 공개한다.
 */
@Tag(name = "미니게임", description = "좌·우 맞히기 — 다섯 번 겨뤄 세 번 이상 맞히면 이긴다")
@RestController
@RequestMapping("/api/zzal/v1/me/pets/{petId}/games")
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @Operation(summary = "판 시작", description = """
            다섯 판의 답을 **서버가 뽑아 저장**하고, 화면에는 판 번호와 지금 몇 번째인지만 준다.

            - **답은 나가지 않는다.** 응답에 그 칸 자체가 없다
            - **두 번 눌러도 안전하다.** 치던 판이 있으면 새로 만들지 않고 그것을 그대로 돌려준다
              (새 판을 내주면 치던 판이 버려진 채 남아 하루 횟수만 먹는다)
            - 하루 횟수는 **시작한 판**으로 센다. 지는 판을 버리고 다시 시작하는 것을 막기 위해서다""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "시작(또는 치던 판)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "없는 펫 또는 남의 펫(ZZAL_PET_NOT_FOUND)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "오늘 횟수를 다 씀(ZZAL_GAME_DAILY_LIMIT)")})
    @PostMapping
    public ApiResponse<GameResponses.State> start(@LoginUser Long userId, @PathVariable Long petId) {
        Instant now = Instant.now();
        ZzalGame game = gameService.start(userId, petId, now);
        return ApiResponse.ok(GameResponses.State.of(game, gameService.remainingToday(userId, now)));
    }

    @Operation(summary = "한 판 치기", description = """
            어느 쪽을 골랐는지만 보낸다. 응답에 **방금 친 판의 답**과 맞힌 수, 다음 판이 담긴다.

            - 남은 판의 답은 담기지 않는다
            - 다섯 판을 다 치면 `finished` 가 true 가 되고 그때 `win` 이 채워진다.
              이긴 판에는 보상이 지급된다(지금 설정은 NONE 이라 기록만 남는다)
            - 끝난 판에 또 치면 409 다 — 안 막으면 이긴 판을 다시 쳐서 보상을 반복해 받는다""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "한 판 침"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "없는 펫 또는 남의 펫(ZZAL_PET_NOT_FOUND) · 없는 판 또는 남의 판(ZZAL_GAME_NOT_FOUND)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "이미 끝난 판(ZZAL_GAME_FINISHED)")})
    @PostMapping("/{gameId}/guess")
    public ApiResponse<GameResponses.Guess> guess(@LoginUser Long userId,
                                                  @PathVariable Long petId,
                                                  @PathVariable Long gameId,
                                                  @Valid @RequestBody GameRequests.Guess request) {
        Instant now = Instant.now();
        GameService.GuessResult result = gameService.guess(userId, petId, gameId, request.pick().code(), now);
        return ApiResponse.ok(GameResponses.Guess.of(result, gameService.remainingToday(userId, now)));
    }

    @Operation(summary = "치던 판 잇기", description = """
            새로고침 복구용. 치던 판이 있으면 그 판과 **지금 몇 번째인지**를 돌려준다.

            ★ 다섯 왕복짜리 놀이라 중간에 새로고침이 반드시 일어난다. 이게 없으면 그 사람은
            치던 판을 다시 못 잡는다. 치던 판이 없으면 `playing` 이 false 다(에러가 아니다).""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "조회 성공(치던 판이 없어도 200)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "없는 펫 또는 남의 펫(ZZAL_PET_NOT_FOUND)")})
    @GetMapping("/current")
    public ApiResponse<GameResponses.State> current(@LoginUser Long userId, @PathVariable Long petId) {
        Instant now = Instant.now();
        int remaining = gameService.remainingToday(userId, now);
        return ApiResponse.ok(gameService.current(userId, petId)
                .map(g -> GameResponses.State.of(g, remaining))
                .orElseGet(() -> GameResponses.State.idle(remaining)));
    }
}
