package com.lore.webtoon.job;

import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.credit.GuestGate;
import com.lore.webtoon.usage.SpendGuard;
import com.lore.webtoon.WebtoonApi;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.s3.S3Service;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 만들기 — <b>파이썬 서버를 안 거치는</b> 길.
 *
 * <h2>스위치로 켠다</h2>
 *
 * 기본은 꺼져 있다. 켜지 않으면 이 컨트롤러가 아예 안 뜨고, 같은 주소를
 * 옛 프록시 가 지금처럼 파이썬 서버로 넘긴다. <b>한 번에
 * 갈아타지 않는다</b> — 갈아타는 동안 만들기가 통째로 멈추면 안 된다.
 *
 * <pre>
 *   lore.webtoon.python.direct=true      # 스프링이 직접 만든다
 * </pre>
 *
 * 켰을 때 이 길이 이기는 이유: 스프링은 <b>더 구체적인 매핑</b>을 먼저 고른다.
 * 옛 프록시 는 {@code /api/webtoon/v1/**} 라는 넓은 그물이고,
 * 여기는 주소를 하나씩 적었다.
 *
 * <h2>응답 모양은 그대로다</h2>
 *
 * 화면은 프로토타입에서 옮겨 온 것이라 파이썬이 내보내던 이름을 그대로 읽는다.
 * 그래서 봉투({@code ApiResponse})를 안 씌우고 <b>파이썬이 주던 모양 그대로</b>
 * 내보낸다 — 씌우면 진행 화면이 "알 수 없는 오류" 밖에 못 띄운다.
 */
@Tag(name = "Webtoon", description = "웹툰 스튜디오")
@RestController
@RequestMapping(JobController.PREFIX)
@ConditionalOnProperty(name = "lore.webtoon.python.direct", havingValue = "true")
public class JobController {

    static final String PREFIX = WebtoonApi.V1 + "/nh";

    private final JobService jobs;
    private final JobQueue queue;
    private final RunArt art;
    private final SpendGuard guard;
    private final GuestGate guests;
    private final CreditGate credits;
    private final S3Service uploads;
    private final com.lore.webtoon.character.CharacterOwner owner;

    public JobController(JobService jobs, JobQueue queue, RunArt art, SpendGuard guard,
                         GuestGate guests, CreditGate credits, S3Service uploads,
                         com.lore.webtoon.character.CharacterOwner owner) {
        this.owner = owner;
        this.jobs = jobs;
        this.queue = queue;
        this.art = art;
        this.guard = guard;
        this.guests = guests;
        this.credits = credits;
        this.uploads = uploads;
    }

    /**
     * 만들기 전에 화면이 묻는 것 — <b>지금 이 사람은 무엇으로 만드는가.</b>
     *
     * 로그인 안 한 사람에게 화면이 「−12크레딧」이라고 적고 있었다. 그 사람에게는
     * 크레딧이 아예 없다(게스트는 하루 무료 몇 편으로 센다) — 없는 값을 낸다고
     * 적어 두고, 정작 몇 편이 남았는지는 어디에도 없었다. 다 쓰고 나서야
     * "오늘 2편 다 쓰셨어요" 를 처음 본다.
     *
     * 봉투를 안 씌운다 — 이 화면이 읽는 다른 것들과 같은 모양으로 둔다.
     */
    @Operation(summary = "지금 만들면 무엇이 드나",
            description = "게스트면 남은 무료 편수, 로그인했으면 크레딧 값과 잔액.")
    @GetMapping("/allowance")
    public Map<String, Object> allowance(HttpServletRequest request) {
        Long me = CreditGate.currentUser();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("logged_in", me != null);
        out.put("credit_cost", credits.cost());
        // 편집실 단추가 값을 적으려고 쓴다 — 화면에 박아 두면 서버 설정과 어긋난다.
        out.put("regen_cost", credits.regenCost());
        /* 화질 셋과 각각의 값. **화면이 여기서 받아 간다** — 같은 표를 화면에도
           적어 두면, 한쪽만 고치는 순간 적힌 값과 실제로 빠지는 크레딧이
           어긋난다. 사람에게 그건 거짓말이다. */
        out.put("qualities", WebtoonQuality.choices());
        out.put("quality_default", WebtoonQuality.DEFAULT_QUALITY);
        if (me == null) {
            Integer left = guests.freeLeft(request);
            out.put("free_left", left);
            out.put("free_per_day", guests.freePerDay());
        } else {
            out.put("balance", credits.balanceOf(me));
        }
        // 오늘 전체 몫이 찼으면 로그인해도 못 만든다 — 그 말을 먼저 해야 한다.
        out.put("blocked", guard.whyBlocked());
        return out;
    }

    /**
     * 게스트(비로그인)용 사진 업로드 주소 발급.
     *
     * 팀 공용 presign({@code /api/v1/uploads/presign})은 로그인이 필요하다 —
     * 티켓을 계정에 묶어야 남의 키를 적어 넣는 것을 막을 수 있어서다. 게스트는
     * 계정이 없어 그 길을 못 쓰고, 그래서 사진을 data URL(base64)로 요청
     * 본문에 그대로 실어 보내고 있었다. 사진이 조금만 커도 본문이 1MB 를
     * 넘는데, CloudFront 앞단 WAF(SizeRestrictions_BODY)가 그 크기를 보고
     * 403 으로 막는다 — 로그인 여부와 무관하게 사진만 올리면 늘 이랬다
     * (2026-09-17 dev 에서 실측).
     *
     * 계정 대신 {@link GuestGate#keyOf}(IP 해시)로 티켓을 묶는다 — 로그인
     * 사람과 같은 "발급받은 사람만 그 키를 쓸 수 있다" 보호를 그대로 적용한다.
     */
    @Operation(summary = "게스트 사진 업로드 주소 발급",
            description = "로그인 없이 S3 에 직접 올릴 임시 주소를 받는다. 10분간 유효.")
    @PostMapping("/photo-presign")
    public S3Service.PresignedUpload photoPresign(HttpServletRequest request,
                                                   @RequestBody PhotoPresignRequest form) {
        if (CreditGate.currentUser() != null) {
            // 로그인한 사람은 팀 공용 presign(/api/v1/uploads/presign)을 쓴다 —
            // 거기 티켓이 계정에 묶여 더 오래(계정이 있는 한) 추적·회수된다.
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "로그인한 사람은 /api/v1/uploads/presign 을 쓰세요");
        }
        String guestKey = guests.keyOf(request);
        if (guestKey == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "게스트 열쇠를 만들 수 없습니다");
        }
        return uploads.createUploadUrlForGuest(guestKey, "webtoon", form.contentType());
    }

    /** 올릴 파일의 MIME 타입(image/png 등). */
    public record PhotoPresignRequest(String contentType) {}

    @Operation(summary = "웹툰 만들기 시작", description = """
            **돈이 나가는 유일한 자리다.** 넘기기 전에 세 번 멈춰 세운다 —
            오늘 전체 몫 · 로그인 안 한 사람의 하루 몫 · 계정 크레딧.""")
    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> create(HttpServletRequest request,
                                                      @RequestBody JobService.CreateRequest form) {
        Long me = CreditGate.currentUser();

        /* **여기도 문지기가 서야 한다.**
         *
         * 프록시 길(옛 프록시)에는 이 셋이 이미 서 있는데, 이 길은
         * 그걸 안 거친다 — 처음 만들 때 그대로 뒀더니 크레딧 0 으로도 그냥
         * 만들어졌다. 스위치를 켜는 순간 아무나 무한히 만들 수 있게 된다.
         *
         * 순서는 프록시 길과 같다: 전체 몫이 먼저다. 오늘 다 찼으면 로그인해도
         * 못 만드는데 "로그인하면 됩니다" 라고 말하면 거짓말이 된다. */
        /* **아직 안 적힌 몫까지 세어서 묻는다.** 나란히 둘을 돌리면 상한까지
           한 편 남았을 때 둘이 같이 물어 둘 다 통과할 수 있다 — 둘 다 아직
           아무것도 안 썼기 때문이다. 줄에 선 것들이 쓸 돈을 미리 잡아 준다. */
        String blocked = guard.whyBlocked(queue.reserved());
        int code = 429;
        boolean counted = false;
        String guestKey = null;
        if (blocked == null && me == null) {
            blocked = guests.useOrBlock(request);
            counted = blocked == null;
            // 나중에(그리다가) 실패해도 되돌릴 수 있게 누구였는지 남긴다.
            if (counted) {
                guestKey = guests.keyOf(request);
            }
        }
        /* **고른 화질만큼 받는다.** 너울(high)은 원가가 파도의 2.4배라 같은
           값으로 팔면 한 편마다 손해다 — 실제로 그렇게 한 달 가까이 돌았다.
           값은 WebtoonQuality 한 곳만 안다. */
        int need = WebtoonQuality.creditsOf(form.quality());
        if (blocked == null) {
            blocked = credits.whyBlocked(me, need);
            if (blocked != null) {
                code = 402;                     // 기다려도 안 풀린다 — 충전해야 한다
            }
        }
        if (blocked != null) {
            return ResponseEntity.status(code).body(Map.of("error", blocked));
        }

        /* **줄 선 자리를 만들기 직전에 센다.** 여기서 센 값이 화면에 「앞에
           3명」으로 적히고, 그대로 작업에 남는다 — 나중에 실제로 기다린
           시간과 맞춰 보면 우리 예상이 맞았는지 알 수 있다. */
        int ahead = queue.ahead();

        String id;
        try {
            id = jobs.create(form, me, form.uid(), guestKey);
        } catch (RuntimeException e) {
            // 시작도 못 했으면 방금 센 한 편을 도로 물린다 — 만든 적 없는
            // 사람에게 "오늘 몫을 다 쓰셨어요" 가 뜨면 안 된다.
            if (counted) {
                guests.refund(request);
            }
            throw e;
        }
        // 만들어진 뒤에 받는다. 같은 작업으로 두 번 불려도 한 번만 빠진다.
        credits.charge(me, need, id, WebtoonQuality.labelOf(form.quality()));
        queue.remember(id, ahead);
        return ResponseEntity.ok(Map.of("id", id, "queue_position", ahead));
    }

    @Operation(summary = "내가 만들던 것", description = """
            아직 안 끝난 작업들(줄 서 있거나 · 그리는 중 · 사람 차례). 첫 화면의
            「만들던 웹툰」 알약이 이것을 본다. 로그인 안 했으면 uid 로 가린다.""")
    @GetMapping("/jobs/mine")
    public Map<String, Object> mine(@RequestParam(required = false) String uid) {
        Long me = CreditGate.currentUser();
        var uids = owner.uidsOf(me, uid);
        return Map.of("jobs", jobs.activeOf(me, uids), "cards", jobs.activeCardsOf(me, uids));
    }

    @Operation(summary = "진행 상황", description = """
            진행 화면이 0.8 초마다 부른다. 파이썬 서버가 내보내던 것과 **같은 모양**이다.""")
    @GetMapping("/jobs/{id}")
    public JobView job(@PathVariable String id,
                       @RequestParam(defaultValue = "false") boolean watching) {
        // watching — 진행 화면이 앞에 떠 있을 때만 붙인다. 그동안은 푸시를 안 보낸다(#599).
        return jobs.view(id, watching);
    }

    /**
     * 다 되면 이 주소로 알려 달라 — <b>게스트가 이메일을 적어 넣는 자리.</b>
     *
     * 한 편에 5~15분이 걸린다. 창을 닫으면 다 됐는지 알 길이 없고, 게스트는
     * 자기 작품을 브라우저 uid 로만 찾으므로 <b>다른 기기로 들어오면 만든
     * 것을 못 찾는다.</b> 메일에 담는 결과 링크가 그 사람이 자기 작품으로
     * 돌아오는 유일한 길이다.
     *
     * 로그인한 사람은 이걸 안 불러도 계정 주소로 간다.
     *
     * <b>실패해도 만들기는 안 멈춘다</b> — 이건 곁가지다. 주소가 틀렸으면
     * 그 자리에서 말해 준다(400): 담아 두고 보낸 척하면 화면에는
     * 「보낼게요」가 떠 있는데 영영 아무것도 안 온다.
     */
    @Operation(summary = "완성 알림 받을 이메일",
            description = "빈 값을 보내면 안 받겠다는 뜻이라 적어 둔 주소를 지운다.")
    @PostMapping("/jobs/{id}/notify")
    public Map<String, Object> notify(@PathVariable String id,
                                      @RequestBody(required = false) NotifyRequest body) {
        String to = jobs.notifyTo(id, body == null ? null : body.email());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        // 화면이 그대로 적는다. 자기 주소를 자기에게 보여 주는 것이라 안 가린다 —
        // 가려 놓으면 오타를 냈는지 확인할 길이 없다.
        out.put("email", to);
        return out;
    }

    /** 받을 주소. 본문 없이 부르면 「안 받겠다」로 읽는다. */
    public record NotifyRequest(String email) {
    }

    @Operation(summary = "상대 인물 고르기",
            description = "현대 로맨스에서 이야기 전에 상대 인물을 고른다(#534). 고르면 그 인물로 이야기 후보 넷을 짓는다.")
    @PostMapping("/jobs/{id}/cast")
    public Map<String, Object> pickCast(@PathVariable String id, @RequestBody CastRequest req) {
        jobs.pickCast(id, req.n());
        return Map.of("ok", true);
    }

    @Operation(summary = "이야기 고르기",
            description = "body 를 같이 보내면 그 방향의 본문을 사람이 고친 내용으로 바꿔서 " +
                    "다음 단계(장면 나누기)부터 그 내용을 쓴다. 안 보내거나 비우면 원래 본문 그대로 간다.")
    @PostMapping("/jobs/{id}/pick")
    public Map<String, Object> pick(@PathVariable String id, @RequestBody PickRequest req) {
        jobs.pick(id, req.n(), req.body(), req.title());
        return Map.of("ok", true);
    }

    /**
     * 넷 다 마음에 안 들 때 — 후보를 다시 짓는다.
     *
     * <b>없으면 그냥 새는 자리였다.</b> 화면은 처음부터 이 주소를 불렀는데
     * (nhApi.ts 의 {@code retryDirections}) 여기에 없어서, 아래 넓은 그물
     * (옛 프록시)로 떨어져 파이썬 서버까지 갔다. 파이썬은
     * 스프링이 만든 작업을 모르니 「그런 작업이 없습니다」를 냈다 — 시트
     * 주소가 어긋나 있던 것과 같은 종류의 구멍이다.
     */
    @Operation(summary = "장면 초안 저장",
            description = "장면 확인 자리(#548)에서 고친 장면 글을 적는다. 멈춤은 그대로. body 를 보내면 본문도 바꾼다.")
    @PostMapping("/jobs/{id}/scenes")
    public Map<String, Object> saveScenes(@PathVariable String id, @RequestBody ScenesRequest body) {
        jobs.saveScenes(id, body.scenes(), body.body(), body.title());
        return Map.of("ok", true);
    }

    @Operation(summary = "인물 카드 고치기",
            description = "own 길(#548) — who 가 hero 면 주인공, 숫자면 cast 의 그 번째(0부터). 보낸 칸만 덮는다.")
    @PostMapping("/jobs/{id}/person")
    public Map<String, Object> savePerson(@PathVariable String id, @RequestBody PersonRequest body) {
        jobs.savePerson(id, body.who(), body.fields());
        return Map.of("ok", true);
    }

    @Operation(summary = "이대로 웹툰 만들기", description = "장면 확인을 끝내고 그림으로 간다(#548).")
    @PostMapping("/jobs/{id}/scenes-continue")
    public Map<String, Object> continueScenes(@PathVariable String id) {
        jobs.continueScenes(id);
        return Map.of("ok", true);
    }

    @Operation(summary = "장면 다시 나누기",
            description = "본문·인물·시트는 두고 장면만 다시 나눈다(#548). note 를 보내면 이번에만 반영한다. 고친 글은 사라진다.")
    @PostMapping("/jobs/{id}/scenes-retry")
    public Map<String, Object> retryScenes(@PathVariable String id,
                                           @RequestBody(required = false) NoteRequest body) {
        jobs.retryScenes(id, body == null ? null : body.note());
        return Map.of("ok", true);
    }

    @Operation(summary = "장면 하나만 다시 짓기",
            description = "장면 확인 자리(#548)에서 n번 장면만 다시 짓는다. 로그인 필수. reasons 는 이유 코드 "
                    + "(awkward·character·stranger·offstory·pacing), note 는 메모. 돌아가는 동안 그 장면은 busy 다.")
    @PostMapping("/jobs/{id}/scenes/{n}/retry")
    public Map<String, Object> retryScene(@PathVariable String id, @PathVariable int n,
                                          @RequestBody(required = false) RetrySceneRequest body) {
        Long me = CreditGate.currentUser();
        /* 장면마다 첫 번째는 무료, 같은 장면을 또 뽑으면 1크레딧(#548). 먼저 받고, 못 지으면 돌려준다. */
        int cost = me == null ? 0 : jobs.resceneCost(id, n);
        String ref = id + ":rescene:" + n + ":" + System.currentTimeMillis();
        Runnable refund = cost > 0 ? () -> credits.refund(me, ref) : () -> { };
        if (cost > 0) {
            credits.requireEnough(me, cost);
            credits.charge(me, cost, ref, "장면 다시 뽑기 · " + n + "번");
        }
        try {
            jobs.retryScene(id, n, body == null ? null : body.reasons(), body == null ? null : body.note(), me, refund);
        } catch (RuntimeException e) {
            refund.run();
            throw e;
        }
        return Map.of("ok", true, "cost", cost);
    }

    @Operation(summary = "장면 이전 판으로 되돌리기",
            description = "다시 뽑기 전의 판 v(1부터, 오래된 것부터)로 되돌린다(#548). 지금 판은 판 목록 끝에 남는다.")
    @PostMapping("/jobs/{id}/scenes/{n}/restore")
    public Map<String, Object> restoreScene(@PathVariable String id, @PathVariable int n,
                                            @RequestBody RestoreSceneRequest body) {
        jobs.restoreScene(id, n, body == null ? 0 : body.v());
        return Map.of("ok", true);
    }

    public record RestoreSceneRequest(int v) {
    }

    public record RetrySceneRequest(List<String> reasons, String note) {
    }

    @Operation(summary = "옛 시트 판으로 되돌리기",
            description = "다시 그리기 전의 시트 판(1~sheet_versions)을 지금 시트로 올린다(#548). 지금 것도 보관한 뒤 바꾼다.")
    @PostMapping("/jobs/{id}/sheet-restore")
    public Map<String, Object> restoreSheet(@PathVariable String id, @RequestBody SheetRestoreRequest body) {
        jobs.restoreSheet(id, body.v());
        return Map.of("ok", true);
    }

    public record SheetRestoreRequest(int v) {
    }

    @Operation(summary = "보관한 옛 시트 판 그림")
    @GetMapping(value = "/jobs/{id}/sheet-v{v}.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> sheetVersionImage(@PathVariable String id, @PathVariable int v) throws IOException {
        String runId = jobs.runOf(id);
        Path src = runId == null ? null : art.sheetVersion(runId, v);
        return src == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(Files.readAllBytes(src));
    }

    @Operation(summary = "이야기 후보 다시 짓기",
            description = "고르는 차례일 때만 된다. note 를 적어 보내면 이번에만 반영한다.")
    @PostMapping("/jobs/{id}/pick-retry")
    public Map<String, Object> retryPick(@PathVariable String id,
                                         @RequestBody(required = false) NoteRequest body) {
        Long me = CreditGate.currentUser();
        /* own 길의 1화 다시 만들기는 첫 번째 무료, 그다음부터 1크레딧(#548). 먼저 받고, 못 지으면 돌려준다. */
        int cost = me == null ? 0 : jobs.restoryCost(id);
        String ref = id + ":restory:" + System.currentTimeMillis();
        Runnable refund = cost > 0 ? () -> credits.refund(me, ref) : () -> { };
        if (cost > 0) {
            credits.requireEnough(me, cost);
            credits.charge(me, cost, ref, "1화 다시 만들기");
        }
        try {
            jobs.retryPick(id, body == null ? null : body.note(), refund);
        } catch (RuntimeException e) {
            refund.run();
            throw e;
        }
        return Map.of("ok", true, "cost", cost);
    }

    /**
     * 그만둔다.
     *
     * <b>돈이 나가는 것을 사람이 멈출 수 있는 유일한 자리다.</b> 이 길에는
     * 그동안 이게 없어서, 화면의 「그만두기」가 파이썬 서버로 새고 아무 일도
     * 일어나지 않았다 — 사람은 눌렀는데 그림은 계속 그려지고 값은 계속
     * 나갔다.
     *
     * 이미 끝난 작업에도 200 을 준다. 화면은 0.8초마다 묻기 때문에, 다 만든
     * 순간에 누른 것을 오류로 돌려주면 사람은 자기가 뭘 잘못한 줄 안다.
     */
    @Operation(summary = "만들기 그만두기",
            description = "도는 것을 멈추고, 낸 것(크레딧·무료 횟수)을 돌려준다.")
    @PostMapping("/jobs/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        jobs.cancel(id);
        return Map.of("ok", true);
    }

    /** 사람이 적어 보낸 한 마디. 본문 없이 부를 수도 있다. */
    public record NoteRequest(String note) {
    }

    /**
     * 캐릭터 시트를 보고 정한다 — 이대로 가거나(approve), 다시 그리거나(retry).
     *
     * <b>주소 이름이 화면과 어긋나 있었다.</b> 화면은 처음부터
     * {@code /sheet-decision} 을 불렀는데 여기에는 {@code /sheet} 만 있어서,
     * 그 요청이 아래 프록시로 새어 파이썬 서버까지 갔다. 파이썬은 스프링이
     * 만든 작업을 모르므로 「그런 작업이 없습니다」를 냈다 — 시트에서 더
     * 나아갈 수 없었다. 옛 이름도 남겨 둔다(둘 다 받는다).
     */
    @Operation(summary = "캐릭터 시트 확인",
            description = "decision=approve 면 그대로 진행, retry 면 시트를 다시 그린다.")
    @PostMapping({"/jobs/{id}/sheet-decision", "/jobs/{id}/sheet"})
    public Map<String, Object> sheet(@PathVariable String id,
                                     @RequestBody(required = false) SheetDecision body) {
        String decision = body == null ? null : body.decision();
        if ("retry".equalsIgnoreCase(decision)) {
            jobs.retrySheet(id, body.note());
        } else {
            jobs.approveSheet(id);
        }
        return Map.of("ok", true);
    }

    @Operation(summary = "안전 기준에 걸린 캐릭터 시트 고치기",
            description = "시트가 이미지 안전 기준에 걸려 멈춘 작업에서, 사진(photo_keys · photos_data)이나 외모 설명"
                    + "(character)을 바꿔 시트만 다시 그린다. 이야기·장면은 그대로다. 작업당 3번까지(#626).")
    @PostMapping("/jobs/{id}/sheet-fix")
    public Map<String, Object> sheetFix(@PathVariable String id, HttpServletRequest request,
                                        @RequestBody(required = false) JobService.SheetFixRequest body) {
        Long me = CreditGate.currentUser();
        jobs.fixSheet(id, body, me, me == null ? guests.keyOf(request) : null);
        return Map.of("ok", true);
    }

    /** 본문이 없으면(옛 이름으로 부르면) 그대로 진행으로 본다. */
    public record SheetDecision(String decision, String note) {
    }

    /**
     * 만드는 동안 보는 그림 — 캐릭터 시트와 방금 그린 장.
     *
     * 진행 화면이 {@code <img src>} 에 그대로 넣는 주소라, 봉투도 JSON 도
     * 없이 그림 자체를 준다. 아직 안 그린 것은 404 다 — 화면은 그 자리를
     * 비워 두고 다음에 다시 묻는다.
     */
    @Operation(summary = "조연 시트 뽑기 (크레딧 1)",
            description = "인물 단계가 세운 다른 인물을 글 생김새만으로 시트로 그린다(#548). 장면 확인·이야기 고르기·시트 확인 자리에서만. "
                    + "그린 뒤로는 장 그림이 참조로 받아 그 인물이 장마다 같은 사람으로 나온다. 못 그리면 크레딧을 돌려준다.")
    @PostMapping("/jobs/{id}/cast-sheet")
    public Map<String, Object> castSheet(@PathVariable String id, @RequestBody CastSheetRequest body) {
        Long me = CreditGate.currentUser();
        String name = body == null || body.name() == null ? "" : body.name().trim();
        String ref = id + ":cast-sheet:" + name;
        credits.requireEnough(me, 1);
        jobs.castSheet(id, name, me, () -> credits.refund(me, ref));
        credits.charge(me, 1, ref, "조연 시트 · " + name);
        return Map.of("ok", true);
    }

    @Operation(summary = "조연 시트 그림")
    @GetMapping(value = "/jobs/{id}/cast-sheet/{name}.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> castSheetImage(@PathVariable String id, @PathVariable String name) throws IOException {
        String runId = jobs.runOf(id);
        Path src = runId == null ? null : art.castSheet(runId, name);
        return src == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(Files.readAllBytes(src));
    }

    @Operation(summary = "만드는 중인 캐릭터 시트")
    @GetMapping(value = "/jobs/{id}/sheet.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> sheetImage(@PathVariable String id) throws IOException {
        String runId = jobs.runOf(id);
        Path src = runId == null ? null : art.sheet(runId);
        return src == null
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(Files.readAllBytes(src));
    }

    @Operation(summary = "만드는 중인 한 장")
    @GetMapping("/jobs/{id}/page/{no}.png")
    public ResponseEntity<byte[]> pageImage(@PathVariable String id, @PathVariable int no,
                                            @RequestParam(defaultValue = "1080") int w)
            throws IOException {
        String runId = jobs.runOf(id);
        Path src = runId == null ? null : art.page(runId, no);
        if (src == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] body = art.scaled(src, w);
        // 줄인 것은 JPEG 이고 원본은 PNG 다 — 브라우저가 안 헷갈리게 밝힌다.
        MediaType type = body.length > 1 && body[0] == (byte) 0x89
                ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).body(body);
    }

    /**
     * 실패도 <b>파이썬이 주던 모양 그대로</b> 내보낸다.
     *
     * 이 저장소의 기본은 봉투({@code {success, data, error}})지만, 진행 화면은
     * 프로토타입에서 옮겨 온 것이라 {@code {"error": "사람이 읽을 한 줄"}} 만
     * 읽는다. 봉투를 씌우면 그 안의 error 가 글자가 아니라 덩어리라서, 화면에는
     * 사유 대신 <b>"[object Object]"</b> 가 뜬다.
     *
     * 옮겨 가는 동안 두 길이 같은 화면을 먹여야 하므로 여기서만 벗긴다.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, String>> asHarnessSpoke(BusinessException e) {
        return ResponseEntity.status(e.getErrorCode().getStatus())
                .body(Map.of("error", e.getMessage()));
    }

    /** 이야기 고르기. body·title 은 own 길의 이야기 확인(#548)에서 고친 본문·제목(선택). */
    public record PickRequest(int n, String body, String title) {
    }

    /** 장면 초안 저장(#548). scenes 의 각 줄은 {n, text}. body·title 은 own 길의 본문·제목(선택). */
    public record ScenesRequest(List<Map<String, Object>> scenes, String body, String title) {
    }

    public record CastRequest(int n) {
    }

    public record CastSheetRequest(String name) {
    }

    public record PersonRequest(String who, Map<String, Object> fields) {
    }
}
