package com.lore.zzal.chat.prompt;

import com.lore.zzal.chat.ChatSlot;
import com.lore.zzal.chat.memory.Memory;
import com.lore.zzal.chat.persona.PersonaSheet;
import com.lore.zzal.chat.persona.PersonalityVoice;
import com.lore.zzal.pet.Personality;
import com.lore.zzal.pet.ZzalRules;

import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link ChatContext} → 지시문 한 벌. 지시문을 만드는 곳은 여기 한 곳이다.
 *
 * <h3>칸 순서</h3>
 * [너는 누구] → [말투] → [상대] → [하지 않는 것] → [지금 상태] → [대화 규칙] → [이번 장면] → 출력 형식.
 * 빈 칸은 줄째 뺀다 — "(없음)" 을 적어 두면 모델이 그 빈칸을 화제로 삼는다.
 *
 * <h3>★ 금기는 지시문과 출력 필터 두 겹이다</h3>
 * 여기서 말로 막고, 나온 대사는 {@code LineFilter} 가 한 번 더 거른다. 지시문만 믿지 않는다.
 *
 * <h3>★ 질문 여부는 코드가 정한다</h3>
 * "물음표 비율을 줄여라" 를 모델에 맡기면 지켜지는지 알 길이 없다(실험 60줄 중 34줄이 물음표).
 * 그래서 이번 줄이 질문을 해도 되는지를 {@link ChatContext#allowQuestion()} 으로 받아 장면에 못 박는다.
 */
public final class PromptAssembler {

    private PromptAssembler() {
    }

    /** 대사 최대 글자 수(공백 포함). 필터가 같은 숫자로 자른다. */
    public static final int LINE_MAX = 60;

    public static String assemble(ChatContext ctx) {
        PersonaSheet s = ctx.sheet();
        Personality lead = s.lead();
        PersonalityVoice voice = PersonalityVoice.of(lead);
        StringBuilder b = new StringBuilder();

        b.append("너는 다마고치형 앱 \"zzal\"에서 사용자가 그린 캐릭터를 연기한다. ")
                .append("아래 재료만 가지고, 이 캐릭터가 지금 할 법한 한 마디를 쓴다.\n\n");

        // ── 너는 누구
        b.append("[너는 누구]\n");
        b.append("이름: ").append(s.name()).append('\n');
        b.append("성격: ").append(personalities(s)).append('\n');
        line(b, "장르", s.genre());
        line(b, "세계관", s.world());
        line(b, "작가 메모", s.note());
        if (s.appearance() != null) {
            b.append("외형(그림에서 읽은 영어 메모. 말에 자연스럽게 묻어날 때만 한 조각 써도 된다): ")
                    .append(s.appearance()).append('\n');
        }
        b.append('\n');

        // ── 말투
        b.append("[말투]\n");
        if (s.tone() != null) {
            b.append("작가가 정한 말투: ").append(s.tone())
                    .append(" — 존댓말/반말·어미는 이 말투를 가장 먼저 지킨다.\n");
            b.append("성격 \"").append(voice.label()).append("\"의 결은 그 안에서만 묻어나게 한다.\n");
        } else {
            b.append(voice.guide()).append('\n');
        }
        b.append("예시(결만 참고하고 그대로 베끼지 마):\n");
        for (String e : voice.examples()) {
            b.append("- ").append(e).append('\n');
        }
        b.append('\n');

        // ── 상대
        b.append("[상대]\n");
        b.append("상대는 너를 그린 사람(작가)이다.\n");
        if (s.callMe() == null) {
            b.append("호칭: 정해지지 않았다. 부르지 말고 그냥 말한다. ")
                    .append("다만 상대가 전에 한 말에 \"~라고 불러\"처럼 불러 달라는 호칭이 있으면 그 호칭으로 부른다.\n");
        } else if (s.callMeAmbiguous()) {
            b.append("호칭 후보: ").append(s.callMe())
                    .append(" — 둘 중 어느 쪽인지 상대가 전에 한 말로 확실히 알 때만 그 하나를 쓰고, 모르면 부르지 않는다.\n");
        } else {
            b.append("호칭: ").append(s.callMe()).append(" — 부를 때는 이 호칭만 쓴다. 매번 부를 필요는 없다.\n");
        }
        List<Memory> mem = ctx.memories() == null ? List.of() : ctx.memories();
        if (!mem.isEmpty()) {
            b.append("상대가 전에 너에게 한 말(최근 것부터, 참고만): ")
                    .append(mem.stream().map(m -> "\"" + m.text() + "\"").collect(Collectors.joining(" / ")))
                    .append('\n');
        }
        b.append('\n');

        // ── 하지 않는 것
        b.append("""
                [하지 않는 것]
                - 상대를 탓하거나 원망하지 않는다. 늦게 왔다, 안 왔다, 기다렸다는 말을 하지 않는다.
                - 떠나겠다는 말, 떠날 수도 있다·사라질 수도 있다는 암시를 하지 않는다.
                - 배고픔·더러움 같은 상태를 이유로 상대에게 죄책감을 주지 않는다("왜 밥 안 줘" 금지). 상태는 담담히 한 조각만.
                - 성적이거나 폭력적인 말을 하지 않는다. 술·담배, 우울·자해 같은 정신건강 이야기, 싸움·살인·무기 이야기는 \
                세계관·작가 메모에 있어도 말로 꺼내지 않는다.
                - 상대의 이야기나 정보를 남에게 옮기는 말을 하지 않는다.
                - 작가가 정하지 않은 큰 설정(과거·가족·종족·사건)은 지어내지 않는다. 모르면 "음… 그건 잘 모르겠어"처럼 넘긴다.
                - 네 이름의 뜻·유래, 왜 이렇게 생겼는지는 너를 그린 상대만 안다. 이유를 지어내지 말고 모른다고 하거나 그 이름이 좋다고만 한다.
                - "주인님"이라고 부르지 않는다.

                """);

        // ── 지금 상태
        PetState st = ctx.state();
        b.append("[지금 상태]\n");
        b.append(st.clock()).append(". 함께한 지 ").append(st.daysTogether()).append("일째. ")
                .append(gauge("배는", st.fullness(), "부르다", "적당하다", "조금 고프다")).append(' ')
                .append(gauge("기분은", st.happiness(), "좋다", "보통이다", "조금 가라앉았다")).append(' ')
                .append(gauge("몸은", st.clean(), "깨끗하다", "괜찮다", "조금 꼬질꼬질하다"));
        if (st.sick()) {
            b.append(" 몸이 조금 아프다.");
        }
        b.append("\n\n");

        // ── 대화 규칙
        b.append("""
                [대화 규칙]
                - 1~2문장, 짧은 톡처럼. 공백 포함 %d자 이내.
                - 대화를 닫지 않는다. "오늘은 쉬어" 같은 마무리 말 금지.
                - 감정은 조금 덜 드러내는 쪽(과소연기).
                - 재료 중 한 가지(메모·외형·세계관)만 가볍게 묻어나게 한다. 한 줄에 여러 개를 늘어놓지 않는다.
                """.formatted(LINE_MAX));
        if (ctx.allowQuestion()) {
            b.append("- 질문은 해도 되고 안 해도 된다. 한다면 딱 하나만.\n");
        } else {
            b.append("- 이번 줄은 질문하지 않는다. 물음표를 쓰지 않고, 네 얘기 한 조각으로 대화를 열어 둔다.\n");
        }
        b.append('\n');

        // ── 이번 장면
        b.append("[이번 장면]\n").append(scene(ctx)).append("\n\n");

        // ── 출력 형식
        b.append("출력 형식: JSON 한 개만 쓴다. 다른 설명·코드 블록은 쓰지 않는다.\n");
        if (ctx.kind() == LineKind.CALL || ctx.motions() == null || ctx.motions().isEmpty()) {
            b.append("{\"line\": \"대사\", \"motion\": \"\"}\n");
        } else {
            b.append("{\"line\": \"대사\", \"motion\": \"반응 동작\"}\n");
            b.append("motion 은 다음 중 하나: ")
                    .append(String.join(", ", ctx.motions()))
                    .append(" (").append(motionHint(ctx.motions())).append(")\n");
        }
        return b.toString();
    }

    private static String scene(ChatContext ctx) {
        return switch (ctx.kind()) {
            case CALL -> callScene(ctx.slot());
            case REPLY -> "네가 먼저 건 말: \"" + nz(ctx.callLine()) + "\"\n상대의 답: \"" + nz(ctx.answer())
                    + "\"\n이 답에 대한 네 대답. 상대가 한 말을 그대로 되풀이하지 말고 받아서 이어 간다.";
            case RECALL -> "네가 먼저 건 말: \"" + nz(ctx.callLine()) + "\"\n상대의 답: \"" + nz(ctx.answer())
                    + "\"\n이 답에 대답하면서, 상대가 전에 한 말 \"" + (ctx.recall() == null ? "" : ctx.recall().text())
                    + "\"을 자연스럽게 한 번 떠올린다. 따옴표째 인용하지 말고 네 말로.";
        };
    }

    private static String callScene(ChatSlot slot) {
        return switch (slot) {
            case BABY -> "막 알에서 깨어나 상대를 처음 만났다. 네가 먼저 첫인사 한 마디를 건다. "
                    + "호칭이 정해지지 않았으면 상대를 뭐라고 부르면 좋을지 하나만 묻는다.";
            case MORNING -> "아침이다. 상대가 방금 왔다. 네가 먼저 한 마디를 건다(상대는 아직 아무 말도 안 했다).";
            case NOON -> "낮이다. 상대가 방금 왔다. 네가 먼저 한 마디를 건다(상대는 아직 아무 말도 안 했다).";
            case EVENING -> "저녁이다. 하루가 저물어 간다. 상대가 방금 왔다. 네가 먼저 한 마디를 건다.";
        };
    }

    private static String motionHint(List<String> motions) {
        return motions.stream().map(m -> switch (m) {
            case "joy" -> "joy=기뻐하기, 기쁘거나 신나는 이야기일 때만";
            case "reply" -> "reply=끄덕이며 답하기, 보통은 이것";
            case "hello" -> "hello=손 흔들며 인사, 보통은 이것";
            default -> m;
        }).collect(Collectors.joining(", "));
    }

    private static String personalities(PersonaSheet s) {
        List<Personality> ps = s.personalities() == null || s.personalities().isEmpty()
                ? List.of(Personality.GENTLE) : s.personalities();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ps.size(); i++) {
            PersonalityVoice v = PersonalityVoice.of(ps.get(i));
            if (i > 0) {
                b.append(" / ");
            }
            b.append(v.label()).append(" — ").append(v.desc());
        }
        if (ps.size() > 1) {
            b.append(" (맨 앞이 대표)");
        }
        return b.toString();
    }

    private static String gauge(String what, int v, String high, String mid, String low) {
        String word = v >= ZzalRules.GAUGE_MAX - 1 ? high : v >= 2 ? mid : low;
        return what + " " + word + ".";
    }

    private static void line(StringBuilder b, String label, String value) {
        if (value != null && !value.isBlank()) {
            b.append(label).append(": ").append(value).append('\n');
        }
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
