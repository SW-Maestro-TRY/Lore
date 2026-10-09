package com.lore.zzal.chat.prompt;

import com.lore.zzal.chat.session.Speaker;

/** [지금까지] 의 한 줄 — 너: … / 상대: … */
public record HistoryLine(Speaker speaker, String line) {
}
