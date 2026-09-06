package com.lore.webtoon.character;

import com.lore.common.exception.BusinessException;
import com.lore.webtoon.CreditGate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 캐릭터 탭이 부르는 자리.
 *
 * 웹툰 진행 화면과 같은 규칙으로 <b>봉투를 안 씌운다</b> — 이 화면들은
 * 프로토타입에서 옮겨 온 것이라 파이썬이 주던 모양을 그대로 읽고, 봉투를
 * 씌우면 사유 대신 "[object Object]" 가 뜬다(JobController 주석 참고).
 */
@Tag(name = "Webtoon", description = "캐릭터")
@RestController
@RequestMapping(CharacterController.PREFIX)
public class CharacterController {

    static final String PREFIX = "/api/webtoon/characters";

    private final CharacterService characters;

    public CharacterController(CharacterService characters) {
        this.characters = characters;
    }

    @Operation(summary = "고를 수 있는 캐릭터", description = """
            내가 만든 것과 기본 제공. 로그인 안 했으면 기본 제공만 나온다.
            남이 만든 것은 여기 안 섞인다(#259).""")
    @GetMapping
    public Map<String, Object> list() {
        Long me = CreditGate.currentUser();
        List<Map<String, Object>> out = characters.pickable(me).stream()
                .map(one -> view(one, me))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("characters", out);
        body.put("logged_in", me != null);
        body.put("free_left", characters.freeLeft(me));
        body.put("free_per_day", characters.freePerDay());
        body.put("credit_cost", characters.cost());
        return body;
    }

    @Operation(summary = "캐릭터 만들기", description = """
            사진을 주면 그것을 읽어 외모를 적고, 안 주면 이름·설명만으로 적는다.
            **올린 사진은 그림이 나오면 지운다** — 보관하는 것은 그린 것뿐이다.

            하루 몫이 남아 있으면 공짜, 아니면 크레딧을 받는다.""")
    @PostMapping
    public Map<String, Object> create(@RequestBody CreateRequest form) {
        Long me = CreditGate.currentUser();
        WebtoonCharacter made = characters.create(
                me, form.name(), form.description(), form.photoData(), form.style());
        return view(made, me);
    }

    @Operation(summary = "이름·설명 고치기")
    @PatchMapping("/{publicId}")
    public Map<String, Object> rename(@PathVariable String publicId,
                                      @RequestBody CreateRequest form) {
        Long me = CreditGate.currentUser();
        return view(characters.rename(publicId, me, form.name(), form.description()), me);
    }

    @Operation(summary = "지우기", description = """
            그림은 S3 에 그대로 둔다 — 이 캐릭터로 이미 만든 웹툰이 그것을 보고 있다.""")
    @DeleteMapping("/{publicId}")
    public Map<String, Object> remove(@PathVariable String publicId) {
        characters.remove(publicId, CreditGate.currentUser());
        return Map.of("ok", true);
    }

    private Map<String, Object> view(WebtoonCharacter one, Long me) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", one.getPublicId());
        m.put("name", one.getName());
        m.put("description", one.getDescription() == null ? "" : one.getDescription());
        m.put("art_url", characters.artUrl(one));
        m.put("source", one.getSource().name().toLowerCase());
        m.put("builtin", one.isBuiltin());
        m.put("mine", one.ownedBy(me));
        m.put("created_at", one.getCreatedAt().toString());
        return m;
    }

    /** 실패도 파이썬이 주던 모양 그대로 — 화면이 사유를 읽는다. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, String>> asHarnessSpoke(BusinessException e) {
        return ResponseEntity.status(e.getErrorCode().getStatus())
                .body(Map.of("error", e.getMessage()));
    }

    public record CreateRequest(String name, String description,
                                @com.fasterxml.jackson.annotation.JsonProperty("photo_data")
                                @com.fasterxml.jackson.annotation.JsonAlias("photoData")
                                String photoData,
                                String style) {
    }
}
