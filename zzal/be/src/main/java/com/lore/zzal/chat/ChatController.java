package com.lore.zzal.chat;

import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import com.lore.zzal.chat.dto.ChatRequests;
import com.lore.zzal.chat.delivery.ChatDeliveryService;
import com.lore.zzal.chat.dto.ChatResponses;
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
@Tag(name = "채팅", description = "한 대화가 계속 이어진다. 원문 이력과 전송 중복 보호")
@RestController
@RequestMapping("/api/zzal/v1/me/pets/{petId}/chat")
public class ChatController {

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.lore.zzal.chat.stage.StageSpeechService stageSpeeches;
    private final ChatService chatService;
    private final ChatDeliveryService delivery;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lore.zzal.chat.presence.ChatPresenceService presence;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lore.zzal.chat.memory.facts.FactMemoryService factMemory;
    @org.springframework.beans.factory.annotation.Autowired
    private com.lore.zzal.chat.memory.facts.MemoryCaptureRetryService memoryRetry;

    public ChatController(ChatService chatService, ChatDeliveryService delivery) {
        this.chatService = chatService;
        this.delivery = delivery;
    }

    @Operation(summary = "지속 대화 조회", description = """
            펫당 한 대화가 계속 이어진다. slot=CONTINUOUS, maxRounds=null이다.
            시간창·왕복횟수·10분 무응답으로 닫지 않는다. 최근40턴은 화면 첫 페이지이며,
            전체 원문은 /history 커서 조회로 읽는다. 수면 중에는 답할 수 없다.
            튜토리얼 첫말 실패 대기·중립 복구는 기존대로다.""")
    @GetMapping
    public ApiResponse<ChatResponses.Chat> calls(@LoginUser Long userId, @PathVariable Long petId) {
        ChatService.View v = chatService.continuousCalls(userId, petId, Instant.now());
        return ApiResponse.ok(new ChatResponses.Chat(v.openSlot(),
                v.calls().stream().map(ChatResponses.Call::from).toList(), v.memories(),
                ChatResponses.Session.from(v.session()), v.turns().stream().map(ChatResponses.Turn::from).toList()).withTutorialAnswers(delivery.tutorialAnswers(userId,petId)).withStageSpeeches(stageSpeeches==null?java.util.List.of():stageSpeeches.read(userId,petId)));
    }

    @GetMapping("/memory-facts")
    public ApiResponse<java.util.List<com.lore.zzal.chat.memory.facts.FactView>> memoryFacts(@LoginUser Long userId,
            @PathVariable Long petId,@org.springframework.web.bind.annotation.RequestParam(defaultValue="40") int limit){
        return ApiResponse.ok(factMemory.list(userId,petId,limit));
    }

    @PostMapping("/memory-facts/retry")
    public ApiResponse<Integer> retryMemory(@LoginUser Long userId,@PathVariable Long petId){
        return ApiResponse.ok(memoryRetry.retry(userId,petId));
    }

    @PostMapping("/presence")
    public ApiResponse<com.lore.zzal.chat.presence.ChatPresenceResponse> presence(@LoginUser Long userId,
            @PathVariable Long petId,@Valid @RequestBody com.lore.zzal.chat.presence.ChatPresenceRequest request,
            @org.springframework.web.bind.annotation.RequestHeader(value="X-Zzal-Language",required=false) String language) {
        return ApiResponse.ok(presence.accept(userId,petId,request,Instant.now(),language));
    }

    @GetMapping("/history")
    @Operation(summary = "대화 원문 이력", description = "프롬프트 문맥과 별개로 날짜 제한 없이 원문을 커서로 읽는다")
    public ApiResponse<ChatResponses.History> history(@LoginUser Long userId, @PathVariable Long petId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) @jakarta.validation.constraints.Positive Long beforeId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "40") @jakarta.validation.constraints.Min(1)
            @jakarta.validation.constraints.Max(100) int limit) {
        return ApiResponse.ok(delivery.history(userId, petId, beforeId, limit));
    }

    @GetMapping("/history/legacy")
    @Operation(summary = "세션 도입 이전 대화 원문", description = "옛 부름 표의 사용자 답과 펫 원문도 날짜 제한 없이 읽는다")
    public ApiResponse<ChatResponses.LegacyHistory> legacyHistory(@LoginUser Long userId, @PathVariable Long petId,
            @org.springframework.web.bind.annotation.RequestParam(required = false) @jakarta.validation.constraints.Positive Long beforeId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "40") @jakarta.validation.constraints.Min(1)
            @jakarta.validation.constraints.Max(100) int limit) {
        return ApiResponse.ok(delivery.legacyHistory(userId, petId, beforeId, limit));
    }

    @Operation(summary = "대화 응답", description = """
            지속 대화에 자유 입력 40자로 한 마디 한다. 왕복·시간창 제한은 없다.
            친밀도·해금 보상은 기존 튜토리얼1회/일상창당1회 규칙을 따로 유지한다.

            응답은 변경된 캐릭터 상태(pet)·다음 펫 대사와 반응 동작(chatReply)·판 상태(session)·
            이번에 생긴 두 턴(turns = 사용자 턴, 펫 턴)으로 구성한다.

            누적 응답 횟수(판 수)는 답하기 동작 해금(4회)에 사용한다.
            새 클라이언트는 sessionId·expectedRound·requestId(UUID)를 함께 보낸다.
            같은 requestId와 같은 요청은 저장된 응답을 재사용하며 턴·왕복·모델 호출을 추가하지 않는다.
            다른 요청에 같은 requestId를 쓰면 ZZAL_CHAT_REQUEST_CONFLICT(409),
            지난 판이나 예상 왕복이 다르면 ZZAL_CHAT_SESSION_STALE(409)이다.
            식별자 셋이 모두 없는 옛 클라이언트는 기존 계약을 유지하며 이 보호의 대상이 아니다.""")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "응답 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "미개방·만료된 슬롯(ZZAL_CHAT_SLOT_CLOSED) · 수면 중(ZZAL_PET_SLEEPING)")})
    @PostMapping("/{slot}/answer")
    public ApiResponse<ChatResponses.Answered> answer(@LoginUser Long userId,
                                                      @PathVariable Long petId,
                                                      @PathVariable ChatSlot slot,
                                                      @Valid @RequestBody ChatRequests.Answer request) {
        return ApiResponse.ok(delivery.answer(userId, petId, slot, request, Instant.now()));
    }
}
