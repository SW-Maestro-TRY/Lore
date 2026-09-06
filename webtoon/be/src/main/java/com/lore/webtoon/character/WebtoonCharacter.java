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

/**
 * 사람이 가지고 노는 캐릭터.
 *
 * <h2>왜 작품이 아니라 캐릭터가 따로 있나</h2>
 *
 * 지금까지 캐릭터는 웹툰 한 편을 만들 때 <b>스쳐 지나가는 입력</b>이었다 —
 * 사진과 설명을 적어 넣으면 그 편에 쓰이고 끝이었다. 그래서 다음 편을 만들
 * 때 같은 캐릭터를 처음부터 다시 적어야 했고, "내 캐릭터" 라고 부를 것이
 * 아무 데도 없었다.
 *
 * 이 표가 생기면 순서가 뒤집힌다 — <b>캐릭터를 먼저 만들어 두고, 그 캐릭터로
 * 웹툰을 만든다.</b> 이 제품이 하려는 것("자캐를 가지고 논다")에 더 가깝다.
 *
 * <h2>보관하는 것은 그린 것이지 올린 것이 아니다</h2>
 *
 * {@code artKey} 는 <b>AI 가 그린 캐릭터 그림</b>이다. 사람이 올린 원본 사진은
 * 여기 안 들어온다 — 그건 이 그림을 만드는 데만 쓰고 곧바로 지운다
 * (JobRunner 의 dropPhotos, newharness_pipeline 의 _drop_photos).
 * 사람 얼굴이 서버에 남지 않는다.
 *
 * <h2>기본 제공과 내 것</h2>
 *
 * {@code ownerId} 가 비어 있으면 <b>기본 제공</b>이다 — 처음 온 사람이 만들
 * 것이 없어도 바로 골라 쓸 수 있게 우리가 올려 둔 것. 그 밖에는 만든 사람
 * 것이고, 만든 사람만 본다.
 *
 * 사람끼리 캐릭터를 주고받는 것은 여기 없다(#259) — 주인이 무엇을 허락한
 * 것인지부터 정해야 하는 일이라 따로 뺐다.
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

    private WebtoonCharacter(String publicId, Long ownerId, String name, String description,
                             CharacterSource source, CharacterStatus status, Instant at) {
        this.publicId = publicId;
        this.status = status;
        this.ownerId = ownerId;
        this.name = name;
        this.description = description;
        this.source = source;
        this.createdAt = at;
        this.updatedAt = at;
    }

    /** 만들어 놓고 그리기 시작한다. 그림은 아직 없다. */
    public static WebtoonCharacter drawing(String publicId, Long ownerId, String name,
                                           String description, Instant at) {
        return new WebtoonCharacter(publicId, ownerId, name, description,
                CharacterSource.PROMPT, CharacterStatus.DRAWING, at);
    }

    /** 기본 제공. 주인이 없다 — 누구나 골라 쓴다. */
    public static WebtoonCharacter builtin(String publicId, String name, String description,
                                           Instant at) {
        return new WebtoonCharacter(publicId, null, name, description,
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

    public boolean isBuiltin() {
        return ownerId == null;
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
