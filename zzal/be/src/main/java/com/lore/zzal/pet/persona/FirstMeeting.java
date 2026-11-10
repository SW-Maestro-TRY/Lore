package com.lore.zzal.pet.persona;

import com.lore.zzal.pet.ZzalPet;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

/** 첫 만남 연출은 케어 튜토리얼/생활 시계/채팅 보상과 별개다. */
public final class FirstMeeting {
    private FirstMeeting() { }
    public enum State { DISCOVERED, ADDRESS, READY_TO_ENTER, COMPLETE }
    public enum Action { NEXT, SET_ADDRESS, DEFER_ADDRESS, ENTER_ROOM, PREPARE_DISCOVERY }
    @io.swagger.v3.oas.annotations.media.Schema(name="ZzalFirstMeetingRequest")
    public record Request(@NotNull Action action, @Size(max = 20) String callMe) {
        @JsonIgnore @AssertTrue(message = "호칭 입력을 확인해 주세요")
        public boolean isAddressValid() {
            if (action == Action.SET_ADDRESS) return callMe != null && !callMe.isBlank();
            return callMe == null;
        }
    }
    @io.swagger.v3.oas.annotations.media.Schema(name="ZzalFirstMeetingView")
    public record View(int version, State state, String callMe, String addressStatus,
                       String discoveryLine, String greetingLine, boolean greetingRetryAllowed, @io.swagger.v3.oas.annotations.media.Schema(nullable=true,description="발견문 준비 상태, 구 응답은 null") String discoveryStatus, @io.swagger.v3.oas.annotations.media.Schema(nullable=true,description="READY 발견문의 LLM/FALLBACK 출처, 그 외 null") String discoverySource) {
        public View(int version,State state,String callMe,String addressStatus,String discoveryLine,String greetingLine,boolean greetingRetryAllowed){this(version,state,callMe,addressStatus,discoveryLine,greetingLine,greetingRetryAllowed,null,null);}
        public View withDiscovery(String line,String status,String source) {
            return new View(version,state,callMe,addressStatus,line==null||line.isBlank()?discoveryLine:line,greetingLine,greetingRetryAllowed,status,source);
        }
        public View withGreeting(String line, boolean retryAllowed) {
            return new View(version, state, callMe, addressStatus, discoveryLine, line, retryAllowed,discoveryStatus,discoverySource);
        }
        public static View of(ZzalPet pet) {
            State state = pet.getFirstMeetingState();
            String source = pet.getCallMeSource();
            String status = source != null ? source
                    : pet.getCallMe() == null ? "UNPROVIDED" : "LEGACY";
            String call = "DEFERRED".equals(source) ? null : pet.getCallMe();
            // Before address confirmation this is service copy, not an invented character voice.
            return new View(1, state == null ? State.COMPLETE : state, call, status,
                    "캐릭터와 처음 만나는 순간이에요.", "", true);
        }
    }
}
