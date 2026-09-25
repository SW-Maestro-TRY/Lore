package com.lore.webtoon.character;

import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.WebtoonApi;
import com.lore.common.exception.BusinessException;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
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

    static final String PREFIX = WebtoonApi.V1 + "/characters";

    /**
     * 이 브라우저를 가리키는 값이 실려 오는 머리.
     *
     * <b>모든 메서드가 같은 자리에서 읽으려고 머리로 받는다.</b> 목록(GET)과
     * 지우기(DELETE)에는 본문이 없어서 본문에 넣으면 만들기만 달라진다.
     * 화면 쪽도 {@code charApi.ts} 의 {@code call()} 한 곳에서 붙인다.
     */
    static final String UID_HEADER = "X-Lore-Uid";

    private final CharacterService characters;
    private final CharacterOwner who;

    public CharacterController(CharacterService characters, CharacterOwner who) {
        this.characters = characters;
        this.who = who;
    }

    @Operation(summary = "고를 수 있는 캐릭터", description = """
            내가 만든 것과 기본 제공. 로그인 안 했으면 기본 제공만 나온다.
            남이 만든 것은 여기 안 섞인다(#259).""")
    @GetMapping
    public Map<String, Object> list(
            @RequestHeader(value = UID_HEADER, required = false) String uid) {
        Long me = CreditGate.currentUser();
        List<String> uids = who.uidsOf(me, uid);
        List<Map<String, Object>> out = characters.pickable(me, uids).stream()
                .map(one -> view(one, me, uids))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("characters", out);
        body.put("logged_in", me != null);
        body.put("free_left", characters.freeLeft(me, uids));
        body.put("free_per_day", characters.freePerDay());
        body.put("credit_cost", characters.cost());
        return body;
    }

    @Operation(summary = "캐릭터 만들기", description = """
            사진을 주면 그것을 읽어 외모를 적고, 안 주면 이름·설명만으로 적는다.
            사진은 여러 장(최대 4장, 같은 사람의 다른 각도·표정) 줄 수 있다.
            **올린 사진은 그림이 나오면 지운다** — 보관하는 것은 그린 것뿐이다.

            하루 몫이 남아 있으면 공짜, 아니면 크레딧을 받는다.""")
    @PostMapping
    public Map<String, Object> create(
            @RequestBody CreateRequest form,
            @RequestHeader(value = UID_HEADER, required = false) String uid) {
        Long me = CreditGate.currentUser();
        WebtoonCharacter made = characters.create(
                me, uid, form.name(), form.description(), form.photosData(), form.style());
        return view(made, me, who.uidsOf(me, uid));
    }

    @Operation(summary = "캐릭터 만들어보기", description = """
            뭐든 넣으면 그대로 그 세계관 웹툰의 한 컷이 된다. 사진·설명·이름·세계관 전부
            **선택** — 아무것도 없이 보내면 「랜덤으로 만들어보기」다.
            world 는 GET /worlds 의 key 이거나 사람이 직접 쓴 한 줄.

            바로 돌려주고 뒤에서 그린다(status=drawing). GET /{id} 로 다시 읽으면
            그림(art_url)과 카드(twist · quote · fate)가 채워진다.
            값과 하루 몫은 「캐릭터 만들기」와 같다.""")
    @PostMapping("/try")
    public Map<String, Object> tryOut(
            @RequestBody(required = false) TryRequest form,
            @RequestHeader(value = UID_HEADER, required = false) String uid) {
        Long me = CreditGate.currentUser();
        TryRequest f = form == null ? new TryRequest(null, null, null, null) : form;
        WebtoonCharacter made = characters.tryOut(
                me, uid, f.name(), f.description(), f.photosData(), f.world());
        return view(made, me, who.uidsOf(me, uid));
    }

    @Operation(summary = "고를 수 있는 세계관", description = """
            「캐릭터 만들어보기」의 세계관 목록. 하네스 프리셋 그대로다.""")
    @GetMapping("/worlds")
    public Map<String, Object> worlds() {
        return Map.of("worlds", characters.worlds());
    }

    @Operation(summary = "랜덤 재료", description = """
            「랜덤으로 만들어보기」가 입력 칸을 채울 값 한 벌 — 이름 · 설명 · 세계관.
            AI 를 안 부르고 조합에서 뽑는다. 사람이 보고 고친 뒤 만든다.""")
    @GetMapping("/random")
    public Map<String, String> random() {
        return characters.randomSeed();
    }

    @Operation(summary = "공유된 카드", description = """
            「캐릭터 만들어보기」 카드의 공유 링크가 여는 자리. 로그인·주인 확인 없음.
            내 것인지(mine)는 안 준다 — 보는 사람이 누구든 같은 카드다.""")
    @GetMapping("/{publicId}/card")
    public Map<String, Object> card(@PathVariable String publicId) {
        WebtoonCharacter one = characters.sharedCard(publicId);
        Map<String, Object> m = view(one, null, List.of());
        m.remove("mine");
        m.remove("error");
        return m;
    }

    @Operation(summary = "캐릭터 하나", description = """
            그리는 중인 것을 다시 읽는 자리. 내 것과 기본 제공만 보인다.""")
    @GetMapping("/{publicId}")
    public Map<String, Object> one(
            @PathVariable String publicId,
            @RequestHeader(value = UID_HEADER, required = false) String uid) {
        Long me = CreditGate.currentUser();
        List<String> uids = who.uidsOf(me, uid);
        return view(characters.byPublicId(publicId, me, uids), me, uids);
    }

    @Operation(summary = "이름·설명 고치기")
    @PatchMapping("/{publicId}")
    public Map<String, Object> rename(
            @PathVariable String publicId,
            @RequestBody CreateRequest form,
            @RequestHeader(value = UID_HEADER, required = false) String uid) {
        Long me = CreditGate.currentUser();
        List<String> uids = who.uidsOf(me, uid);
        return view(characters.rename(publicId, me, uids, form.name(), form.description()),
                    me, uids);
    }

    @Operation(summary = "지우기", description = """
            그림은 S3 에 그대로 둔다 — 이 캐릭터로 이미 만든 웹툰이 그것을 보고 있다.""")
    @DeleteMapping("/{publicId}")
    public Map<String, Object> remove(
            @PathVariable String publicId,
            @RequestHeader(value = UID_HEADER, required = false) String uid) {
        Long me = CreditGate.currentUser();
        characters.remove(publicId, me, who.uidsOf(me, uid));
        return Map.of("ok", true);
    }

    private Map<String, Object> view(WebtoonCharacter one, Long me, List<String> uids) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", one.getPublicId());
        m.put("name", one.getName());
        m.put("description", one.getDescription() == null ? "" : one.getDescription());
        m.put("art_url", characters.artUrl(one));
        m.put("source", one.getSource().name().toLowerCase());
        m.put("status", one.getStatus().name().toLowerCase());
        m.put("error", one.getError());
        m.put("builtin", one.isBuiltin());
        // 로그인 안 하고 만든 것도 「내 것」이다 — 만든 사람에게는 계정이
        // 있고 없고가 그 캐릭터의 주인을 바꾸지 않는다.
        m.put("mine", one.madeBy(me, uids));
        m.put("created_at", one.getCreatedAt().toString());
        // 한 컷으로 만든 것만 카드가 있다. 없으면 칸 자체를 안 보낸다 — 화면이
        // "card 가 있나" 로 두 종류를 가른다.
        if (one.hasCard()) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("world", one.getWorld() == null ? "" : one.getWorld());
            card.put("world_label", one.getWorldLabel() == null ? "" : one.getWorldLabel());
            card.put("genre", one.getGenre() == null ? "" : one.getGenre());
            card.put("role", one.getRoleName() == null ? "" : one.getRoleName());
            card.put("role_tier", one.getRoleTier() == null ? "" : one.getRoleTier());
            card.put("lucky", one.isLucky());
            card.put("twist", one.getTwist());
            card.put("quote", one.getQuote() == null ? "" : one.getQuote());
            List<Map<String, Object>> dialogue = new ArrayList<>();
            for (WebtoonCharacter.DialogueLine line : one.dialogueLines()) {
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("who", line.who());
                l.put("mine", line.mine());
                l.put("side", line.side());
                l.put("text", line.text());
                dialogue.add(l);
            }
            card.put("dialogue", dialogue);
            card.put("fate", one.fateLines());
            card.put("style", one.getStyle() == null ? "" : one.getStyle());
            m.put("card", card);
        }
        return m;
    }

    /** 실패도 파이썬이 주던 모양 그대로 — 화면이 사유를 읽는다. */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, String>> asHarnessSpoke(BusinessException e) {
        return ResponseEntity.status(e.getErrorCode().getStatus())
                .body(Map.of("error", e.getMessage()));
    }

    /** 「캐릭터 만들어보기」 입력. 전부 비어도 된다. */
    public record TryRequest(String name, String description,
                             @com.fasterxml.jackson.annotation.JsonProperty("photos_data")
                             @com.fasterxml.jackson.annotation.JsonAlias("photosData")
                             List<String> photosData,
                             String world) {
    }

    public record CreateRequest(String name, String description,
                                @com.fasterxml.jackson.annotation.JsonProperty("photos_data")
                                @com.fasterxml.jackson.annotation.JsonAlias("photosData")
                                List<String> photosData,
                                String style) {
    }
}
