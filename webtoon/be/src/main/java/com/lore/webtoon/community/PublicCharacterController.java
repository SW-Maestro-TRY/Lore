package com.lore.webtoon.community;
import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.credit.CreditGate;
import com.lore.common.response.ApiResponse;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping(WebtoonApi.V1+"/community/characters")
public class PublicCharacterController {
    private final PublicCharacters service;
    public PublicCharacterController(PublicCharacters service) { this.service=service; }
    public record Publication(boolean published,boolean agreed) {}
    @GetMapping public ApiResponse<?> browse(@RequestParam(defaultValue="0") int page) { return ApiResponse.ok(service.browse(page)); }
    @GetMapping("/{id}") public ApiResponse<?> one(@PathVariable String id) { return ApiResponse.ok(service.one(id)); }
    @PutMapping("/{id}/publication") public ApiResponse<?> publish(@PathVariable String id,@RequestBody Publication body) { return ApiResponse.ok(service.publish(id,CreditGate.currentUser(),body.published(),body.agreed())); }
}
