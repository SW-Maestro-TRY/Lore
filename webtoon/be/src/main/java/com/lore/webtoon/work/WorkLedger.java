package com.lore.webtoon.work;

import com.lore.webtoon.credit.BrowserLinkRepository;
import com.lore.webtoon.harness.WebtoonController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * 만들어진 작품을 DB 에 적어 둔다.
 *
 * <h2>왜 여기서 적는가</h2>
 *
 * 하네스는 자기 폴더에만 남기고 계정을 모른다(게스트도 만들 수 있어서 알 수가
 * 없다). 계정을 아는 것은 이 앞에 선 자바뿐이고, 만들기 요청과 진행 조회가
 * 둘 다 여기를 지나간다({@link WebtoonController}). 그래서 지나가는 김에 적는다.
 *
 * <h2>여기서 실패해도 만들기는 안 막는다</h2>
 *
 * 이 표는 <b>누가 만들었나</b> 를 나중에 묻기 위한 것이지, 만드는 데 필요한
 * 것이 아니다. 못 적었다고 만들기를 실패시키면 이미 돈을 치른 사람이 그림도
 * 못 받는다. 못 적은 것은 크게 남기고 넘어간다 — 예전 길(하네스 파일을 이어
 * 붙이는 것)이 아직 살아 있어서 목록이 통째로 비지는 않는다.
 */
@Service
public class WorkLedger {

    private static final Logger log = LoggerFactory.getLogger(WorkLedger.class);

    private final WebtoonWorkRepository works;
    private final BrowserLinkRepository links;
    /* 스프링이 만들어 주는 빈이 없다(앞서 주입받게 썼다가 서버가 안 떴다). */
    private final ObjectMapper mapper = new ObjectMapper();

    public WorkLedger(WebtoonWorkRepository works, BrowserLinkRepository links) {
        this.works = works;
        this.links = links;
    }

    /**
     * 만들기가 시작됐다. 아직 작품 번호는 없다.
     *
     * @param answer 하네스가 준 응답 — 여기서 작업 번호를 꺼낸다
     */
    @Transactional
    public void started(byte[] answer, Long userId, String browserUid) {
        started(text(answer, "id"), userId, browserUid);
    }

    /**
     * 같은 일인데 <b>값을 이미 아는 쪽</b>이 부르는 자리.
     *
     * 프록시 길은 하네스가 준 JSON 밖에 없어서 거기서 번호를 꺼내지만,
     * 스프링이 직접 만드는 길은 번호를 그냥 들고 있다. 이게 없어서 그 길로
     * 만든 작품이 <b>장부에 한 줄도 안 남았다</b> — 만든 사람의 마이페이지에
     * 자기 작품이 안 보였다.
     */
    @Transactional
    public void started(String jobId, Long userId, String browserUid) {
        if (jobId == null || browserUid == null || browserUid.isBlank()) {
            return;
        }
        try {
            if (works.findByJobId(jobId).isEmpty()) {
                works.save(WebtoonWork.started(jobId, userId, browserUid, Instant.now()));
            }
        } catch (RuntimeException e) {
            log.error("작품을 적지 못했습니다 (job={}, user={})", jobId, userId, e);
        }
    }

    /**
     * 진행 상황에 작품 번호가 실려 왔다. 아직 안 적혔으면 채운다.
     *
     * 진행 화면이 0.8 초마다 묻는 자리라 <b>거의 매번 아무 일도 안 한다</b> —
     * 이미 채워져 있으면 쓰지 않는다.
     *
     * @param userId 지금 로그인한 사람. 게스트로 시작했다가 도중에 로그인했으면
     *               그때 주인이 붙는다
     */
    @Transactional
    public void progressed(String jobId, byte[] answer, Long userId) {
        learnedRun(jobId, text(answer, "run_id"), userId);
    }

    /** 위와 같은 일. 작품 번호를 이미 아는 쪽이 부른다. */
    @Transactional
    public void learnedRun(String jobId, String runId, Long userId) {
        if (jobId == null || runId == null) {
            return;
        }
        try {
            works.findByJobId(jobId).ifPresent(work -> {
                boolean 새로 = work.getRunId() == null || work.getRunId().isBlank();
                boolean 주인없음 = work.getUserId() == null && userId != null;
                if (!새로 && !주인없음) {
                    return;                       // 흔한 길 — 아무것도 안 쓴다
                }
                work.learnRun(runId);
                if (userId != null) {
                    work.claimBy(userId);
                }
                works.save(work);
            });
        } catch (RuntimeException e) {
            log.error("작품 번호를 적지 못했습니다 (job={}, run={})", jobId, runId, e);
        }
    }

