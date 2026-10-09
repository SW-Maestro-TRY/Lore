package com.lore.zzal.chat.prompt;

import com.lore.zzal.chat.persona.PersonaSheet;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 시스템 메시지 캐시 — 펫당 하나. 시트({@link PersonaSheet})가 같으면 지난 글을 그대로 쓰고, 다르면 다시 만든다
 * (호칭이 저장되거나 작가가 설정을 고치면 그 다음 턴부터 새 글).
 *
 * ★ 글자까지 같은 앞부분을 매번 보내야 OpenAI 쪽 프롬프트 캐시도 먹는다. 여기 캐시는 조립 비용보다
 *   "같은 시트면 같은 글" 을 한 곳에서 보장하려는 것이다. 서버 메모리라 재시작하면 비고, 다시 만들어도 같은 글이다.
 */
@Component
public class SystemPromptCache {

    private record Entry(PersonaSheet sheet, String text) {
    }

    private final Map<Long, Entry> byPet = new ConcurrentHashMap<>();
    private final AtomicLong builds = new AtomicLong();

    public String get(Long petId, PersonaSheet sheet) {
        Entry e = byPet.get(petId);
        if (e != null && e.sheet().equals(sheet)) {
            return e.text();
        }
        String text = PromptAssembler.system(sheet);
        builds.incrementAndGet();
        byPet.put(petId, new Entry(sheet, text));
        return text;
    }

    /** 지금까지 새로 만든 횟수(시험·로그용). */
    public long builds() {
        return builds.get();
    }
}
