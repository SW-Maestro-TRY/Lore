package com.lore.zzal.chat.prompt;

import com.lore.zzal.chat.persona.PersonaSheet;
import com.lore.zzal.pet.FirstIntro;
import java.util.List;
import static com.lore.zzal.chat.prompt.ConversationScene.Step.*;

/** Presentation scenes share the existing server cursor; generated words never acknowledge it. */
public final class TutorialEndingSpeech {
    private TutorialEndingSpeech() { }
    public static final List<ConversationScene.Step> STEPS = List.of(
            FINISH_THANKS, FINISH_TOMORROW, CHAT_INTRO, CHAT_MEMORY, CHAT_INVITE);
    public static boolean supports(ConversationScene.Step step) { return step != null && STEPS.contains(step); }
    public static FirstIntro.Step position(ConversationScene.Step step) {
        return switch (step) {
            case FINISH_THANKS, FINISH_TOMORROW -> FirstIntro.Step.FINISH_NOTICE;
            case CHAT_INTRO, CHAT_MEMORY, CHAT_INVITE -> FirstIntro.Step.REVIEW_SEEN;
            default -> throw new IllegalArgumentException("Not an ending scene");
        };
    }
    public static String instruction(ConversationScene.Step step) {
        return switch (step) {
            case FINISH_THANKS -> "함께 돌봄과 놀이를 해 준 상대에게 캐릭터의 말투로 짧게 고마움을 전한다. 작별하거나 새 행동을 요구하지 않는다.";
            case FINISH_TOMORROW -> "다음 출석에 새 배경 선물이 기다린다는 뜻을 캐릭터의 말투로 짧게 전한다. 이미 받았다고 하거나 배경의 모습·지급 시각을 지어내지 않는다.";
            case CHAT_INTRO -> "이제 대화로 서로 알아가고 추억을 쌓을 수 있다는 뜻을 캐릭터의 말투로 짧게 소개한다. 대화예시는 별도 샘플이며 우리와 이미 나눈 대화가 아니다.";
            case CHAT_MEMORY -> "우리가 앞으로 나눌 이야기가 쌓이면 캐릭터의 새로운 모습과 행동으로 이어질 수 있다는 뜻을 짧게 설명한다. 방금 본 예시는 별도 캐릭터의 창작 예시이며 우리 기억이나 이미 획득한 행동으로 말하지 않는다. 특정 횟수·시기·동작 지급을 보장하지 않는다.";
            case CHAT_INVITE -> "저장된 호칭이 있으면 문장 앞부분에서 정확히 한 번 자연스럽게 부르며 이제 상대의 이야기를 들려 달라고 초대한다. 호칭 사양은 존중하고, 미정이면 사용자님을 선택 언어에 맞게 쓴다. 호칭 뒤에 네·당신 같은 이인칭을 다시 덧붙이거나 문장 끝에 호칭을 붙이지 않는다. 호칭을 다시 묻지 않는다.";
            default -> throw new IllegalArgumentException("Not an ending scene");
        };
    }
    /** Only a failure/legacy-cache escape hatch; never labelled as persona-generated speech. */
    public static String fallback(ConversationScene.Step step, PersonaSheet sheet) {
        String language = sheet.preferredResponseLanguage();
        String call = sheet.callMeDeclined() ? "" : sheet.callMe();
        if (call == null || call.isBlank() || call.equals("사용자님"))
            call = sheet.callMeDeclined() ? "" : switch (language) { case "en" -> "User"; case "ja" -> "あなた"; default -> "사용자님"; };
        String prefix = call.isBlank() ? "" : call + ", ";
        return switch (language) {
            case "en" -> switch (step) {
                case FINISH_THANKS -> "Thank you for taking care of me and playing with me.";
                case FINISH_TOMORROW -> "A new background gift will be waiting at your next attendance.";
                case CHAT_INTRO -> "We can get to know each other and make memories through our chats.";
                case CHAT_MEMORY -> "The stories we share can inspire new looks and actions for me.";
                case CHAT_INVITE -> prefix + "tell me what's on your mind.";
                default -> throw new IllegalArgumentException("Not an ending scene");
            };
            case "ja" -> switch (step) {
                case FINISH_THANKS -> "お世話して、一緒に遊んでくれてありがとう。";
                case FINISH_TOMORROW -> "次の出席では、新しい背景のプレゼントが待っているよ。";
                case CHAT_INTRO -> "これからお話ししながら、お互いを知って思い出を作っていこう。";
                case CHAT_MEMORY -> "一緒に話したことが、新しい姿や動きにつながることもあるよ。";
                case CHAT_INVITE -> (call.isBlank() ? "" : call + "、") + "今度は話を聞かせて。";
                default -> throw new IllegalArgumentException("Not an ending scene");
            };
            default -> switch (step) {
                case FINISH_THANKS -> "돌봐 주고 함께 놀아 줘서 고마워.";
                case FINISH_TOMORROW -> "다음 출석에는 새 배경 선물이 기다리고 있어.";
                case CHAT_INTRO -> "이제 이야기하면서 서로 알아가고 추억도 쌓아 가자.";
                case CHAT_MEMORY -> "우리가 나눈 이야기가 쌓이면 나의 새로운 모습과 행동으로 이어지기도 해.";
                case CHAT_INVITE -> prefix + "이제 이야기를 들려줘.";
                default -> throw new IllegalArgumentException("Not an ending scene");
            };
        };
    }
}
