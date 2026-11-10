package com.lore.webtoon.work;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.Admins;
import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.credit.CreditGate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 둘러보기 「인기순」 자리를 관리자가 정한다. <b>관리자 계정({@code role = ADMIN})만</b> 쓴다.
 * 인기순이 둘러보기의 기본 정렬이라, 처음 온 사람이 무엇부터 보는지를 여기서 고른다.
 * 작을수록 앞이고, 비우면(null) 정해진 작품들 뒤로 가서 조회순으로 선다.
 */
@Tag(name = "Webtoon", description = "웹툰 스튜디오")
@RestController
@RequestMapping(WebtoonApi.V1 + "/admin/browse-rank")
public class BrowseRankAdminController {

    private static final Logger log = LoggerFactory.getLogger(BrowseRankAdminController.class);

    private final Admins admins;
    private final WebtoonWorkRepository works;

    public BrowseRankAdminController(Admins admins, WebtoonWorkRepository works) {
        this.admins = admins;
        this.works = works;
    }

    public record RankRequest(Integer rank) {
    }

    @Operation(summary = "인기순 자리 정하기", description = "rank 가 작을수록 앞. null 이면 자리를 비운다(조회순으로).")
    @PutMapping("/{runId}")
    @Transactional
    public Map<String, Object> set(@PathVariable String runId, @RequestBody RankRequest body) {
        Long me = CreditGate.currentUser();
        if (me == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "로그인이 필요합니다");
        }
        if (!admins.isAdmin(me)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "관리자만 쓸 수 있습니다");
        }
        Integer rank = body == null ? null : body.rank();
        if (rank != null && (rank < 1 || rank > 9999)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "순서는 1~9999 사이 숫자입니다");
        }
        WebtoonWork work = works.findFirstByRunId(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "작품이 없습니다"));
        work.setBrowseRank(rank);
        works.save(work);
        log.info("인기순 자리를 바꿨습니다 (admin={}, run={}, rank={})", me, runId, rank);
        Map<String, Object> out = new HashMap<>();
        out.put("run_id", runId);
        out.put("rank", rank);
        return out;
    }
}
