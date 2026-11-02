package com.lore.zzal.admin;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.inquiry.InquiryRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 관리자 — 문의 답장·후기 보기. 잠금은 다른 관리자 화면과 같은 세 겹(스위치·{@link AdminGuard}·noindex)에
 * 하나를 더한다 — <b>봇 토큰으로는 못 부른다</b>({@link AdminCaller#requireHuman()}). 답장은 사용자에게 나가는 글이고,
 * 문의·후기 본문은 사용자가 쓴 글이라 사람이 직접 볼 때만 연다.
 */
@Service
@ConditionalOnProperty(name = "app.zzal.admin.enabled", havingValue = "true")
public class AdminInquiryService {

    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 365;

    private final AdminGuard guard;
    private final AdminCaller caller;
    private final InquiryRepository inquiries;

    public AdminInquiryService(AdminGuard guard, AdminCaller caller, InquiryRepository inquiries) {
        this.guard = guard;
        this.caller = caller;
        this.inquiries = inquiries;
    }

    private void require(Long userId) {
        caller.requireHuman();
        if (userId == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        guard.require(userId);
    }

    static int days(Integer days) {
        if (days == null) return DEFAULT_DAYS;
        return Math.max(1, Math.min(MAX_DAYS, days));
    }

    @Transactional(readOnly = true)
    public List<InquiryRepository.AdminRow> list(Long admin, Integer days, Instant now) {
        require(admin);
        return inquiries.adminList(days(days), now);
    }

    public record Detail(InquiryRepository.AdminRow inquiry, List<InquiryRepository.Row> chain,
                         List<InquiryRepository.Feedback> feedback) { }

    @Transactional(readOnly = true)
    public Detail detail(Long admin, long id) {
        require(admin);
        var head = inquiries.adminFind(id).orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_INQUIRY_NOT_FOUND));
        return new Detail(head, inquiries.chain(id), inquiries.feedbackOfUser(head.userId()));
    }

    /** 답장 한 번. 이미 있으면 409 — 두 사람이 동시에 보내도 하나만 남는다(조건부 UPDATE). */
    @Transactional
    public Detail reply(Long admin, long id, String text, Instant now) {
        require(admin);
        if (inquiries.findById(id).isEmpty()) throw new BusinessException(ErrorCode.ZZAL_INQUIRY_NOT_FOUND);
        if (inquiries.reply(id, text, admin, now) == 0) throw new BusinessException(ErrorCode.ZZAL_INQUIRY_ALREADY_REPLIED);
        return detail(admin, id);
    }

    @Transactional(readOnly = true)
    public List<InquiryRepository.Feedback> feedback(Long admin, Integer days, Instant now) {
        require(admin);
        return inquiries.feedbackSince(days(days), now);
    }
}
