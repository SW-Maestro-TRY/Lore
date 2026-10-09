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
            session·turns 는 지금의 판(열린 판, 없으면 오늘 마지막 판)과 그 판의 턴들이다.

            하루 대화 창은 KST 10~14시, 14~19시, 19~23시다. 창이 지나면 만료되며 별도 감점은 없다.
            지난 창에는 새 판을 만들지 않는다. 튜토리얼 BABY는 순서로 열리며 만료가 없다.
            BABY 첫 생성 실패는 5분 재시도 대기 후 누적 3회 실패 시 중립 문장으로 열린다.
            첫 답 뒤 10분 동안 말이 없으면 그 판은 닫힌다(ABANDONED).

            수면 중에도 조회는 가능하지만 openSlot 은 null 이다.""")
    @GetMapping
    public ApiResponse<ChatResponses.Chat> calls(@LoginUser Long userId, @PathVariable Long petId) {
        ChatService.View v = chatService.calls(userId, petId, Instant.now());
        return ApiResponse.ok(new ChatResponses.Chat(v.openSlot(),
                v.calls().stream().map(ChatResponses.Call::from).toList(), v.memories(),
                ChatResponses.Session.from(v.session()), v.turns().stream().map(ChatResponses.Turn::from).toList()));
    }

    @Operation(summary = "대화 응답", description = """
            열린 대화(판)에 자유 입력 40자로 한 마디 한다. 한 판은 최대 max-rounds 왕복이고, 마지막 펫 턴은
            닫기 턴이다(session.closed=true). 친밀도 +40·응답 카운터는 판당 1회(첫 답)만 센다.

            응답은 변경된 캐릭터 상태(pet)·다음 펫 대사와 반응 동작(chatReply)·판 상태(session)·
            이번에 생긴 두 턴(turns = 사용자 턴, 펫 턴)으로 구성한다.

            누적 응답 횟수(판 수)는 답하기 동작 해금(4회)에 사용한다.""")
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
                petService.motionRows(petId), a.action().justUnlocked(), false,
                petService.scenes(petId), petService.pieces(petId));
        return ApiResponse.ok(new ChatResponses.Answered(pet, new ChatResponses.Reply(a.replyLine(), a.reactionKey()),
                ChatResponses.Session.from(a.session()), a.turns().stream().map(ChatResponses.Turn::from).toList()));
    }
}
