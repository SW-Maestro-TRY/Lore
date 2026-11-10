package com.lore.zzal.chat.prompt;

/** Server-selected goal only. User answers stay in existing history; this is not a new authority or persistence API. */
public record ConversationScene(Phase phase, Step step, Beat beat) {
    public ConversationScene(Phase phase,Step step){this(phase,step,Beat.ENTRY);}
    @io.swagger.v3.oas.annotations.media.Schema(name="ZzalConversationBeat")
    public enum Beat { ENTRY, AFTER_ACTION }
    public enum Phase { FIRST_MEETING, TUTORIAL, TUTORIAL_STAGE, CONTINUOUS }
    public enum Step { FEED, SNACK, CLEAN, BATH, GAME, NAP_SLEEP, NAP_WAKE, FIRST_MEETING_DISCOVERY,
        FINISH_THANKS, FINISH_TOMORROW, CHAT_INTRO, CHAT_MEMORY, CHAT_INVITE }
    public ConversationScene {
        java.util.Objects.requireNonNull(phase);
        java.util.Objects.requireNonNull(beat);
        if (step==Step.FIRST_MEETING_DISCOVERY) { if(phase!=Phase.FIRST_MEETING||beat!=Beat.ENTRY) throw new IllegalArgumentException("Discovery is first-meeting ENTRY only"); }
        else if (TutorialEndingSpeech.supports(step) && (phase != Phase.TUTORIAL_STAGE || beat != Beat.ENTRY)) throw new IllegalArgumentException("Ending scenes are tutorial-stage ENTRY only");
        else if (((phase == Phase.TUTORIAL || phase == Phase.TUTORIAL_STAGE)) != (step != null)) throw new IllegalArgumentException("Tutorial requires one server step; other phases cannot carry one");
    }
}
