package com.lore.webtoon.push;

import com.lore.webtoon.job.Refunded;

import java.util.Map;

/**
 * 알림 문구. 제목은 <b>진행 화면에 이미 떠 있는 제목 그대로</b>다 — 알림을 누르고
 * 들어온 사람이 같은 말을 다시 보게 한다. 완성·실패는 메일 제목과 같다.
 * 번역도 화면 사전({@code webtoon/fe/screens/progress/i18n.ts})에 있는 것을 옮겼다.
 *
 * 언어는 구독할 때의 화면 언어다. 모르는 언어면 한국어.
 */
final class PushMessages {

    record Message(String title, String body) {
    }

    private PushMessages() {
    }

    private static final Map<String, Map<String, String>> T = Map.of(
            "ko", Map.ofEntries(
                    Map.entry("CAST", "누구와의 이야기로 갈까요?"),
                    Map.entry("PICK", "이야기를 확인해 주세요"),
                    Map.entry("SCENES", "1화를 확인해 주세요"),
                    Map.entry("SHEET", "캐릭터 시트를 확인해 주세요"),
                    Map.entry("DONE", "웹툰이 다 만들어졌어요"),
                    Map.entry("FAILED", "웹툰을 다 만들지 못했어요"),
                    Map.entry("continue", "답해 주시면 이어서 만들어요."),
                    Map.entry("ready", "지금 바로 볼 수 있어요."),
                    Map.entry("retry", "다시 시도해 주시면 처음부터 새로 그려 드려요."),
                    Map.entry("credit", "사용된 크레딧은 자동으로 환불되었어요."),
                    Map.entry("free", "사용한 무료 생성 횟수는 자동으로 복구되었어요.")),
            "en", Map.ofEntries(
                    Map.entry("CAST", "Whose story should it be?"),
                    Map.entry("PICK", "Check the story"),
                    Map.entry("SCENES", "Check episode 1"),
                    Map.entry("SHEET", "Check the character sheet"),
                    Map.entry("DONE", "Your webtoon is ready"),
                    Map.entry("FAILED", "We couldn't finish your webtoon"),
                    Map.entry("continue", "Reply and we'll keep going."),
                    Map.entry("ready", "You can read it now."),
                    Map.entry("retry", "Try again and we'll draw it from scratch."),
                    Map.entry("credit", "The credits used have been refunded automatically."),
                    Map.entry("free", "Your free creation has been restored automatically.")),
            "ja", Map.ofEntries(
                    Map.entry("CAST", "誰との物語にしますか？"),
                    Map.entry("PICK", "ストーリーを確認してください"),
                    Map.entry("SCENES", "第1話を確認してください"),
                    Map.entry("SHEET", "キャラクターシートを確認してください"),
                    Map.entry("DONE", "ウェブトゥーンが完成しました"),
                    Map.entry("FAILED", "ウェブトゥーンを完成できませんでした"),
                    Map.entry("continue", "お答えいただくと続きを作ります。"),
                    Map.entry("ready", "今すぐ読めます。"),
                    Map.entry("retry", "もう一度お試しいただくと、最初から描き直します。"),
                    Map.entry("credit", "使用したクレジットは自動的に返金されました。"),
                    Map.entry("free", "使用した無料生成回数は自動的に戻りました。")),
            "zh", Map.ofEntries(
                    Map.entry("CAST", "要和谁的故事？"),
                    Map.entry("PICK", "请确认故事"),
                    Map.entry("SCENES", "请确认第 1 话"),
                    Map.entry("SHEET", "请确认角色设定图"),
                    Map.entry("DONE", "网络漫画完成了"),
                    Map.entry("FAILED", "网络漫画没能完成"),
                    Map.entry("continue", "回复后我们会继续制作。"),
                    Map.entry("ready", "现在就可以看了。"),
                    Map.entry("retry", "重试的话会从头重新画。"),
                    Map.entry("credit", "已使用的点数已自动退还。"),
                    Map.entry("free", "已使用的免费次数已自动恢复。")));

    static String normalize(String lang) {
        String l = lang == null ? "" : lang.trim().toLowerCase();
        return T.containsKey(l) ? l : "ko";
    }

    static Message of(JobPush.Kind kind, String lang, String workTitle, Refunded back) {
        Map<String, String> t = T.get(normalize(lang));
        String named = workTitle == null || workTitle.isBlank() ? "" : "「" + workTitle + "」 ";
        String body = switch (kind) {
            case DONE -> named + t.get("ready");
            case FAILED -> back == Refunded.CREDIT ? t.get("credit")
                    : back == Refunded.FREE ? t.get("free")
                    : t.get("retry");
            default -> named + t.get("continue");
        };
        return new Message(t.get(kind.name()), body.trim());
    }
}
