package com.lore.webtoon.job;

import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.credit.GuestGate;
import com.lore.webtoon.usage.SpendGuard;
import com.lore.webtoon.WebtoonApi;
import com.lore.common.exception.BusinessException;
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
    private final RunArt art;
    private final SpendGuard guard;
    private final GuestGate guests;
    private final CreditGate credits;

    public JobController(JobService jobs, RunArt art, SpendGuard guard, GuestGate guests,
                         CreditGate credits) {
        this.jobs = jobs;
        this.art = art;
        this.guard = guard;
        this.guests = guests;
        this.credits = credits;
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
        String blocked = guard.whyBlocked();
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
        if (blocked == null) {
            blocked = credits.whyBlocked(me);
            if (blocked != null) {
                code = 402;                     // 기다려도 안 풀린다 — 충전해야 한다
            }
        }
        if (blocked != null) {
            return ResponseEntity.status(code).body(Map.of("error", blocked));
        }

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
        credits.charge(me, id);                 // 만들어진 뒤에 받는다
        return ResponseEntity.ok(Map.of("id", id, "queue_position", 0));
    }

    @Operation(summary = "진행 상황", description = """
            진행 화면이 0.8 초마다 부른다. 파이썬 서버가 내보내던 것과 **같은 모양**이다.""")
    @GetMapping("/jobs/{id}")
    public JobView job(@PathVariable String id) {
        return jobs.view(id);
    }

    @Operation(summary = "이야기 고르기")
    @PostMapping("/jobs/{id}/pick")
    public Map<String, Object> pick(@PathVariable String id, @RequestBody PickRequest body) {
        jobs.pick(id, body.n());
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
    @Operation(summary = "이야기 후보 다시 짓기",
            description = "고르는 차례일 때만 된다. note 를 적어 보내면 이번에만 반영한다.")
    @PostMapping("/jobs/{id}/pick-retry")
    public Map<String, Object> retryPick(@PathVariable String id,
                                         @RequestBody(required = false) NoteRequest body) {
        jobs.retryPick(id, body == null ? null : body.note());
        return Map.of("ok", true);
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

    public record PickRequest(int n) {
    }
}
