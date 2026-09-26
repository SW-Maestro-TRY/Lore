package com.lore.webtoon.character;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * 사람이 가지고 노는 캐릭터.
 */
@Entity
@Table(
        name = "webtoon_character",
        indexes = {
                @Index(name = "idx_webtoon_character_owner", columnList = "owner_id, id"),
                @Index(name = "idx_webtoon_character_public_id", columnList = "public_id"),
        })
public class WebtoonCharacter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 화면이 부르는 번호. 숫자 id 를 그대로 내보내면 남의 번호를 세어 볼 수 있다. */
    @Column(name = "public_id", nullable = false, unique = true, length = 40)
    private String publicId;

    /** 만든 사람. <b>비어 있으면 기본 제공</b>이다. */
    @Column(name = "owner_id")
    private Long ownerId;

    /**
     * 로그인 안 하고 만들었으면 그 브라우저를 가리키는 값({@code lore_uid}).
     * 로그인하고 만든 것과 우리가 심은 것에는 없다.
     *
     * <h2>왜 필요한가</h2>
     *
     * 캐릭터는 <b>로그인 없이도 만들 수 있다.</b> 그런데 주인 칸만 있으면
     * 게스트가 만든 것은 주인이 빈 채로 남고, 그건 우리가 심은 기본 제공과
     * 구별이 안 된다 — 실제로 남의 목록에 뜨고, 거두는 자리에 쓸려 지워졌다.
     *
     * <h2>⚠️ 이것으로 소유를 증명하지는 못한다</h2>
     *
     * uid 는 브라우저가 만들어 들고 다니는 값이라 <b>마음먹으면 남의 것을
     * 적어 보낼 수 있다.</b> 작품 쪽이 같은 한계를 안고 같은 방식을 쓰고
     * 있다 — 왜 그래도 이 길인지는 {@code BrowserLink} 머리 주석에 있다.
     * 요약하면, 로그인을 필수로 만들지 않는 한 게스트를 가리킬 다른 값이
     * 없고, 로그인 전에 만든 것이 로그인 뒤에도 따라와야 하기 때문이다.
     */
    @Column(name = "browser_uid", length = 64)
    private String browserUid;

    @Column(nullable = false, length = 60)
    private String name;

    /** 성격·말투·관계 등. 이 글이 곧 그림의 근거가 된다. */
    @Column(columnDefinition = "text")
    private String description;

    /** AI 가 그린 캐릭터 그림의 S3 키. 아직 안 그렸으면 비어 있다. */
    @Column(name = "art_key", length = 200)
    private String artKey;

    /** 어떻게 만들었나 — 사진에서 그렸나, 글만으로 그렸나. 나중에 무엇이 잘 되는지 보려고 남긴다. */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private CharacterSource source;

    /**
     * 그리는 중인가, 다 됐나.
     *
     * 그리는 데 1분쯤 걸려서 <b>요청을 붙들고 기다릴 수가 없다</b> — 배포의
     * 어느 자리도 그만큼 긴 연결을 안 기다려 준다. 만들기는 곧바로 돌려주고
     * 화면이 이 값을 보고 「그리는 중」을 띄운다.
     */
    @Column(nullable = false, length = 20,
            columnDefinition = "varchar(20) not null default 'READY'")
    @Enumerated(EnumType.STRING)
    private CharacterStatus status = CharacterStatus.READY;

    /** 못 그렸을 때 사람이 읽을 한 줄. */
    @Column(length = 300)
    private String error;

    /*
     * 「캐릭터 만들어보기」로 만든 것만 아래 칸이 찬다 — 그 세계관 웹툰의 한 컷
     * (art_key 가 그 그림이다)과 카드 글. 직접 만들기·기본 제공은 전부 비어 있다.
     *
     * 카드 글을 따로 표로 빼지 않는다. 캐릭터 하나에 카드 하나이고, 「이 캐릭터로
     * 1화 보기」·「내 캐릭터에 저장」이 전부 이 줄 하나를 가리키면 되기 때문이다.
     */

    /** 세계관 — story-harness 프리셋 키. 사람이 직접 썼으면 비어 있다. */
    @Column(length = 80)
    private String world;

    /** 딱지에 쓸 세계관 이름(로판·헌터처럼 짧게). */
    @Column(name = "world_label", length = 20)
    private String worldLabel;

    /** 반전 문장에 들어가는 장르 한 단어. */
    @Column(length = 40)
    private String genre;

    /** 그 세계관에서 맡은 자리. */
    @Column(name = "role_name", length = 40)
    private String roleName;

    /** 자리의 무게 — 하네스가 굴린 값 그대로(중심 · 곁 · 스쳐감 · 뜬금). 화면이 주연·조연 같은 딱지로 옮긴다. */
    @Column(name = "role_tier", length = 20)
    private String roleTier;

    /** 종까지 바뀐 뽑기였나(#331). 화면이 이걸 보고 "당황하셨나요?" 설문을 띄운다. */
    @Column(nullable = false)
    private boolean lucky;
    /* 넣은 것과 나온 것을 나란히(#329). 이름은 비어 있으면 모델이 지어 name 을 덮으므로
       사람이 적은 것을 따로 둔다. 세계관은 world 에 프리셋 키만 남아 직접 적은 한 줄이
       사라진다. 종은 카드가 읽어 낸 값 — 사진·설명이 무엇으로 읽혔는지 보여 주는 근거다. */
    @Column(name = "asked_name", length = 60)
    private String askedName;

    @Column(name = "asked_world", length = 120)
    private String askedWorld;

    @Column(length = 40)
    private String species;

    /** 반전 한 줄 — 카드의 제목이다. */
    @Column(length = 300)
    private String twist;

    /** 옛 카드의 대사 한 줄. 새 카드는 dialogue 를 쓰고 여기엔 그 줄들을 「누구: 말」로 이어 둔다. */
    @Column(length = 300)
    private String quote;

    /** 한 컷 위에 얹을 말풍선 두세 줄. 줄마다 「누구 TAB 내것(1/0) TAB 쪽 TAB 말」, 줄바꿈으로 나눈다.
        그림에는 글자가 없고 화면이 얹는다 — 이 그림이 1화의 참고 그림으로도 쓰여서 글자를 구우면 샌다. */
    @Column(columnDefinition = "text")
    private String dialogue;

    /** 운명 두세 줄. 줄바꿈으로 잇는다. */
    @Column(columnDefinition = "text")
    private String fate;

    /** 그린 그림체(prompt/style 의 이름). 1화를 같은 그림체로 그리려고 남긴다. */
    @Column(length = 40)
    private String style;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebtoonCharacter() {
    }

    private WebtoonCharacter(String publicId, Long ownerId, String browserUid,
                             String name, String description,
                             CharacterSource source, CharacterStatus status, Instant at) {
        this.publicId = publicId;
        this.status = status;
        this.ownerId = ownerId;
        this.browserUid = blankToNull(browserUid);
        this.name = name;
        this.description = description;
        this.source = source;
        this.createdAt = at;
        this.updatedAt = at;
    }

    /** 만들어 놓고 그리기 시작한다. 그림은 아직 없다. */
    public static WebtoonCharacter drawing(String publicId, Long ownerId, String browserUid,
                                           String name, String description, Instant at) {
        return new WebtoonCharacter(publicId, ownerId, browserUid, name, description,
                CharacterSource.PROMPT, CharacterStatus.DRAWING, at);
    }

    /** 기본 제공. 주인이 없다 — 누구나 골라 쓴다. */
    public static WebtoonCharacter builtin(String publicId, String name, String description,
                                           Instant at) {
        return new WebtoonCharacter(publicId, null, null, name, description,
                CharacterSource.BUILTIN, CharacterStatus.READY, at);
    }

    /** 다 그렸다. */
    public void drewArt(String key, CharacterSource source, Instant at) {
        this.artKey = key;
        this.source = source;
        this.status = CharacterStatus.READY;
        this.error = null;
        this.updatedAt = at;
    }

    /** 한 컷과 카드 글이 다 됐다 — 「캐릭터 만들어보기」의 끝. */
    public void drewPanel(String key, CharacterSource source, Card card, Instant at) {
        drewArt(key, source, at);
        this.world = cut(card.world(), 80);
        this.worldLabel = cut(card.worldLabel(), 20);
        this.genre = cut(card.genre(), 40);
        this.roleName = cut(card.role(), 40);
        this.roleTier = cut(card.roleTier(), 20);
        this.lucky = card.lucky();
        this.species = cut(card.species(), 40);
        this.twist = cut(card.twist(), 300);
        this.quote = cut(card.quote(), 300);
        this.dialogue = card.dialogue() == null || card.dialogue().isEmpty() ? null
                : card.dialogue().stream().map(DialogueLine::pack).collect(java.util.stream.Collectors.joining("\n"));
        this.fate = card.fate() == null || card.fate().isEmpty()
                ? null : String.join("\n", card.fate());
        this.style = cut(card.style(), 40);
    }

    /** 카드 글. 한 컷으로 만든 캐릭터만 갖는다. */
    public record Card(String world, String worldLabel, String genre, String role, String roleTier,
                       String twist, String quote, List<DialogueLine> dialogue,
                       List<String> fate, String style, boolean lucky, String species) {
    }

    /** 말풍선 한 줄. side 는 말하는 이가 화면에서 서 있는 쪽(left · right · center). */
    public record DialogueLine(String who, boolean mine, String side, String text) {
        String pack() {
            return String.join("\t", clean(who), mine ? "1" : "0", clean(side), clean(text));
        }

        static DialogueLine unpack(String line) {
            String[] p = line.split("\t", -1);
            if (p.length < 4 || p[3].isBlank()) {
                return null;
            }
            return new DialogueLine(p[0], "1".equals(p[1]), p[2], p[3]);
        }

        private static String clean(String s) {
            return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').trim();
        }
    }

    /** 카드가 있나 — 「캐릭터 만들어보기」로 만든 것인가. */
    public boolean hasCard() {
        return twist != null && !twist.isBlank();
    }

    /** 운명 줄들. 없으면 빈 목록. */
    public List<DialogueLine> dialogueLines() {
        if (dialogue == null || dialogue.isBlank()) {
            return List.of();
        }
        return Arrays.stream(dialogue.split("\n")).map(DialogueLine::unpack)
                .filter(java.util.Objects::nonNull).toList();
    }

    public List<String> fateLines() {
        if (fate == null || fate.isBlank()) {
            return List.of();
        }
        return Arrays.stream(fate.split("\n")).map(String::trim)
                .filter(l -> !l.isEmpty()).toList();
    }

    private static String cut(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 못 그렸다. 사유는 사람이 읽을 한 줄이어야 한다. */
    public void failed(String why, Instant at) {
        this.status = CharacterStatus.ERROR;
        this.error = why == null ? null : why.substring(0, Math.min(why.length(), 300));
        this.updatedAt = at;
    }

    public void rename(String name, String description, Instant at) {
        if (name != null && !name.isBlank()) {
            this.name = name;
        }
        this.description = description;
        this.updatedAt = at;
    }

    /** 이 사람이 만든 것인가. 기본 제공은 아무도 주인이 아니다. */
    public boolean ownedBy(Long userId) {
        return userId != null && userId.equals(ownerId);
    }

    /**
     * 이 사람 것인가 — 계정으로든 브라우저로든.
     *
     * 로그인 안 하고 만든 것은 계정이 없으므로 브라우저 uid 로만 가릴 수
     * 있다. 로그인한 사람에게도 uid 를 같이 본다 — 로그인 전에 만든 것이
     * 로그인 뒤에 남의 것이 되면 안 된다.
     */
    public boolean madeBy(Long userId, Collection<String> uids) {
        if (ownedBy(userId)) {
            return true;
        }
        return browserUid != null && uids != null && uids.contains(browserUid);
    }

    /**
     * 우리가 심은 것인가.
     *
     * <b>주인이 비었나로 보면 안 된다.</b> 로그인 안 하고 만든 것도 주인이
     * 비어 있어서, 그렇게 보면 남이 만든 캐릭터가 「기본 제공」이 되어 모두의
     * 목록에 뜨고 거두는 자리에 쓸려 지워진다 — 실제로 그랬다.
     */
    public boolean isBuiltin() {
        return source == CharacterSource.BUILTIN;
    }

    public String getBrowserUid() {
        return browserUid;
    }

    public String getWorld() {
        return world;
    }

    public String getWorldLabel() {
        return worldLabel;
    }

    public String getGenre() {
        return genre;
    }

    public String getRoleName() {
        return roleName;
    }

    public String getRoleTier() {
        return roleTier;
    }

    public boolean isLucky() {
        return lucky;
    }

    /** 사람이 넣은 이름·세계관을 그대로 적어 둔다. 만들 때 한 번. */
    public void asked(String name, String world) {
        this.askedName = name == null || name.isBlank() ? null : cut(name.trim(), 60);
        this.askedWorld = world == null || world.isBlank() ? null : cut(world.trim(), 120);
    }

    public String getAskedName() {
        return askedName;
    }

    public String getAskedWorld() {
        return askedWorld;
    }

    public String getSpecies() {
        return species;
    }

    public String getTwist() {
        return twist;
    }

    public String getQuote() {
        return quote;
    }

    public String getStyle() {
        return style;
    }

    /** 빈 문자열은 없는 것과 같다 — 그 값이 들어오면 아무나 자기 것이 된다. */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getArtKey() {
        return artKey;
    }

    public CharacterSource getSource() {
        return source;
    }

    public CharacterStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
