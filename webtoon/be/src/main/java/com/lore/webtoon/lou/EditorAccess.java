package com.lore.webtoon.lou;

import com.lore.common.exception.*;
import com.lore.webtoon.Admins;
import com.lore.webtoon.work.WorkLedger;
import com.lore.webtoon.credit.CreditGate;

public final class EditorAccess {
    private EditorAccess() {}
    public static Long mustOwn(String runId, Admins admins, WorkLedger ledger) {
        Long userId = CreditGate.currentUser();
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED,
                    "편집실은 로그인해야 쓸 수 있어요. 로그인하면 이 브라우저로 만든 작품도 같이 따라옵니다.");
        }
        /* 관리자 계정은 모든 작품의 편집실을 쓴다(운영 확인용). 다시 그리기 값은 관리자 계정의 크레딧에서 나간다. */
        if (!admins.isAdmin(userId) && !ledger.mayChange(runId, userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "내가 만든 작품만 고칠 수 있습니다");
        }
        return userId;
    }
}
