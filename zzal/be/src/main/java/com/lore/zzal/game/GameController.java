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
@Tag(name = "미니게임", description = "좌우 맞히기와 달리기. 두 게임 합산 하루 3매치이며 취침 시 초기화한다")
@RestController
@RequestMapping("/api/zzal/v1/me/pets/{petId}/games")
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

    @Operation(summary = "매치 시작", description = """
            kind 로 게임 종류를 지정한다. LEFT_RIGHT(좌우 맞히기)는 처음부터 사용할 수 있고,
            RUN(달리기)은 좌우 맞히기 5승 이후 해금된다.

            좌우 맞히기는 5회 중 3회를 맞히면 승리한다. 정답은 매치 시작 시 서버가 결정해
            보관하며 응답에 포함하지 않는다. 승리 시 행복 +1 을 부여한다.

            진행 중인 매치가 있으면 새로 생성하지 않고 해당 매치를 반환한다.
            일일 3매치는 두 게임 합산이며 시작 시점에 차감하고 취침 시 초기화한다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "매치 시작 또는 진행 중인 매치 반환"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "ZZAL_GAME_DAILY_LIMIT · ZZAL_SICK_REFUSES · ZZAL_FEATURE_LOCKED(RUN) · ZZAL_PET_SLEEPING")})
    @PostMapping
    public ApiResponse<GameResponses.State> start(@LoginUser Long userId, @PathVariable Long petId,
                                                  @Valid @RequestBody GameRequests.Start request) {
        GameService.Started s = gameService.start(userId, petId, request.kind(), Instant.now());
        return ApiResponse.ok(GameResponses.State.of(s, remaining(userId, petId)));
    }

    @Operation(summary = "좌우 맞히기 1회 진행", description = """
            선택한 방향을 전달하면 정답 여부를 서버가 판정한다. 응답에는 방금 진행한 회차의
            정답만 포함하며 남은 회차의 정답은 노출하지 않는다.

            5회를 모두 진행하면 finished 가 true 가 되고 그때 win 이 채워진다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "진행 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ZZAL_GAME_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "ZZAL_GAME_FINISHED · ZZAL_SICK_REFUSES · ZZAL_PET_SLEEPING")})
    @PostMapping("/{gameId}/guess")
    public ApiResponse<GameResponses.Guess> guess(@LoginUser Long userId, @PathVariable Long petId,
                                                  @PathVariable Long gameId,
                                                  @Valid @RequestBody GameRequests.Guess request) {
        GameService.GuessResult r = gameService.guess(userId, petId, gameId, request.pick().code(), Instant.now());
        return ApiResponse.ok(GameResponses.Guess.of(r, remaining(userId, petId)));
    }

    @Operation(summary = "달리기 종료", description = """
            달리기는 화면이 물리를 돌리고 서버는 살아남은 시간만 받는다. 30,000ms 이상이면 승리이며
            상한 60,000ms 로 잘린다. 승리 시 행복 +1 을 부여한다.

            좌우 맞히기는 이 API 로 끝내지 않는다(guess 로 5회를 진행하면 그때 끝난다).

            ★ 이 주소가 없으면 달리기를 시작한 사람은 그 판을 <b>끝낼 수가 없다</b> — 하루 3매치 중
            한 판이 깎인 채 finishedAt 이 비어 있어, 다음 시작이 계속 그 판을 돌려준다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "종료 처리됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "survivedMs 가 없거나 0~60,000 밖(INVALID_INPUT) · 좌우 맞히기 판(INVALID_INPUT)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "ZZAL_GAME_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "ZZAL_GAME_FINISHED · ZZAL_PET_SLEEPING")})
    @PostMapping("/{gameId}/finish")
    public ApiResponse<GameResponses.RunResult> finish(@LoginUser Long userId, @PathVariable Long petId,
                                                       @PathVariable Long gameId,
                                                       @Valid @RequestBody GameRequests.Finish request) {
        GameService.RunResult r = gameService.finish(userId, petId, gameId, request.survivedMs(), Instant.now());
        return ApiResponse.ok(GameResponses.RunResult.of(r, remaining(userId, petId)));
    }

    @Operation(summary = "진행 중인 매치 조회", description = """
            새로고침 등으로 화면이 초기화된 경우 진행 중인 매치를 이어받는다.
            진행 중인 매치가 없으면 playing 이 false 다.

            일일 매치 수는 시작 시점에 차감하므로, 이 API 가 없으면 새로고침 시
            차감된 매치를 이어서 진행할 수 없다.""")
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
