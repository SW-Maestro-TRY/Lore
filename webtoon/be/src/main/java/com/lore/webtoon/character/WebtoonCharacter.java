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
import java.util.Collection;

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
