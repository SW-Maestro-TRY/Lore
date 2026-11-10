package com.lore.webtoon.invite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 누가 누구를 데려왔나. 피초대자 한 명에 한 줄.
 *
 * 보상은 크레딧 장부({@code CreditService.grantOnce}, refId {@code invite-signup-<피초대자>} ·
 * {@code invite-complete-<피초대자>})가 한 번만 들어가게 막고, 이 표의 {@code *_rewarded} 는
 * 「상한에 걸려 안 준 줄」을 가려 두는 용도다.
 */
@Entity
@Table(name = "webtoon_invite")
public class Invite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "inviter_id", nullable = false)
    private Long inviterId;

    @Column(name = "invitee_id", nullable = false)
    private Long inviteeId;

    @Column(name = "code", nullable = false, length = 16)
    private String code;

    /** 피초대자가 가입할 때 들고 있던 브라우저 uid. 초대자 기기와 같은지 볼 때 쓴다. */
    @Column(name = "browser_uid", length = 64)
    private String browserUid;

    @Column(name = "signed_up_at", nullable = false)
    private Instant signedUpAt;

    @Column(name = "signup_rewarded", nullable = false)
    private boolean signupRewarded;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "complete_rewarded", nullable = false)
    private boolean completeRewarded;

    protected Invite() {
    }

    Invite(Long inviterId, Long inviteeId, String code, String browserUid, Instant signedUpAt) {
        this.inviterId = inviterId;
        this.inviteeId = inviteeId;
        this.code = code;
        this.browserUid = browserUid;
        this.signedUpAt = signedUpAt;
    }

    public Long getId() {
        return id;
    }

    public Long getInviterId() {
        return inviterId;
    }

    public Long getInviteeId() {
        return inviteeId;
    }

    public String getCode() {
        return code;
    }

    public Instant getSignedUpAt() {
        return signedUpAt;
    }

    public boolean isSignupRewarded() {
        return signupRewarded;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public boolean isCompleteRewarded() {
        return completeRewarded;
    }

    void signupRewarded() {
        this.signupRewarded = true;
    }

    void completed(Instant at, boolean rewarded) {
        this.completedAt = at;
        this.completeRewarded = rewarded;
    }
}
