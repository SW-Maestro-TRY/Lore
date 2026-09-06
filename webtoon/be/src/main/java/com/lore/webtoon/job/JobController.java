package com.lore.webtoon.job;

import com.lore.common.exception.BusinessException;
import com.lore.webtoon.CreditGate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 만들기 — <b>파이썬 서버를 안 거치는</b> 길.
 *
 * <h2>스위치로 켠다</h2>
 *
 * 기본은 꺼져 있다. 켜지 않으면 이 컨트롤러가 아예 안 뜨고, 같은 주소를
 * {@code WebtoonController} 가 지금처럼 파이썬 서버로 넘긴다. <b>한 번에
 * 갈아타지 않는다</b> — 갈아타는 동안 만들기가 통째로 멈추면 안 된다.
 *
 * <pre>
 *   lore.webtoon.python.direct=true      # 스프링이 직접 만든다
 * </pre>
 *
 * 켰을 때 이 길이 이기는 이유: 스프링은 <b>더 구체적인 매핑</b>을 먼저 고른다.
 * {@code WebtoonController} 는 {@code /api/webtoon/**} 라는 넓은 그물이고,
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

    static final String PREFIX = "/api/webtoon/nh";

    private final JobService jobs;

    public JobController(JobService jobs) {
        this.jobs = jobs;
    }

    @Operation(summary = "웹툰 만들기 시작")
    @PostMapping("/create")
    public Map<String, Object> create(@RequestBody JobService.CreateRequest form) {
        String id = jobs.create(form, CreditGate.currentUser(), form.uid());
        return Map.of("id", id, "queue_position", 0);
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

    @Operation(summary = "캐릭터 시트 확인")
    @PostMapping("/jobs/{id}/sheet")
    public Map<String, Object> sheet(@PathVariable String id) {
        jobs.approveSheet(id);
        return Map.of("ok", true);
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
