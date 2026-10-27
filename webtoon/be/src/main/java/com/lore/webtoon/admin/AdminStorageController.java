package com.lore.webtoon.admin;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.Admins;
import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.art.ArtOrphanScan;
import com.lore.webtoon.credit.CreditGate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 그림 창고 점검 — <b>관리자만</b>. 창고에는 있는데 DB 어디에도 없는 웹툰 그림을 세고,
 * 원하면 지울 대기열에 넣는다. 실제 삭제는 7일 뒤 정리 작업이 다시 확인하고 한다.
 */
@Tag(name = "Webtoon", description = "웹툰 스튜디오")
@RestController
@RequestMapping(WebtoonApi.V1 + "/admin/storage")
public class AdminStorageController {

    private final Admins admins;
    private final ArtOrphanScan scan;

    public AdminStorageController(Admins admins, ArtOrphanScan scan) {
        this.admins = admins;
        this.scan = scan;
    }

    private void requireAdmin() {
        Long me = CreditGate.currentUser();
        if (me == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "로그인이 필요합니다");
        }
        if (!admins.isAdmin(me)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "관리자만 쓸 수 있습니다");
        }
    }

    private void requireBucket() {
        if (!scan.ready()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "그림 창고(버킷)가 없는 환경입니다");
        }
    }

    @Operation(summary = "주인 없는 그림 세기",
            description = "images/webtoon · private/webtoon 아래 파일 가운데 DB 어디에도 주소가 없고 "
                    + "올라온 지 2일이 지난 것을 센다. 아무것도 바꾸지 않는다.")
    @GetMapping("/orphans")
    public ArtOrphanScan.Report count() {
        requireAdmin();
        requireBucket();
        return scan.run(false, Instant.now());
    }

    @Operation(summary = "주인 없는 그림을 지울 대기열에 넣기",
            description = "세기와 같은 기준으로 찾은 것을 대기열에 적는다. 바로 지우지 않는다 — "
                    + "7일 뒤 정리 작업(매일 05:30 KST)이 웹툰 표를 다시 보고 아무 데서도 안 쓰면 지운다.")
    @PostMapping("/orphans")
    public ArtOrphanScan.Report queue() {
        requireAdmin();
        requireBucket();
        return scan.run(true, Instant.now());
    }
}
