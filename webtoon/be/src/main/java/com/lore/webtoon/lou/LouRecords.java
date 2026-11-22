package com.lore.webtoon.lou;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.webtoon.runs.PageRegen;
import com.lore.webtoon.work.WebtoonWork;
import com.lore.webtoon.work.WorkLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class LouRecords {
    private final JdbcTemplate db;
    private final WorkLedger ledger;
    private final ObjectMapper json = new ObjectMapper();

    public LouRecords(JdbcTemplate db, WorkLedger ledger) {
        this.db = db;
        this.ledger = ledger;
    }

    /**
     * 이 메모가 루가 이 장에 내놓은 구도 유지 지시 그대로인가. 그대로일 때만 글자 지키기 · 자동 확인을 붙인다 —
     * 사람이 쓰거나 고쳐 쓴 메모에는 안 붙인다(어느 글을 고치라는지 알 수 없다, 2026-11-22 결정).
     */
    public boolean madeByLou(String run, int page, String note) {
        if (note == null || note.isBlank()) return false;
        for (String answer : db.queryForList("""
                select result_json from webtoon_lou_turn where run_id=? and kind='ask' and status='done'
                order by created_at desc limit 300
                """, String.class, run)) {
            if (answer == null) continue;
            try {
                for (JsonNode plan : json.readTree(answer).path("plans")) {
                    if (!"keep".equals(plan.path("method").asText())
                            || !note.strip().equals(plan.path("note").asText().strip())) continue;
                    for (JsonNode at : plan.path("pages")) if (at.asInt() == page) return true;
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                // 못 읽는 옛 답은 루의 지시로 치지 않는다
            }
        }
        return false;
    }

    public void checked(PageRegen job, JsonNode result) {
        long owner = job.getPaidBy() != null ? job.getPaidBy()
                : ledger.find(job.getRunId()).map(WebtoonWork::getUserId).orElse(0L);
        var request = json.createObjectNode().put("page", job.getPageNo()).put("regen_id", job.getId())
                .put("note", job.getNote()).put("method", "keep").put("auto", true);
        db.update("""
                insert into webtoon_lou_turn(id,run_id,owner_id,kind,request_json,status,result_json)
                values(?,?,?,'check',?,'done',?)
                """, UUID.randomUUID().toString(), job.getRunId(), owner, request.toString(), result.toString());
    }
}
