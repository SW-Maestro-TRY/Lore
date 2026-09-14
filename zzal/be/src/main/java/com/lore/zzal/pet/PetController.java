package com.lore.zzal.pet;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.share.ShareService;
import com.lore.zzal.share.dto.ShareResponses;
import com.lore.zzal.pet.dto.PetRequests;
import com.lore.zzal.pet.dto.PetResponses;
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
import java.util.List;

/**
 * 펫 API v2 — 계약은 {@code zzal/docs/api-v2.md}.
 *
 * 주소 규칙 — 내 것은 `me` 밑. 주소에 남의 번호를 넣을 자리가 없어서 남의 데이터를 건드리는 실수가 불가능하다.
 * <b>모든 행동(POST)의 응답 = `PetDetail` 최신 상태.</b> 화면은 누른 뒤 다시 조회하지 않는다.
 * 채팅·동작 seen·앨범·미니게임 v2 는 PR-4·5 에서 이 밑에 붙는다.
 */
@Tag(name = "펫", description = "캐릭터 생성·조회·돌보기·취침·성격·배경·공유")
@RestController
@RequestMapping("/api/zzal/v1/me/pets")
public class PetController {

    private final PetService petService;
    private final MotionCatalog catalog;
    private final ShareService shareService;

    public PetController(PetService petService, MotionCatalog catalog, ShareService shareService) {
        this.petService = petService;
        this.catalog = catalog;
        this.shareService = shareService;
    }

    private PetResponses.Detail detail(ZzalPet pet, String stepLabel, Instant real) {
        return PetResponses.Detail.from(pet, stepLabel, pet.now(real), catalog,
                petService.motionRows(pet.getId()), List.of(), false, petService.scenes(pet.getId()),
                petService.pieces(pet.getId()));
    }

    private PetResponses.Detail detail(PetService.Action action, Instant real) {
        ZzalPet pet = action.pet();
        return PetResponses.Detail.from(pet, null, pet.now(real), catalog,
                petService.motionRows(pet.getId()), action.justUnlocked(), action.justHealed(),
                petService.scenes(pet.getId()), petService.pieces(pet.getId()));
    }

