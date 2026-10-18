package com.lore.zzal.chat.presence;
import com.lore.zzal.chat.dto.ChatResponses;
public record ChatPresenceResponse(String outcome, ChatResponses.Chat chat) { }
