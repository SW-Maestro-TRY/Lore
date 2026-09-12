package com.lore.zzal.chat;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import com.lore.zzal.chat.dto.ChatRequests;
import com.lore.zzal.chat.dto.ChatResponses;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.pet.PetService;
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
@Tag(name = "채팅", description = "캐릭터가 먼저 말을 걸고 사용자가 응답한다. 하루 3회 + 튜토리얼 1회")
@RestController
@RequestMapping("/api/zzal/v1/me/pets/{petId}/chat")
public class ChatController {

    private final ChatService chatService;
    private final PetService petService;
    private final MotionCatalog catalog;

    public ChatController(ChatService chatService, PetService petService, MotionCatalog catalog) {
        this.chatService = chatService;
        this.petService = petService;
        this.catalog = catalog;
    }

    @Operation(summary = "오늘의 대화 조회", description = """
            현재 시점까지 발생한 대화 목록과 응답 가능한 슬롯(openSlot), 최근 응답 5건을 반환한다.

            대화는 기상 후 1시간, 기상 후 7시간, 19:00 에 발생한다. 응답하지 않은 채 다음 시각이
            지나면 해당 대화는 만료되며 별도 감점은 없다.

            수면 중에도 조회는 가능하지만 openSlot 은 null 이다.""")
    @GetMapping
    public ApiResponse<ChatResponses.Chat> calls(@LoginUser Long userId, @PathVariable Long petId) {
        ChatService.View v = chatService.calls(userId, petId, Instant.now());
        return ApiResponse.ok(new ChatResponses.Chat(v.openSlot(),
                v.calls().stream().map(ChatResponses.Call::from).toList(), v.memories()));
    }

    @Operation(summary = "대화 응답", description = """
            슬롯당 1회, 자유 입력 40자로 응답한다. 응답 시 친밀도 +40 을 부여하며,
            이는 단일 행동으로 얻는 가장 큰 값이다.

            응답은 변경된 캐릭터 상태(pet)와 대사·반응 동작(chatReply)으로 구성한다.
            입력한 내용은 기억으로 저장되어 이후 대화에서 다시 언급된다.

            누적 응답 횟수는 동작 해금 조건(1회·4회·12회)에 사용한다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "응답 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "미개방·만료된 슬롯(ZZAL_CHAT_SLOT_CLOSED) · 수면 중(ZZAL_PET_SLEEPING)")})
    @PostMapping("/{slot}/answer")
    public ApiResponse<ChatResponses.Answered> answer(@LoginUser Long userId,
                                                      @PathVariable Long petId,
                                                      @PathVariable ChatSlot slot,
                                                      @Valid @RequestBody ChatRequests.Answer request) {
        Instant real = Instant.now();
        ChatService.Answered a = chatService.answer(userId, petId, slot, request.text(), real);
        PetResponses.Detail pet = PetResponses.Detail.from(a.action().pet(), null, a.action().pet().now(real), catalog,
                petService.motionRows(petId), a.action().justUnlocked());
        return ApiResponse.ok(new ChatResponses.Answered(pet, new ChatResponses.Reply(a.replyLine(), a.reactionKey())));
    }
}
