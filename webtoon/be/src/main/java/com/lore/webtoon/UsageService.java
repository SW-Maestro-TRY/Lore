package com.lore.webtoon;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** 생성 비용을 받아 쌓고, 오늘 얼마 썼는지 답한다. */
@Service
public class UsageService {

    private static final Logger log = LoggerFactory.getLogger(UsageService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private final UsageRepository usage;
    private final SpendGuard guard;
    private final String token;

    public UsageService(UsageRepository usage, SpendGuard guard,
                        @Value("${lore.webtoon.internal-token:}") String token) {
        this.usage = usage;
        this.guard = guard;
        this.token = token == null ? "" : token.trim();
        // 한 마디는 **아스키만** 된다. HTTP 헤더 값에 한글을 실으면 스프링
        // 시큐리티의 방화벽이 요청 자체를 거절해서, 비용이 조용히 안 쌓이고
        // 500 만 남는다 — 그런데 그때 서버 로그는 "처리되지 않은 예외" 라고만
        // 말해서 한 마디 때문인 줄 알기 어렵다(실제로 여기서 한 번 헤맸다).
        // 뜰 때 미리 말해 준다.
        if (!this.token.isEmpty() && !this.token.chars().allMatch(c -> c > 0x20 && c < 0x7F)) {
            log.error("LORE_WEBTOON_INTERNAL_TOKEN 에 아스키가 아닌 글자나 공백이 있습니다. "
                    + "HTTP 헤더에 실을 수 없어 비용 적재가 전부 실패합니다 — "
                    + "영문·숫자·기호로 바꿔 주세요.");
        }
        if (this.token.isEmpty()) {
            // 부팅을 막지는 않는다 — 로컬에서는 이것 없이도 서버가 떠야 한다.
            // 대신 크게 남긴다: 값이 비면 비용 적재가 통째로 막히는데, 그 사실이
            // 실제 호출 때까지 안 드러나는 것이 제일 나쁘다(S3 버킷과 같은 자리).
            log.warn("LORE_WEBTOON_INTERNAL_TOKEN 이 비어 있어 비용 적재가 막힙니다. "
                    + "서버라면 /etc/lore/lore.env 를 확인하세요.");
        }
    }

    /**
     * 하네스가 맞는지.
     *
     * 길이가 같은지부터 보고 <b>상수 시간</b>으로 비교한다 — 문자열을 그냥
     * 비교하면 앞자리가 몇 글자 맞는지가 걸리는 시간으로 새어 나간다.
     */
    void checkToken(String given) {
        if (token.isEmpty()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "비용 적재가 설정되지 않았습니다");
        }
        byte[] a = token.getBytes(StandardCharsets.UTF_8);
        byte[] b = (given == null ? "" : given).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(a, b)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "권한이 없습니다");
        }
    }

    /**
     * 호출 기록을 쌓는다. 이미 있는 것(run_id + seq)은 건너뛴다.
     *
     * @param calls meta.json 의 calls 순서 그대로. 그 순서가 곧 seq 다
     * @return 이번에 새로 남은 줄 수
     */
    @Transactional
    public int ingest(String runId, List<Call> calls) {
        if (calls == null || calls.isEmpty()) {
            return 0;
        }
        List<UsageRecord> fresh = new ArrayList<>();
        for (int seq = 0; seq < calls.size(); seq++) {
            Call c = calls.get(seq);
            if (c == null || usage.existsByRunIdAndSeq(runId, seq)) {
                continue;
            }
            fresh.add(UsageRecord.of(
                    runId, seq,
                    blankTo(c.stage(), "(모름)"), blankTo(c.provider(), "(모름)"),
                    blankTo(c.model(), "(모름)"),
                    c.inputTokens(), c.outputTokens(),
                    c.costUsd(), c.costKrw(), c.costBasis(),
                    cut(c.error()),
                    c.calledAt() == null ? Instant.now() : c.calledAt()));
        }
        usage.saveAll(fresh);
        return fresh.size();
    }

    /**
     * 만들려는 사람에게 보여 줄 것 — <b>몫만</b>.
     *
     * 돈은 안 담는다. 한 편에 얼마가 드는지는 우리 원가이고, 화면에 흘리면
     * 그대로 밖으로 나간다(가격은 아직 안 정했고 크레딧 숫자도 목업이다 —
     * 이 저장소 규칙 참고). 만들려는 사람이 알아야 하는 것은 "지금 만들 수
     * 있나, 오늘 몇 편 남았나" 뿐이다.
     */
    @Transactional(readOnly = true)
    public ApiResponse<TodayView> today() {
        SpendGuard.Today t = guard.today();
        return ApiResponse.ok(new TodayView(t.runs(), t.runLimit(), guard.whyBlocked() == null));
    }

    /**
     * 운영하는 사람이 볼 것 — 오늘 · 이번 달 · 무엇에 얼마나.
     *
     * <b>한 마디를 확인하고 준다.</b> 여기 담긴 것은 단계별·모델별 원가라
     * 우리 원가 구조가 그대로 드러난다. 화면에서 부르지 않고 손에서 부른다
     * (haeun/landing/spend_report.py) — 브라우저에 한 마디를 심으면 그것을
     * 심는 순간 더 이상 비밀이 아니다.
     */
    @Transactional(readOnly = true)
    public ApiResponse<SpendView> spend(String givenToken) {
        checkToken(givenToken);
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(ZONE);
        Instant dayFrom = today.atStartOfDay(ZONE).toInstant();
        Instant monthFrom = today.withDayOfMonth(1).atStartOfDay(ZONE).toInstant();

        SpendGuard.Today t = guard.today();
        List<Line> lines = new ArrayList<>();
        for (Object[] row : usage.breakdownBetween(dayFrom, now)) {
            lines.add(new Line((String) row[0], (String) row[1],
                    ((Number) row[2]).longValue(), ((Number) row[3]).longValue()));
        }
        return ApiResponse.ok(new SpendView(
                t.runs(), t.runLimit(), t.krw(), t.krwLimit(),
                usage.runsBetween(monthFrom, now), usage.krwBetween(monthFrom, now),
                guard.whyBlocked() == null, lines));
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    /** 사유가 길면 잘라 둔다 — 기록이지 로그가 아니다. */
    private static String cut(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        return v.length() <= 300 ? v : v.substring(0, 300);
    }

    /**
     * 하네스가 보내는 한 줄. meta.json 의 모양을 그대로 받는다 — 보내는 쪽이
     * 파일을 다시 빚지 않아도 되게.
     */
    public record Call(String stage, String provider, String model,
                       long inputTokens, long outputTokens,
                       double costUsd, long costKrw, String costBasis,
                       String error, Instant calledAt) {
    }

    /**
     * 만들려는 사람에게 보여 줄 것. <b>돈은 없다</b>(위 today() 참고).
     *
     * @param canCreate 지금 새로 만들 수 있는가. 화면이 이걸 보고 미리 알린다
     */
    public record TodayView(long runs, long runLimit, boolean canCreate) {
    }

    /**
     * 운영하는 사람이 볼 것.
     *
     * @param monthRuns 이번 달 만든 편수
     * @param monthKrw  이번 달 나간 돈(원)
     */
    public record SpendView(long runs, long runLimit, long krw, long krwLimit,
                            long monthRuns, long monthKrw,
                            boolean canCreate, List<Line> breakdown) {
    }

    /** @param krw 이 단계·모델에 오늘 나간 돈 */
    public record Line(String stage, String model, long krw, long calls) {
    }
}
