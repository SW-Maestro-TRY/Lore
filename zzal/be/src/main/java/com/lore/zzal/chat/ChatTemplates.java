package com.lore.zzal.chat;

import com.lore.zzal.pet.Personality;

import java.util.List;
import java.util.Map;

/**
 * 채팅 대사 템플릿 v0 — 성격 5그룹 × 슬롯 4 부름 + 답 3벌(플랜 "채팅 v0 = 템플릿", {@code app.zzal.chat.mode=template}).
 *
 * <h3>규칙</h3>
 * <ul>
 *   <li>어느 성격·상황에서도 사용자를 원망하지 않는다(정본 0장 6). 놓친 부름을 언급하지 않는다(만료 = 패널티 0)</li>
 *   <li>{name} = 펫 이름, {memory} = 최근 답 하나(있을 때만 쓰는 줄)</li>
 *   <li>성격을 아직 안 골랐으면 온순(GENTLE) 톤</li>
 * </ul>
 * 상훈님 톤 리뷰 전의 초안이다. 문구만 고치면 되고 구조는 안 바뀐다.
 */
public final class ChatTemplates {

    private ChatTemplates() {
    }

    private record Set(String baby, String morning, String noon, String evening, List<String> replies, String recall) {
    }

    private static final Map<Personality, Set> SETS = Map.of(
            Personality.GENTLE, new Set(
                    "저… 안녕하세요. 뭐라고 불러 드리면 좋을까요?",
                    "좋은 아침이에요. 오늘은 뭐 하실 거예요?",
                    "점심은 드셨어요? 저는 방금 기지개를 켰어요.",
                    "하루 어땠어요? 저는 오늘 {name}답게 잘 지냈어요.",
                    List.of("그렇군요. 말해 줘서 고마워요.", "음, 그럼 저도 그렇게 해 볼게요.", "네, 기억해 둘게요."),
                    "저번에 '{memory}' 라고 하셨죠. 그 뒤로 어때요?"),
            Personality.LIVELY, new Set(
                    "우와, 드디어 만났다! 이름이 뭐예요?",
                    "굿모닝! 오늘 뭐 재밌는 일 있어요?",
                    "점심 뭐 먹었어요? 저도 궁금해요!",
                    "저녁이다! 오늘 제일 좋았던 거 하나만!",
                    List.of("오오 좋다! 나도 그거 좋아해요!", "헤헤, 알겠어요!", "그거 재밌겠다, 다음엔 같이 해요!"),
                    "아 맞다, '{memory}' 그거요! 또 있어요?"),
            Personality.SHY, new Set(
                    "…안녕. 조금만 천천히 말해 줄래요?",
                    "…일어났어요? 좋은 아침이에요.",
                    "저기… 오늘은 어때요?",
                    "…오늘도 와 줘서, 고마워요.",
                    List.of("…그렇구나.", "…알았어요. 기억할게요.", "…응, 나도요."),
                    "…전에 '{memory}' 라고 했던 거, 기억나요."),
            Personality.CLINGY, new Set(
                    "히히, 처음 뵙겠습니다! 저 이름 있어요?",
                    "일어났어요? 오늘도 같이 있자요!",
                    "심심했어요! 지금 뭐 해요?",
                    "저녁이에요~ 오늘 저 잘했죠?",
                    List.of("그치그치! 저도 그렇게 생각했어요!", "에헤헤, 좋아요!", "응! 그럼 다음에도 얘기해 줘요!"),
                    "저번에 '{memory}' 라고 했잖아요, 저 다 기억해요!"),
            Personality.COOL, new Set(
                    "…왔군. 뭐라고 부르면 돼?",
                    "일어났나. 오늘 계획은?",
                    "낮이군. 별일 없나.",
                    "하루 끝. 오늘은 어땠지?",
                    List.of("그런가. 알겠다.", "…나쁘지 않군.", "기억해 두지."),
                    "전에 '{memory}' 라고 했지. 그건 어떻게 됐나."));

    /** 부름 한 줄. */
    public static String call(Personality personality, ChatSlot slot, String petName) {
        Set set = SETS.get(personality == null ? Personality.GENTLE : personality);
        String line = switch (slot) {
            case BABY -> set.baby();
            case MORNING -> set.morning();
            case NOON -> set.noon();
            case EVENING -> set.evening();
        };
        return line.replace("{name}", petName);
    }

    /**
     * 답에 대한 대사. 답 텍스트로 벌을 고르고(같은 답이면 같은 대사 — 결정적), 기억이 있고 세 번에 한 번은 재언급.
     */
    public static String reply(Personality personality, String answer, List<String> memories, int answerCount) {
        Set set = SETS.get(personality == null ? Personality.GENTLE : personality);
        if (!memories.isEmpty() && answerCount > 0 && answerCount % 3 == 0) {
            return set.recall().replace("{memory}", memories.get(0));
        }
        int i = Math.floorMod(answer == null ? 0 : answer.hashCode(), set.replies().size());
        return set.replies().get(i);
    }
}
