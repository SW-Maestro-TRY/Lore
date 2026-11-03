package com.lore.webtoon.play;

import com.lore.webtoon.character.CharacterService;
import com.lore.webtoon.character.CharacterStatus;
import com.lore.webtoon.character.WebtoonCharacter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 운영 세계의 사건 예시 인물(events[].example_people)을 체험에서 고를 수 있는 캐릭터로 만든다(2026-11-03).
 *
 * <p>서버가 뜰 때 사진(face_url)이 있는 사람마다 캐릭터를 하나 심고, 그 id 를 사건의 examples 로 내보낸다
 * ({@link PlayCatalog#examples}). 사진은 jar 안 {@code play/people/<face_url 파일 이름>} 에서 읽는다 — 화면용
 * 사본(webtoon/fe/static/play)과 같은 그림이다. 사진이 아직 없는 사람은 빼 둔다(그림 없이는 시트를 못 그린다).
 *
 * <p>캐릭터 id 는 세계 · 사건 · 이름에서 정해지므로 다시 떠도 같은 캐릭터를 쓴다.
 */
@Component
public class PlayPeople implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlayPeople.class);

    private final PlayCatalog catalog;
    private final CharacterService characters;
    private final PlayWorldRepository edited;

    public PlayPeople(PlayCatalog catalog, CharacterService characters, PlayWorldRepository edited) {
        this.catalog = catalog;
        this.characters = characters;
        this.edited = edited;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Map<String, Object> world : catalog.worlds()) {
            String worldId = String.valueOf(world.get("id"));
            if (edited.existsById(worldId)) {
                continue;                       // 관리자가 고친 세계 — 예시 인물은 편집본이 맡는다(파일 값으로 되돌리지 않는다)
            }
            for (Object e : PlayWorldService.list(world.get("events"))) {
                if (!(e instanceof Map<?, ?> event)) {
                    continue;
                }
                String eventId = String.valueOf(event.get("id"));
                List<String> ids = new ArrayList<>(PlayWorldService.ids(event.get("examples")));
                for (Object p : PlayWorldService.list(event.get("example_people"))) {
                    if (p instanceof Map<?, ?> person) {
                        String id = seed(worldId, eventId, person);
                        if (id != null && !ids.contains(id) && ids.size() < PlayWorldService.MAX_EXAMPLES) {
                            ids.add(id);
                        }
                    }
                }
                catalog.examples(eventId, ids);
            }
        }
    }

    private String seed(String worldId, String eventId, Map<?, ?> person) {
        String name = PlayWorldService.text(person.get("name"), 30);
        String face = PlayWorldService.text(person.get("face_url"), 300);
        if (name.isEmpty() || face.isEmpty()) {
            return null;
        }
        byte[] image = photo(face);
        if (image == null) {
            log.warn("세계관 예시 인물 사진이 jar 에 없습니다 — 빼 둡니다: {} ({})", name, face);
            return null;
        }
        try {
            WebtoonCharacter one = characters.seedPlayPerson(idOf(worldId, eventId, name), worldId, name,
                    description(person), image);
            return one.getStatus() == CharacterStatus.READY ? one.getPublicId() : null;
        } catch (Exception ex) {                                         // 한 사람 실패로 서버가 안 뜨면 안 된다
            log.error("세계관 예시 인물을 못 심었습니다: {}", name, ex);
            return null;
        }
    }

    /** 캐릭터 설명 — 나이 · 설정 · 이 사건과의 관계. 체험의 「설명」 칸과 시트 사양이 이 글을 읽는다. */
    static String description(Map<?, ?> person) {
        String age = PlayWorldService.text(person.get("age"), 10);
        String desc = PlayWorldService.text(person.get("desc"), 600);
        String relation = PlayWorldService.text(person.get("relation"), 600);
        StringBuilder s = new StringBuilder();
        if (!age.isEmpty()) {
            s.append(age).append("세. ");
        }
        s.append(desc);
        if (!relation.isEmpty()) {
            s.append(s.isEmpty() ? "" : " ").append(relation);
        }
        return s.toString().trim();
    }

    static String idOf(String worldId, String eventId, String name) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1")
                    .digest((worldId + "/" + eventId + "/" + name).getBytes(StandardCharsets.UTF_8));
            return "pp" + HexFormat.of().formatHex(h).substring(0, 18);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] photo(String faceUrl) {
        String file = faceUrl.substring(faceUrl.lastIndexOf('/') + 1);
        ClassPathResource r = new ClassPathResource("play/people/" + file);
        if (file.isBlank() || !r.exists()) {
            return null;
        }
        try (InputStream in = r.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }
}
