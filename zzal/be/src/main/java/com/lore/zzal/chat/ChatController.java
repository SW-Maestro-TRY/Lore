package com.lore.zzal.chat;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import com.lore.zzal.chat.dto.ChatRequests;
import com.lore.zzal.chat.dto.ChatResponses;
import com.lore.zzal.motion.MotionCatalog;
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

/**
 * 채팅 API(api-v2.md 1.5) — 캐릭터가 먼저 부르고 사용자가 답한다.
 */
@Tag(name = "채팅", description = "하루 3회의 부름 + 아기 8분")
@RestController
@RequestMapping("/api/zzal/v2/me/pets/{petId}/chat")
public class ChatController {

    private final ChatService chatService;
    private final MotionCatalog catalog;

    public ChatController(ChatService chatService, MotionCatalog catalog) {
        this.chatService = chatService;
        this.catalog = catalog;
    }

    @Operation(summary = "오늘의 부름", description = """
            지금까지 도래한 부름들과 지금 답할 수 있는 슬롯(`openSlot`), 기억(최근 답 5개).
            자는 중에도 조회는 되지만 `openSlot` 은 null 이다.""")
    @GetMapping
    public ApiResponse<ChatResponses.Chat> calls(@LoginUser Long userId, @PathVariable Long petId) {
        ChatService.View v = chatService.calls(userId, petId, Instant.now());
        return ApiResponse.ok(new ChatResponses.Chat(v.openSlot(),
                v.calls().stream().map(ChatResponses.Call::from).toList(), v.memories()));
    }

    @Operation(summary = "부름에 답하기", description = """
            자유 입력 40자 1회. 대사 1줄 + 반응 동작 1개 + 친밀도 +40.
            응답 = `{pet: PetDetail, chatReply{line, reactionKey}}`(해석 22).""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "답함"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "안 열린·만료된 부름(ZZAL_CHAT_SLOT_CLOSED) · 자는 중(ZZAL_PET_SLEEPING)")})
    @PostMapping("/{slot}/answer")
    public ApiResponse<ChatResponses.Answered> answer(@LoginUser Long userId,
                                                      @PathVariable Long petId,
                                                      @PathVariable ChatSlot slot,
                                                      @Valid @RequestBody ChatRequests.Answer request) {
        Instant real = Instant.now();
        ChatService.Answered a = chatService.answer(userId, petId, slot, request.text(), real);
        PetResponses.Detail pet = PetResponses.Detail.from(a.action().pet(), null, a.action().pet().now(real), catalog,
                a.action().justUnlocked());
        return ApiResponse.ok(new ChatResponses.Answered(pet, new ChatResponses.Reply(a.replyLine(), a.reactionKey())));
    }
}