    /**
     * 이 작품을 이 사람이 봐도 되나.
     *
     * 공개면 누구나, 비공개면 주인만. <b>모르는 작품은 봐도 된다고 답한다</b> —
     * 아직 이 표로 안 옮겨 온 옛 작품이 있고, 그것까지 막으면 멀쩡한 사람이
     * 자기 작품을 못 본다. 옮겨 오는 것이 끝나면 반대로 뒤집을 자리다.
     */
    @Transactional(readOnly = true)
    public boolean mayRead(String runId, Long userId) {
        return works.findFirstByRunId(runId)
                .map(work -> work.isPublic() || isOwner(work, userId))
                .orElse(true);
    }

    /** 이 작품을 이 사람이 공개/비공개로 바꿔도 되나. */
    @Transactional(readOnly = true)
    public boolean mayChange(String runId, Long userId) {
        return works.findFirstByRunId(runId).map(w -> isOwner(w, userId)).orElse(false);
    }

    /** 공개 여부를 적는다. -> 바뀌었으면 true (같은 값이면 아무 일도 안 한다) */
    @Transactional
    public boolean setPublic(String runId, boolean value) {
        return works.findFirstByRunId(runId).map(work -> {
            if (work.isPublic() == value) {
                return false;
            }
            work.setPublic(value);
            works.save(work);
            return true;
        }).orElse(false);
    }

    /** 지금 공개인가. 모르는 작품은 공개로 본다(위 mayRead 와 같은 이유). */
    @Transactional(readOnly = true)
    public boolean isPublic(String runId) {
        return works.findFirstByRunId(runId).map(WebtoonWork::isPublic).orElse(true);
    }

    /**
     * 주인인가.
     *
     * 계정이 직접 붙어 있거나, 이 계정에 이어진 브라우저가 만든 것이면 주인이다.
     * 로그인 안 했으면 주인일 수 없다 — 게스트끼리는 서로를 구별할 방법이 없고
     * (uid 는 지어낼 수 있다), 그 값을 믿으면 아무나 남의 비공개 작품을 열 수 있다.
     */
    private boolean isOwner(WebtoonWork work, Long userId) {
        if (userId == null) {
            return false;
        }
        if (userId.equals(work.getUserId())) {
            return true;
        }
        // 브라우저로 물려받는 것은 **주인이 아직 없는 작품만**이다. 주인이 있는데도
        // 브라우저가 같다고 열어 주면, 한 컴퓨터를 같이 쓴 사람끼리 서로의 비공개
        // 작품을 볼 수 있게 된다(WebtoonWorkRepository.ownedBy 의 주석 참고).
        return work.getUserId() == null
                && links.existsByUserIdAndBrowserUid(userId, work.getBrowserUid());
    }

    /** 이 계정 것 전부. 최근 만든 것부터. */
    @Transactional(readOnly = true)
    public List<String> runIdsOf(Long userId) {
        return works.ownedBy(userId).stream().map(WebtoonWork::getRunId).toList();
    }

    /**
     * 이미 만들어져 있던 작품을 옮겨 담는다.
     *
     * <b>이 표가 생기기 전에 만든 작품은 여기 없다.</b> 그대로 두고 마이페이지가
     * 이 표만 보기 시작하면, 만든 사람에게는 작품이 사라진 것으로 보인다. 그래서
     * 하네스가 아는 것(파일 두 개를 이어 붙인 것)을 한 번 받아 적는다.
     *
     * 작업 번호를 모르므로 작품 번호를 그 자리에 쓴다 — 둘이 겹칠 일이 없고
     * (하네스가 서로 다른 규칙으로 만든다), 유일 제약을 지키면서 "이미 옮겼나"
     * 를 그 값으로 물을 수 있다.
     *
     * 여러 번 불러도 된다. 이미 있는 것은 건너뛴다.
     *
     * @param owned (브라우저 값 -> 그 브라우저가 만든 작품들)
     * @return 이번에 새로 적은 수
     */
    @Transactional
    public int moveIn(java.util.Map<String, ? extends java.util.Collection<String>> owned) {
        int saved = 0;
        for (var entry : owned.entrySet()) {
            String uid = entry.getKey();
            if (uid == null || uid.isBlank()) {
                continue;
            }
            for (String runId : entry.getValue()) {
                if (runId == null || runId.isBlank() || works.existsByRunId(runId)) {
                    continue;
                }
                try {
                    works.save(WebtoonWork.moved(runId, runId, null, uid, Instant.now()));
                    saved++;
                } catch (RuntimeException e) {
                    log.warn("작품을 옮겨 담지 못했습니다 (run={})", runId, e);
                }
            }
        }
        return saved;
    }

    /** JSON 에서 글자 하나. 못 읽어도 던지지 않는다 — 적는 일 때문에 멈출 수 없다. */
    private String text(byte[] json, String field) {
        if (json == null || json.length == 0) {
            return null;
        }
        try {
            JsonNode got = mapper.readTree(json).path(field);
            return got.isTextual() && !got.asText().isBlank() ? got.asText() : null;
        } catch (IOException e) {
            return null;
        }
    }
}