    @Operation(summary = "이미지 등록", description = """
            그림을 등록하고 부화를 시작한다. 시트뿐 아니라 격자와 후처리까지
            부화 전 단계가 이 요청으로 시작되며, 생성은 백그라운드에서
            수행되므로 요청은 즉시 응답한다.

            캐릭터 정보(이름·성격)는 별도 API로 등록한다. 이름은 생성의 입력이
            아니라 완료 조건이다 — 생성이 끝나고 이름도 들어온 순간 캐릭터가
            깨어난다. 그래서 사용자가 이름을 입력하는 동안 생성이 함께 진행되어
            체감 시간이 줄어든다.

            이름을 입력하지 않고 이탈한 뒤 재진입하면 기존 초안을 반환한다.
            이미 생성된 결과를 재사용하여 중복 비용을 방지한다.

            imageKey 는 presign API 로 발급받은 미사용 키여야 한다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "초안 생성"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "유효하지 않은 이미지 키(INVALID_UPLOAD_KEY) · 사용 완료된 키(UPLOAD_KEY_ALREADY_USED)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "부화 진행 중(ZZAL_PET_ALREADY_HATCHING) · 슬롯 부족(ZZAL_PET_LIMIT_REACHED)")})
    @PostMapping("/draft")
    public ApiResponse<PetResponses.Drafted> draft(@LoginUser Long userId,
                                                   @Valid @RequestBody PetRequests.Draft request) {
        ZzalPet pet = petService.draft(userId, request.imageKey(), Instant.now());
        return ApiResponse.ok(new PetResponses.Drafted(pet.getId()));
    }

    @Operation(summary = "캐릭터 정보 등록", description = """
            이름과 성격·세계관·말투·장르를 저장한다. 생성은 이 요청으로 시작되지 않는다 —
            이미지 등록 시점에 이미 시작되어 있고, 이 요청은 이름을 채운다.
            생성이 이미 끝나 있었다면 이 시점에 캐릭터가 깨어난다.

            그림 생성에 반영되는 입력은 없다. 격자 프롬프트가 사용하는 외형 정보는 등록한
            그림에서만 추출하며, 성격·세계관·말투·장르·note(자유 메모)는 모두 저장만 되고
            대사 생성에 사용한다.

            이름을 제외한 항목은 모두 선택이다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "부화 시작"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "존재하지 않거나 소유자가 다른 캐릭터(ZZAL_PET_NOT_FOUND)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "이미 캐릭터 정보가 등록됨(ZZAL_PET_NOT_DRAFT)")})
    @PostMapping("/draft/{petId}/character")
    public ApiResponse<PetResponses.Created> character(@LoginUser Long userId,
                                                       @PathVariable Long petId,
                                                       @Valid @RequestBody PetRequests.Character request) {
        Instant now = Instant.now();
        ZzalPet pet = petService.character(userId, petId, request.name(), request.note(),
                request.picked(), request.world(), request.tone(), request.genre(), now);
        return ApiResponse.ok(PetResponses.Created.from(pet, ZzalRules.HATCH_ESTIMATE.toSeconds()));
    }

    @Operation(summary = "부화 진행 조회", description = """
            부화 대기 화면이 주기적으로 폴링하는 API 다. 응답을 가볍게 유지하기 위해
            게이지·동작 목록은 포함하지 않는다. 전체 상태가 필요하면 상태 조회 API 를 사용한다.

            실패 시 message 에는 재시도 가능 여부만 담고 실패 원인은 노출하지 않는다.""")
    @GetMapping("/{petId}/hatch")
    public ApiResponse<PetResponses.Hatch> hatch(@LoginUser Long userId, @PathVariable Long petId) {
        return ApiResponse.ok(petService.hatchProgress(userId, petId, Instant.now()));
    }

    @Operation(summary = "내 캐릭터 목록 조회", description = """
            로그인한 사용자의 캐릭터 목록을 반환한다. 슬롯이 1개이므로 통상 0건 또는 1건이다.

            목록이 비어 있으면 온보딩으로, 비어 있지 않으면 캐릭터 화면으로 분기한다.""")
    @GetMapping
    public ApiResponse<List<PetResponses.Detail>> list(@LoginUser Long userId) {
        Instant real = Instant.now();
        List<PetResponses.Detail> pets = petService.refreshAll(userId, real).stream()
                .map(p -> detail(p, petService.currentStepLabel(p.getId()), real))
                .toList();
        return ApiResponse.ok(pets);
    }

    @Operation(summary = "캐릭터 상태 조회", description = """
            부화 중과 진행 중을 구분하지 않고 이 API 하나로 응답한다. 화면 구성에 필요한
            시계·게이지·동작 목록·튜토리얼 진행이 모두 포함된다.

            조회 시점에 경과 시간을 정산한다. 취침 시각이 지났으면 수면 상태로,
            기상 시각이 지났으면 기상 상태로 갱신된 결과가 반환된다.
            그날 첫 조회이면 함께한 날이 1 증가한다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "존재하지 않거나 소유자가 다른 캐릭터(ZZAL_PET_NOT_FOUND)")})
    @GetMapping("/{petId}")
    public ApiResponse<PetResponses.Detail> detail(@LoginUser Long userId, @PathVariable Long petId) {
        Instant real = Instant.now();
        ZzalPet pet = petService.refresh(userId, petId, real);
        return ApiResponse.ok(detail(pet, petService.currentStepLabel(petId), real));
    }

    // ── 돌보기 (설계 규칙) ───────────────────────────────────────────────

    @Operation(summary = "돌보기", description = """
            수행할 행동만 전달하면 수치 변화와 수행 가능 여부를 서버가 판정한다.
            응답은 변경된 전체 상태이므로 화면은 별도 재조회 없이 그대로 반영한다.

            | action | 효과 | 거부 조건 |
            |---|---|---|
            | FEED | 배부름 +1, 재고 -1 | 배부름이 최대치일 때 |
            | SNACK | 행복 +1 | 질병 상태 · 연속 5회 시 배탈 발생 |
            | PET | 친밀도 +5 (하루 3회까지) | 없음 |
            | CLEAN | 흔적 제거 | 이미 청결한 상태일 때 |
            | BATH | 흔적 제거, 행복 +1 | 하루 1회 초과 |
            | MEDICINE | 질병 즉시 치료 | 질병 상태가 아닐 때 |

            FEED·CLEAN·BATH·MEDICINE 은 친밀도 +5 를 부여하며 하루 합산 30 이 상한이다.

            응답의 justUnlocked 에 이번 행동으로 해금된 동작 seq 가 담긴다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "돌보기 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "존재하지 않거나 소유자가 다른 캐릭터(ZZAL_PET_NOT_FOUND)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "ZZAL_PET_NOT_ALIVE · ZZAL_PET_SLEEPING · ZZAL_NO_FOOD · ZZAL_CARE_NOT_NEEDED "
                            + "· ZZAL_BATH_DONE_TODAY · ZZAL_SICK_REFUSES")})
    @PostMapping("/{petId}/care")
    public ApiResponse<PetResponses.Detail> care(@LoginUser Long userId,
                                                 @PathVariable Long petId,
                                                 @Valid @RequestBody PetRequests.Care request) {
        Instant real = Instant.now();
        return ApiResponse.ok(detail(petService.care(userId, petId, request.action(), real), real));
    }

    // ── 잠 (설계 규칙) ──────────────────────────────────────────────────

    @Operation(summary = "재우기", description = """
            KST 19:00~23:00 구간에 수행한다. 23:00 까지 수행하지 않으면 자동으로 수면 상태가 되며,
            자동 취침에는 보상이 없다. 수동 취침은 행복 +1, 친밀도 +10 을 부여한다.

            수면 중에는 게이지 감소가 멈추고 재고만 충전된다. 야간 취침은 하루의 경계이며
            이 시점에 일일 카운터 초기화와 케어 미스 판정이 함께 수행된다.

            튜토리얼 진행 중에는 8단계 차례에만 수행할 수 있고 낮잠으로 처리한다.
            낮잠은 대기 없이 즉시 기상할 수 있으며 보상은 없다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "취침 처리"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "허용 구간이 아님(ZZAL_NOT_SLEEP_TIME) · 이미 수면 중(ZZAL_PET_SLEEPING)")})
    @PostMapping("/{petId}/sleep")
    public ApiResponse<PetResponses.Detail> sleep(@LoginUser Long userId, @PathVariable Long petId) {
        Instant real = Instant.now();
        return ApiResponse.ok(detail(petService.sleep(userId, petId, real), real));
    }

    @Operation(summary = "깨우기", description = """
            KST 07:00~10:00 구간에 수행한다. 10:00 까지 수행하지 않으면 자동으로 기상 처리되며,
            이 경우 overslept 가 true 로 표시되고 보상이 없다. 수동 기상은 친밀도 +10 을 부여한다.

            튜토리얼 낮잠은 시간 제약 없이 즉시 기상할 수 있으며 보상은 없다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "기상 처리"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "수면 상태가 아님(ZZAL_PET_NOT_SLEEPING) · 허용 구간이 아님(ZZAL_NOT_WAKE_TIME)")})
    @PostMapping("/{petId}/wake")
    public ApiResponse<PetResponses.Detail> wake(@LoginUser Long userId, @PathVariable Long petId) {
        Instant real = Instant.now();
        return ApiResponse.ok(detail(petService.wake(userId, petId, real), real));
    }

    // ── 성격·배경·공유 (설계 규칙) ───────────────────────────────────

    @Operation(summary = "성격 등록·수정", description = """
            성격 5종(온순·활발·수줍음·응석·시크)과 세계관 한 줄을 저장한다.
            수면 중을 포함해 언제든 변경할 수 있다.

            성격은 대사 톤에만 반영되며 그림 생성에는 사용하지 않는다.
            튜토리얼 4단계는 이 API 호출로 완료 처리된다.""")
    @PostMapping("/{petId}/personality")
    public ApiResponse<PetResponses.Detail> personality(@LoginUser Long userId,
                                                        @PathVariable Long petId,
                                                        @Valid @RequestBody PetRequests.PersonalityChoice request) {
        Instant real = Instant.now();
        return ApiResponse.ok(detail(
                petService.choosePersonality(userId, petId, request.picked(), request.world(), real), real));
    }

    @Operation(summary = "아이 정보 확인(튜토리얼 4칸)", description = """
            튜토리얼 4칸("이 성격이 맞나요")을 넘긴다. 성격을 고치지 않고 아이 정보를 열어 보기만 해도
            넘어간다.

            화면이 혼자 넘기면 안 된다. 서버가 4칸에 남아 있으면 그다음 행동(청소)이 무시돼
            튜토리얼이 막히고, 첫 흔적이 이 칸을 넘길 때 생기므로 바닥이 깨끗해 5칸(청소)에서 또 막힌다.

            지금 칸이 4칸이 아니면 ZZAL_TUTORIAL_STEP_MISMATCH 다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "다음 칸으로"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "지금 칸이 아님(ZZAL_TUTORIAL_STEP_MISMATCH) · 이미 끝남(ZZAL_TUTORIAL_ALREADY_DONE)")})
    @PostMapping("/{petId}/tutorial/seen")
    public ApiResponse<PetResponses.Detail> tutorialSeen(@LoginUser Long userId, @PathVariable Long petId) {
        Instant real = Instant.now();
        return ApiResponse.ok(detail(petService.tutorialSeen(userId, petId, real), real));
    }

    @Operation(summary = "배경 변경", description = """
            해금 동작 4종이 열린 뒤부터 사용할 수 있다. 그 전에는 ZZAL_FEATURE_LOCKED 를 반환한다.

            배경 key 값은 화면이 정의하며 서버는 값을 검증하지 않는다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "변경 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "미해금 기능(ZZAL_FEATURE_LOCKED)")})
    @PostMapping("/{petId}/background")
    public ApiResponse<PetResponses.Detail> background(@LoginUser Long userId,
                                                       @PathVariable Long petId,
                                                       @Valid @RequestBody PetRequests.Background request) {
        Instant real = Instant.now();
        return ApiResponse.ok(detail(petService.changeBackground(userId, petId, request.background(), real), real));
    }

    @Operation(summary = "튜토리얼 완료", description = """
            튜토리얼 마지막 단계에서 호출한다. 이 호출로 게임 시계가 시작된다.

            튜토리얼은 시간이 아니라 순서로 진행한다. 1~8단계는 각 행동 API(돌보기·채팅 응답·
            성격 등록·미니게임·공유·취침)가 호출될 때 서버가 순서를 확인하고 다음 단계로 넘긴다.
            별도 호출이 필요한 단계는 사용자 입력이 없는 9단계뿐이다.

            시계가 시작되기 전에는 게이지 감소·질병·자동 취침이 발생하지 않는다.
            따라서 이탈 후 며칠이 지나도 튜토리얼 진행 상태는 그대로 유지된다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "시계 시작"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "미완료 단계 존재(ZZAL_TUTORIAL_NOT_FINISHED) · 이미 완료됨(ZZAL_TUTORIAL_ALREADY_DONE)")})
    @PostMapping("/{petId}/tutorial/done")
    public ApiResponse<PetResponses.Detail> tutorialDone(@LoginUser Long userId, @PathVariable Long petId) {
        Instant real = Instant.now();
        return ApiResponse.ok(detail(petService.tutorialDone(userId, petId, real), real));
    }

    @Operation(summary = "공유 링크 발급", description = """
            해금된 동작에 대해 공유 주소를 발급하고 공유 횟수를 증가시킨다.
            응답에는 링크와 변경된 상태가 함께 담긴다.

            같은 동작을 다시 공유하면 기존 링크를 그대로 반환한다. 호출마다 주소가 바뀌면
            이전에 배포한 링크와 달라져 확산 경로를 집계할 수 없기 때문이다.

            파일이 아니라 링크로 제공하는 이유는 주요 SNS 인앱 브라우저가 파일 다운로드를
            차단하기 때문이다. 링크는 해당 환경에서도 열린다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "발급 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "미해금 동작(ZZAL_MOTION_NOT_OPEN)")})
    @PostMapping("/{petId}/share")
    public ApiResponse<PetResponses.Shared> share(@LoginUser Long userId,
                                                  @PathVariable Long petId,
                                                  @Valid @RequestBody PetRequests.Share request) {
        Instant real = Instant.now();
        // ★ 순서가 중요하다 — 열린 동작인지 먼저 본다. 링크를 먼저 내면 안 열린 동작에도
        //   주소가 생겨 남는다(예외로 롤백되지 않는 경로가 있다).
        PetResponses.Detail pet = detail(petService.share(userId, petId, request.motionKey(), real), real);
        ShareResponses.Issued issued = shareService.issue(petId, request.motionKey(), real);
        return ApiResponse.ok(new PetResponses.Shared(issued.token(), issued.url(), pet));
    }

    // ── 동작 (설계 규칙) ───────────────────────────────────────────────

    @Operation(summary = "신규 동작 확인 처리", description = """
            새로 도착한 동작을 확인했음을 기록한다. 처리 후 learnedToday 목록에서 제외된다.

            서버가 확인 여부를 기록하지 않으면 화면에 진입할 때마다 해금 연출이 반복된다.
            수면 중에도 호출할 수 있다.

            아직 도착하지 않은 동작은 409(ZZAL_MOTION_NOT_OPEN)를 반환한다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "확인 처리"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
                    description = "존재하지 않거나 소유자가 다른 캐릭터(ZZAL_PET_NOT_FOUND)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "미도착 동작(ZZAL_MOTION_NOT_OPEN)")})
    @PostMapping("/{petId}/motions/{seq}/seen")
    public ApiResponse<PetResponses.Detail> seen(@LoginUser Long userId,
                                                 @PathVariable Long petId,
                                                 @PathVariable int seq) {
        Instant real = Instant.now();
        return ApiResponse.ok(detail(petService.markSeen(userId, petId, seq, real), real));
    }

    // ── 앨범 (설계 규칙) ─────────────────────────────────────────────────

    @Operation(summary = "앨범 조회", description = """
            동작 도감 18칸과 엽서·장면·첫 심화 동작 정보를 반환한다.
            잠긴 칸도 이름과 해금 조건을 포함해 내려간다.

            앨범은 처음부터 열려 있으며 해금 여부와 무관하게 조회할 수 있다.
            엽서·장면은 해당 기능이 구현되기 전까지 빈 목록으로 반환한다.""")
    @GetMapping("/{petId}/album")
    public ApiResponse<PetResponses.Album> album(@LoginUser Long userId, @PathVariable Long petId) {
        Instant real = Instant.now();
        ZzalPet pet = petService.refresh(userId, petId, real);
        PetResponses.Detail d = detail(pet, null, real);
        return ApiResponse.ok(new PetResponses.Album(
                d.motions() == null ? List.of() : d.motions(),
                // 여행 엽서 — 전달된 것만(여행 중인 엽서는 안 보인다)
                petService.postcards(petId).stream().map(PetResponses.Postcard::of).toList(),
                // 혼자 논 장면 보관 3개(설계 규칙). 최근 것부터
                petService.scenes(petId).stream().map(PetResponses.Scene::of).toList(),
                d.firstGift()));
    }
}
