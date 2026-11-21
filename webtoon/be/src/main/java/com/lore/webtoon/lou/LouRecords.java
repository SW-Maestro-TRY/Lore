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
