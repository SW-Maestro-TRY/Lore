package com.lore.zzal.chat.presence;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity
@Table(name="zzal_chat_presence_receipt",uniqueConstraints=@UniqueConstraint(name="uk_zzal_chat_presence_event",columnNames={"pet_id","event_id"}))
public class ZzalChatPresenceReceipt {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="pet_id",nullable=false) private Long petId;
    @Column(name="event_id",nullable=false) private UUID eventId;
    @Column(name="request_json",nullable=false,columnDefinition="text") private String requestJson;
    @Column(name="response_json",nullable=false,columnDefinition="text") private String responseJson;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    protected ZzalChatPresenceReceipt() { }
    public ZzalChatPresenceReceipt(Long petId,UUID eventId,String request,String response,Instant now) {
        this.petId=petId;this.eventId=eventId;requestJson=request;responseJson=response;createdAt=now;
    }
    public boolean matches(String request) { return requestJson.equals(request); }
    public String responseJson() { return responseJson; }
}
