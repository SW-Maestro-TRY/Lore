package com.lore.zzal.chat.delivery;

import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.dto.ChatRequests;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** 답과 응답을 같은 트랜잭션에 기록한다. 재전송은 저장된 응답만 읽는다. */
@Entity
@Table(name = "zzal_chat_delivery", uniqueConstraints = @UniqueConstraint(
        name = "uk_zzal_chat_delivery_pet_request", columnNames = {"pet_id", "request_id"}))
public class ZzalChatDelivery {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "pet_id", nullable = false) private Long petId;
    @Column(name = "request_id", nullable = false) private UUID requestId;
    @Column(name = "session_id", nullable = false) private Long sessionId;
    @Column(name = "expected_round", nullable = false) private int expectedRound;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private ChatSlot slot;
    @Column(nullable = false, length = 40) private String text;
    @Column(name = "response_json", nullable = false, columnDefinition = "text") private String responseJson;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected ZzalChatDelivery() { }

    public ZzalChatDelivery(Long petId, ChatSlot slot, ChatRequests.Answer request, String json, Instant now) {
        this.petId = petId;
        this.slot = slot;
        this.requestId = request.requestId();
        this.sessionId = request.sessionId();
        this.expectedRound = request.expectedRound();
        this.text = request.text();
        if(request.tutorial()!=null){
            try {var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var tree=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(json);
                tree.set("_tutorial",mapper.valueToTree(request.tutorial()));this.responseJson=mapper.writeValueAsString(tree);
            }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        }else this.responseJson = json;
        this.createdAt = now;
    }

    public boolean matches(ChatSlot slot, ChatRequests.Answer request) {
        boolean tutorialMatches;
        try {var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var stored=mapper.readTree(responseJson).get("_tutorial");
            tutorialMatches=java.util.Objects.equals(stored,request.tutorial()==null?null:mapper.valueToTree(request.tutorial()));
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        return tutorialMatches && this.slot == slot && sessionId.equals(request.sessionId())
                && expectedRound == request.expectedRound() && text.equals(request.text());
    }
    public java.util.Optional<ChatRequests.Answer.Tutorial> tutorial(){
        try {var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var value=mapper.readTree(responseJson).get("_tutorial");
            return value==null?java.util.Optional.empty():java.util.Optional.of(mapper.treeToValue(value,ChatRequests.Answer.Tutorial.class));
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Invalid stored tutorial receipt",e);}
    }
    public UUID requestId(){return requestId;}
    public Long sessionId(){return sessionId;}
    public int expectedRound(){return expectedRound;}
    public String text(){return text;}

    public String responseJson() {
        try{var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var tree=mapper.readTree(responseJson);if(tree instanceof com.fasterxml.jackson.databind.node.ObjectNode object && object.has("_tutorial")){object.remove("_tutorial");return mapper.writeValueAsString(object);}return responseJson;}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
    }
}
