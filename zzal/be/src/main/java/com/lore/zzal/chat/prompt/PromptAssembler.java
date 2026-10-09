package com.lore.zzal.chat.prompt;

import com.lore.zzal.chat.persona.PersonaSheet;
import com.lore.zzal.chat.session.QuestionItem;
import com.lore.zzal.chat.session.Speaker;
import com.lore.zzal.chat.session.TurnPlan;
import com.lore.zzal.pet.Personality;
import com.lore.zzal.text.Josa;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 지시문 조립 — 호출 1회 = <b>시스템 메시지</b>(고정 + 시트, 펫당 같음) + <b>사용자 메시지</b>(이번 턴). 조립하는 곳은 여기 한 곳이다.
 *
 * <h3>A. 시스템 메시지</h3>
 * [너에 대해](시트, 빈 칸은 줄째 뺀다) · [말하는 법](고정) · [출력]. 펫이 같고 시트가 같으면 글자까지 같다 —
 * 캐시({@code SystemPromptCache})가 이 성질에 기댄다. 그래서 여기에는 <b>시각·상태·턴</b>을 넣지 않는다.
 *
 * <h3>B. 사용자 메시지</h3>
 * [지금](요일·시각·만난 날·상태 단어) · [지금까지](지난 판 마지막 말 1줄 + 이번 판 최대 3왕복) · [이번 턴](종류·할 일).
 * [이번 턴]은 {@link TurnPlan} 그대로다 — 질문 여부·항목은 코드가 정했다.
 */
public final class PromptAssembler {

    private PromptAssembler() {
    }

    /** 대사 최대 글자 수. 필터가 같은 숫자로 거른다. */
    public static final int LINE_MAX = 60;

    /** 시스템 메시지가 [출력] 에 적는 동작 목록. 실제로 고를 수 있는 것은 턴마다 코드가 다시 거른다. */
    public static final List<String> MOTIONS = List.of("hello", "reply", "joy");

    static final String RULES = """
            [말하는 법]
            - 한 번에 한 줄, 60자 이내. 말만 한다. 지문·괄호·이모지·동작 묘사 금지.
            - 네가 사는 곳과 작가 메모에 있는 단어만 쓴다. 없는 설정·사건·인물을 만들지 않는다.
            - 상대가 한 말을 받아서 말한다. 상대 말을 네 세계 식으로 해석해도 된다.
            - 질문은 [이번 턴]에서 허락할 때만, 하나만.
            - 하지 않는 말: 상대 원망, 떠난다는 암시, 네 상태로 죄책감 주기, 성적·폭력·음주·정신건강 소재,
              이름의 유래 지어내기, "주인님", 상대의 개인정보 되풀이.
            - 상대가 금기 소재를 꺼내면 가볍게 화제를 돌린다.
            """;

    // ── A. 시스템 ─────────────────────────────────────────────────────────

    public static String system(PersonaSheet s) {
        StringBuilder b = new StringBuilder();
        b.append("너는 '").append(s.name()).append("'").append(Josa.of(s.name(), "이다", "다"))
                .append(". 작가가 만든 캐릭터이고, 작가와 짧은 대화를 나눈다.\n\n");
        b.append("[너에 대해]\n");
        if (s.personalities() != null && !s.personalities().isEmpty()) {
            b.append("성격: ").append(s.personalities().stream().map(PromptAssembler::label)
                    .collect(Collectors.joining(", "))).append('\n');
        }
        b.append("말투: ").append(s.tone() == null ? "반말, 짧게" : s.tone()).append('\n');
        line(b, "네가 사는 곳", s.world());
        line(b, "작가 메모", s.note());
        line(b, "네 모습", s.appearance());
        if (s.callMe() != null) {
            b.append("상대를 부르는 말: ").append(s.callMe()).append('\n');
        } else if (s.callMeDeclined()) {
            b.append("상대를 따로 부르지 않는다. '너'라고 한다.\n");
        } else {
            b.append("상대를 뭐라고 부를지 아직 모른다. '너'라고 한다.\n");
        }
        b.append('\n').append(RULES).append('\n');
        b.append("[출력]\n");
        b.append("JSON 한 줄: {\"line\": \"<대사>\", \"motion\": \"<")
                .append(String.join(", ", MOTIONS)).append(" 중 하나>\"}\n");
        return b.toString();
    }

