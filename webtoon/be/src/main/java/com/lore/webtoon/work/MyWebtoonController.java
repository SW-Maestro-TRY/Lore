package com.lore.webtoon.work;

import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.job.NotifySettingService;
import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 로그인한 사람의 웹툰.
 *
 * <h2>여기는 응답을 감싼다</h2>
 *
 * 옛 프록시는 하네스가 준 것을 그대로 흘려보냈지만(2026-09-12 제거),
 * 이 주소들은 <b>자바가 뜻을 갖고 판단하는 것</b>이라 저장소 규약대로
 * {@code ApiResponse} 로 감싼다 — 로그인이 필요하고, 계정과 브라우저를 잇는
 * 판단이 여기서 일어난다.
 *
 * <h2>로그인이 필요한 유일한 웹툰 주소다</h2>
 *
 * 나머지 {@code /api/webtoon/v1/**} 는 게스트도 부를 수 있게 열려 있다
 * (common 의 WebSecurityConfig). {@code /api/webtoon/v1/my/**} 만 그 앞에서
 * 잠근다 — 로그인 안 한 사람에게는 "내" 라는 말이 성립하지 않는다.
 */
@Tag(name = "Webtoon", description = "웹툰 스튜디오")
@RestController
@RequestMapping(MyWebtoonController.PREFIX)
public class MyWebtoonController {

    static final String PREFIX = WebtoonApi.V1 + "/my";

    private final MyWebtoonService service;
    private final NotifySettingService notifySettings;
    private final RunLikeService likes;
    private final RunTrash trash;

    public MyWebtoonController(MyWebtoonService service, NotifySettingService notifySettings,
                               RunLikeService likes, RunTrash trash) {
        this.service = service;
        this.notifySettings = notifySettings;
        this.likes = likes;
        this.trash = trash;
    }

    @Operation(summary = "찜하기", description = """
            이 작품을 내 찜 목록에 넣는다. 이미 찜했으면 그대로 두고 지금 찜 수만 돌려준다 —
            두 번 눌러도 한 번이다(#247).""")
    @PostMapping("/runs/{runId}/like")
    public ApiResponse<LikeResult> like(@LoginUser Long userId, @PathVariable String runId) {
        return ApiResponse.ok(new LikeResult(runId, true, likes.like(userId, runId)));
    }

    @Operation(summary = "찜 취소", description = "찜한 적이 없어도 오류 없이 지금 찜 수를 돌려준다.")
    @DeleteMapping("/runs/{runId}/like")
    public ApiResponse<LikeResult> unlike(@LoginUser Long userId, @PathVariable String runId) {
        return ApiResponse.ok(new LikeResult(runId, false, likes.unlike(userId, runId)));
    }

    @Operation(summary = "내가 찜한 웹툰", description = """
            최근에 찜한 것부터. 모양은 둘러보기 목록과 같고 liked=true 가 붙는다.""")
    @GetMapping("/likes")
    public ApiResponse<List<Map<String, Object>>> likes(@LoginUser Long userId) {
        return ApiResponse.ok(likes.likedCards(userId));
    }

    @Operation(summary = "이 목록 중 내가 찜한 것", description = """
            둘러보기 카드에 하트를 칠하려고 부른다. 로그인 없이는 빈 목록이다.""")
    @PostMapping("/likes/among")
    public ApiResponse<List<String>> likedAmong(@LoginUser Long userId, @RequestBody AmongRequest request) {
        return ApiResponse.ok(likes.likedAmong(userId, request.runIds() == null ? List.of() : request.runIds()));
    }

    /** @param likes 바뀐 뒤의 찜 수 */
    public record LikeResult(String runId, boolean liked, long likes) {
    }

    public record AmongRequest(List<String> runIds) {
    }

    @Operation(summary = "이 브라우저를 내 계정에 잇기", description = """
            로그인할 때마다 부른다. 기기를 바꾸면 브라우저 uid 가 새로 생기므로
            한 번만 잇는 것으로는 두 번째 기기가 안 붙는다.

            이미 이어져 있으면 아무 일도 안 하고 linked=false 를 준다.""")
    @PostMapping("/link")
    public ApiResponse<LinkResult> link(@LoginUser Long userId,
                                        @jakarta.validation.Valid @RequestBody LinkRequest request) {
        return ApiResponse.ok(new LinkResult(service.link(userId, request.uid())));
    }

    @Operation(summary = "내가 만든 웹툰", description = """
            내 계정에 이어진 브라우저들이 만든 작품 전부. 나만 보기로 내려 둔
            것도 포함한다 — 내 목록이라서다.

            모양은 둘러보기 목록(GET /api/webtoon/v1/runs)과 같다.""")
    @GetMapping("/runs")
    public ApiResponse<List<Map<String, Object>>> runs(@LoginUser Long userId) {
        return ApiResponse.ok(service.myRuns(userId));
    }

    @Operation(summary = "공개 / 비공개", description = """
            내 계정에 이어진 브라우저가 만든 작품만 바꿀 수 있다.

            비공개로 내리면 둘러보기에서 빠지고 내 목록에는 그대로 남는다.""")
    @PostMapping("/runs/{runId}/visibility")
    public ApiResponse<VisibilityResult> visibility(@LoginUser Long userId,
                                                    @PathVariable String runId,
                                                    @RequestBody VisibilityRequest request) {
        return ApiResponse.ok(
                new VisibilityResult(runId, service.setVisibility(userId, runId, request.isPublic())));
    }

    @Operation(summary = "그림 다시 올리기", description = """
            다 그려졌는데 결과 화면이 비어 있는 작품을 살린다.

            **다시 그리지 않는다.** 이미 그려져 있는 그림을 S3 에 올리고 DB 에
            적기만 하므로 돈이 안 나간다. 여러 번 불러도 되고, 이미 적힌 장은
            건너뛴다(recorded=0 이면 이미 다 있었다는 뜻).

            내 계정에 이어진 브라우저가 만든 작품만 된다 — 공개 전환과 같은 기준.""")
    @PostMapping("/runs/{runId}/reupload")
    public ApiResponse<ReuploadResult> reupload(@LoginUser Long userId,
                                                @PathVariable String runId) {
        return ApiResponse.ok(new ReuploadResult(runId, service.reupload(userId, runId)));
    }

    @Operation(summary = "내 작품 지우기 (휴지통)", description = """
            바로 지우지 않고 휴지통에 넣는다(#157). 넣은 작품은 내 목록·둘러보기·찜 목록에서
            빠지고, 결과·장 주소도 404 가 된다. 그림은 비공개 자리로 옮긴다.

            keepDays 일(기본 30일) 안에는 POST /my/runs/{runId}/restore 로 되살릴 수 있고,
            그 뒤에는 그림(S3)과 행이 영구 삭제된다(purgeAt).

            · 내 계정에 이어진 브라우저가 만든 작품만 된다 — 공개 전환과 같은 기준(아니면 403)
            · 예시 작품은 못 지운다(403) · 없는 작품은 404
            · 만드는 중인 작품은 먼저 「만들기 중단」을 한 뒤에 지울 수 있다(400)
            · 이미 휴지통에 있으면 그대로 두고 같은 값을 돌려준다""")
    @DeleteMapping("/runs/{runId}")
    public ApiResponse<RunTrash.Trashed> delete(@LoginUser Long userId, @PathVariable String runId) {
        return ApiResponse.ok(trash.trash(userId, runId));
    }

    @Operation(summary = "휴지통", description = """
            내가 지운 작품. 최근에 지운 것부터. 모양은 내 목록과 같고 deleted_at ·
            purge_at(이 시각이 지나면 영구 삭제)이 붙는다.""")
    @GetMapping("/trash")
    public ApiResponse<TrashList> trashList(@LoginUser Long userId) {
        return ApiResponse.ok(new TrashList(trash.keepDays(), trash.trashOf(userId)));
    }

    @Operation(summary = "휴지통에서 되살리기", description = """
            내 목록으로 돌려놓는다. 공개였던 작품은 둘러보기에도 다시 뜬다.
            휴지통에 없던 작품이면 restored=false 를 준다.""")
    @PostMapping("/runs/{runId}/restore")
    public ApiResponse<RestoreResult> restore(@LoginUser Long userId, @PathVariable String runId) {
        return ApiResponse.ok(new RestoreResult(runId, trash.restore(userId, runId)));
    }

    /** @param keepDays 휴지통에 둔 뒤 되살릴 수 있는 날 수 */
    public record TrashList(int keepDays, List<Map<String, Object>> runs) {
    }

    public record RestoreResult(String runId, boolean restored) {
    }

    @Operation(summary = "웹툰 완성 메일 — 켜져 있는가", description = """
            행이 없으면(한 번도 안 건드렸으면) true 다 — 지금까지 계정 이메일로
            항상 보냈던 것과 같은 기본값이다.""")
    @GetMapping("/notify-setting")
    public ApiResponse<NotifySettingResult> notifySetting(@LoginUser Long userId) {
        return ApiResponse.ok(new NotifySettingResult(notifySettings.isOn(userId)));
    }

    @Operation(summary = "웹툰 완성 메일 — 켜고 끄기", description = """
            웹툰이 다 만들어졌을 때 계정 이메일로 알리는 것을 켜고 끈다.
            게스트로 만들 때 직접 적은 주소는 이 설정과 무관하게 그대로 간다 —
            그건 그 한 번만의 명시적인 선택이다.""")
    @PostMapping("/notify-setting")
    public ApiResponse<NotifySettingResult> setNotifySetting(@LoginUser Long userId,
                                                             @RequestBody NotifySettingRequest request) {
        return ApiResponse.ok(new NotifySettingResult(notifySettings.set(userId, request.on())));
    }

    /** @param recorded 이번에 새로 적은 그림 줄 수. 0 이면 이미 다 적혀 있었다. */
    public record ReuploadResult(String runId, int recorded) {
    }

    public record NotifySettingResult(boolean on) {
    }

    public record NotifySettingRequest(boolean on) {
    }

    /** @param isPublic 바뀐 뒤의 상태. 화면은 이 값으로 스위치를 맞춘다. */
    public record VisibilityResult(
            String runId,
            @com.fasterxml.jackson.annotation.JsonProperty("public") boolean isPublic) {
    }

    /** @param isPublic 둘러보기에 거는가. JSON 키는 {@code public} 이다(자바 예약어라 이름만 다름). */
    public record VisibilityRequest(
            @com.fasterxml.jackson.annotation.JsonProperty("public") boolean isPublic) {
    }

    public record LinkRequest(
            @Schema(description = "브라우저가 들고 다니는 값. 프론트 localStorage 의 lore_uid",
                    example = "umt747mfwy4k8hbj8")
            @NotBlank String uid) {
    }

    /** @param linked 이번에 새로 이었는가. 이미 이어져 있었으면 false. */
    public record LinkResult(boolean linked) {
    }
}
