package com.lore.zzal.game;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import com.lore.zzal.game.dto.GameRequests;
import com.lore.zzal.game.dto.GameResponses;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.ZzalPet;
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
 * 미니게임 API v2(api-v2.md 1.7). 응답은 펫 상태가 아니라 게임 상태(결정기록 C17).
 */
@Tag(name = "미니게임", description = "좌·우 맞히기 + 달리기 — 합쳐 하루 3판, 잠들 때 리셋")
@RestController
@RequestMapping("/api/zzal/v2/me/pets/{petId}/games")
public class GameController {

    private final GameService gameService;
    private final PetService petService;

    public GameController(GameService gameService, PetService petService) {
        this.gameService = gameService;
        this.petService = petService;
    }

    private int remaining(Long userId, Long petId) {
        return gameService.remainingToday(petService.get(userId, petId));
    }

    @Operation(summary = "판 시작", description = """
            `kind` = LEFT_RIGHT · RUN. 진행 중인 판이 있으면 새로 만들지 않고 그것을 돌려준다.
            하루 3판은 두 게임 합산·시작한 판 기준·잠들 때 리셋. RUN 은 좌우 5승 뒤.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "시작(또는 치던 판)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "ZZAL_GAME_DAILY_LIMIT · ZZAL_SICK_REFUSES · ZZAL_FEATURE_LOCKED(RUN) · ZZAL_PET_SLEEPING")})
    @PostMapping
    public ApiResponse<GameResponses.State> start(@LoginUser Long userId, @PathVariable Long petId,
                                                  @Valid @RequestBody GameRequests.Start request) {
        GameService.Started s = gameService.start(userId, petId, request.kind(), Instant.now());
        return ApiResponse.ok(GameResponses.State.of(s, remaining(userId, petId)));
    }

    @Operation(summary = "좌우 한 판 치기", description = "응답에 방금 친 판의 답만 담긴다. 다섯 판을 다 치면 finished·win.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "한 판 침"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ZZAL_GAME_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ZZAL_GAME_FINISHED · ZZAL_PET_SLEEPING")})
    @PostMapping("/{gameId}/guess")
    public ApiResponse<GameResponses.Guess> guess(@LoginUser Long userId, @PathVariable Long petId,
                                                  @PathVariable Long gameId,
                                                  @Valid @RequestBody GameRequests.Guess request) {
        GameService.GuessResult r = gameService.guess(userId, petId, gameId, request.pick().code(), Instant.now());
        return ApiResponse.ok(GameResponses.Guess.of(r, remaining(userId, petId)));
    }

    @Operation(summary = "달리기 끝", description = "살아남은 ms. 30,000 이상이면 승리(행복 +1). 서버는 상한 60,000 만 검증.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "끝"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ZZAL_GAME_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "ZZAL_GAME_FINISHED")})
    @PostMapping("/{gameId}/finish")
    public ApiResponse<GameResponses.RunResult> finish(@LoginUser Long userId, @PathVariable Long petId,
                                                       @PathVariable Long gameId,
                                                       @Valid @RequestBody GameRequests.Finish request) {
        GameService.RunResult r = gameService.finish(userId, petId, gameId, request.survivedMs(), Instant.now());
        return ApiResponse.ok(new GameResponses.RunResult(r.game().getId(), r.game().getSurvivedMs(), r.win(),
                remaining(userId, petId), r.justUnlocked(), r.runUnlocked()));
    }

    @Operation(summary = "치던 판 잇기", description = "새로고침 복구용. 치던 판이 없으면 playing=false.")
    @GetMapping("/current")
    public ApiResponse<GameResponses.State> current(@LoginUser Long userId, @PathVariable Long petId) {
        // current() 가 정산을 먼저 하므로 남은 판수는 그 뒤에 읽는다(정산 전 값 방지 — 리뷰 반영).
        java.util.Optional<ZzalGame> playing = gameService.current(userId, petId, Instant.now());
        ZzalPet pet = petService.get(userId, petId);
        int remaining = gameService.remainingToday(pet);
        boolean run = pet.getLeftRightWins() >= com.lore.zzal.pet.ZzalRules.RUN_UNLOCK_LEFT_RIGHT_WINS;
        return ApiResponse.ok(playing
                .map(g -> GameResponses.State.of(g, remaining, java.util.List.of(), run))
                .orElseGet(() -> GameResponses.State.idle(remaining, run)));
    }
}
