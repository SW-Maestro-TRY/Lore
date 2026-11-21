package com.lore.webtoon.lou;

import com.lore.webtoon.Admins;
import com.lore.webtoon.work.WorkLedger;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/webtoon/v1/runs/{runId}/lou")
public class LouController {
    private final LouService service;
    private final Admins admins;
    private final WorkLedger ledger;

    public LouController(LouService service, Admins admins, WorkLedger ledger) {
        this.service = service;
        this.admins = admins;
        this.ledger = ledger;
    }

    @PostMapping("/turns")
    @Operation(summary="루에게 짚은 장과 질문 보내기")
    public Object ask(@PathVariable String runId, @RequestBody Map<String,Object> body) {
        return service.submit(runId, EditorAccess.mustOwn(runId, admins, ledger), "ask", body);
    }

    @PostMapping("/checks")
    @Operation(summary="루에게 재생성 전후 비교 요청하기")
    public Object check(@PathVariable String runId, @RequestBody Map<String,Object> body) {
        return service.submit(runId, EditorAccess.mustOwn(runId, admins, ledger), "check", body);
    }

    @GetMapping("/turns/{id}")
    @Operation(summary="루 대화 또는 비교 결과 보기")
    public Object view(@PathVariable String runId, @PathVariable String id) {
        EditorAccess.mustOwn(runId, admins, ledger);
        return service.view(runId,id);
    }

    @GetMapping("/turns")
    @Operation(summary="최근 루 대화와 비교 50건 보기")
    public Object list(@PathVariable String runId) {
        EditorAccess.mustOwn(runId, admins, ledger);
        return service.list(runId);
    }

    @ExceptionHandler(LouLimit.class)
    public ResponseEntity<?> limit() {
        return ResponseEntity.status(429).body(Map.of("message","오늘 루 대화 100건을 모두 사용했어요. 내일 다시 보내 주세요."));
    }
}

class LouLimit extends RuntimeException {}