    // ── B. 사용자(이번 턴) ─────────────────────────────────────────────────

    public static String user(ChatContext ctx) {
        StringBuilder b = new StringBuilder();
        PetState st = ctx.state();
        b.append("[지금]\n");
        b.append(st.when()).append(", 만난 지 ").append(st.daysTogether()).append("일째.\n");
        b.append("상태: ").append(st.words()).append("\n\n");

        List<HistoryLine> hist = recent(ctx.history());
        if (ctx.lastSessionLine() != null || !hist.isEmpty()) {
            b.append("[지금까지]\n");
            if (ctx.lastSessionLine() != null) {
                b.append("지난 대화 마지막 말: ").append(ctx.lastSessionLine()).append('\n');
            }
            if (!hist.isEmpty()) {
                b.append("이번 대화:\n");
                for (HistoryLine h : hist) {
                    b.append(h.speaker() == Speaker.PET ? "너: " : "상대: ").append(h.line()).append('\n');
                }
            }
            b.append('\n');
        }

        TurnPlan p = ctx.plan();
        b.append("[이번 턴]\n");
        b.append("종류: ").append(p.type().label()).append('\n');
        b.append("할 일: ").append(task(p, ctx.lastSessionLine())).append('\n');
        return b.toString();
    }

    /** [이번 턴] 의 할 일 — 턴 종류마다 한 문장. */
    static String task(TurnPlan p, String last) {
        QuestionItem item = p.item();
        String q = item == null ? null : "\"" + item.text() + "\"";
        String l = last == null ? null : "\"" + last + "\"";
        return switch (p.type()) {
            case FIRST_MEET -> q == null ? "네가 있는 곳 한 조각을 말하며 인사한다."
                    : "네가 있는 곳 한 조각을 말하며 인사하고, " + q + "을 하나 묻는다.";
            case GREETING -> {
                if (l != null && q != null) {
                    yield "인사하고 " + l + "을 짧게 받은 뒤 " + q + "을 묻는다.";
                } else if (l != null) {
                    yield "인사하고 " + l + "을 짧게 받는다.";
                } else if (q != null) {
                    yield "인사하고 " + q + "을 묻는다.";
                }
                yield "인사한다.";
            }
            case REUNION -> l == null ? "반가워하되 원망 없이. 질문 없음."
                    : "반가워하되 원망 없이. " + l + "을 받는다. 질문 없음.";
            case CONTINUE -> {
                StringBuilder t = new StringBuilder("상대의 마지막 말을 받아서 한 줄. 질문 ")
                        .append(p.allowQuestion() ? "허용." : "금지.");
                if (p.answerFirst()) {
                    t.append(" 상대가 물었으니 먼저 답한다.");
                }
                if (q != null) {
                    t.append(" 묻는다면 ").append(q).append("을 하나 묻는다.");
                }
                yield t.toString();
            }
            case CLOSE -> "네가 할 일로 돌아가며 끝낸다. 질문 금지. 다음에 또 말 걸겠다는 뜻을 담는다.";
        };
    }

    /** 이번 판의 최근 3왕복(펫 턴부터 시작하게 자른다). */
    static List<HistoryLine> recent(List<HistoryLine> all) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        int max = ChatContext.HISTORY_ROUNDS * 2;
        int from = Math.max(0, all.size() - max);
        if (from > 0 && all.get(from).speaker() != Speaker.PET) {
            from += 1;
        }
        return all.subList(from, all.size());
    }

    public static String label(Personality p) {
        return switch (p) {
            case GENTLE -> "온순";
            case LIVELY -> "활발";
            case SHY -> "수줍음";
            case CLINGY -> "응석";
            case COOL -> "시크";
        };
    }

    private static void line(StringBuilder b, String label, String value) {
        if (value != null && !value.isBlank()) {
            b.append(label).append(": ").append(value).append('\n');
        }
    }
}
