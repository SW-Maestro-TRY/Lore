package com.lore.webtoon.community;

import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.credit.CreditGate;
import com.lore.common.response.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping(WebtoonApi.V1+"/community")
public class CommunityController {
    private final CommunityService service;
    public CommunityController(CommunityService service) { this.service=service; }
    private Long me() { return CreditGate.currentUser(); }
    public record Text(String body) {}
    public record Report(String kind,String target,String reason) {}
    public record Resolution(boolean hide) {}
    @GetMapping("/runs/{run}/subscription") public ApiResponse<?> state(@PathVariable String run) { return ApiResponse.ok(service.state(run,me())); }
    @PutMapping("/runs/{run}/subscription") public ApiResponse<?> subscribe(@PathVariable String run) { return ApiResponse.ok(service.subscribe(run,me(),true)); }
    @DeleteMapping("/runs/{run}/subscription") public ApiResponse<?> unsubscribe(@PathVariable String run) { return ApiResponse.ok(service.subscribe(run,me(),false)); }
    @GetMapping("/subscriptions") public ApiResponse<?> subscriptions() { return ApiResponse.ok(service.subscriptions(me())); }
    @DeleteMapping("/subscriptions/{root}") public ApiResponse<?> unsubscribeRoot(@PathVariable String root) { service.unsubscribeRoot(root,me()); return ApiResponse.ok(Map.of("ok",true)); }
    @GetMapping("/runs/{run}/comments") public ApiResponse<?> comments(@PathVariable String run,@RequestParam(defaultValue="0") long before) { return ApiResponse.ok(service.comments(run,me(),before)); }
    @GetMapping("/runs/{run}/comments/count") public ApiResponse<?> commentCount(@PathVariable String run) { return ApiResponse.ok(Map.of("count",service.commentCount(run))); }
    @PostMapping("/runs/{run}/comments") public ApiResponse<?> comment(@PathVariable String run,@RequestBody Text body) { return ApiResponse.ok(service.addComment(run,me(),body.body())); }
    @PutMapping("/comments/{id}") public ApiResponse<?> edit(@PathVariable long id,@RequestBody Text body) { service.editComment(id,me(),body.body()); return ApiResponse.ok(Map.of("ok",true)); }
    @DeleteMapping("/comments/{id}") public ApiResponse<?> delete(@PathVariable long id) { service.deleteComment(id,me()); return ApiResponse.ok(Map.of("ok",true)); }
    @GetMapping("/notices") public ApiResponse<?> notices() { return ApiResponse.ok(service.notices(me())); }
    @PostMapping("/notices/{id}/read") public ApiResponse<?> read(@PathVariable long id) { service.readNotice(me(),id); return ApiResponse.ok(Map.of("ok",true)); }
    @PostMapping("/reports") public ApiResponse<?> report(@RequestBody Report body) { service.report(me(),body.kind(),body.target(),body.reason()); return ApiResponse.ok(Map.of("ok",true)); }
    @GetMapping("/reports") public ApiResponse<?> reports() { return ApiResponse.ok(service.reports(me())); }
    @PostMapping("/reports/{id}/resolve") public ApiResponse<?> resolve(@PathVariable long id,@RequestBody Resolution body) { service.resolve(me(),id,body.hide()); return ApiResponse.ok(Map.of("ok",true)); }
    @GetMapping("/runs/{run}/attribution") public ApiResponse<?> attribution(@PathVariable String run) { return ApiResponse.ok(service.attribution(run)); }
}
