package com.lore.zzal.chat.presence;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record ChatPresenceRequest(@NotNull UUID visitId, @NotNull UUID eventId, @Positive long sequence,
                                  @NotNull Event event, EntryKind entryKind) {
    public enum Event { ENTER, LEAVE }
    public enum EntryKind { OPEN, RETURN, RELOAD }
    @com.fasterxml.jackson.annotation.JsonIgnore
    @jakarta.validation.constraints.AssertTrue(message = "ENTER requires entryKind")
    public boolean isValidEntryKind() { return event != Event.ENTER || entryKind != null; }
}
