package com.lore.webtoon.lou;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.lore.common.exception.*;
import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.job.*;
import com.lore.webtoon.runs.RegenService;
import com.lore.webtoon.safety.SafetyGuard;
import com.lore.webtoon.usage.UsageService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

@Service
public class LouService {
    static final int DAILY_LIMIT = 100;
    private final JdbcTemplate db;
    private final ObjectMapper json = new ObjectMapper();
    private final PageStore pages;
    private final RunFiles files;
    private final HarnessProcess harness;
    private final WebtoonJobRepository jobs;
    private final SafetyGuard safety;
    private final RegenService regen;
    private final UsageService usage;

    public LouService(JdbcTemplate db, PageStore pages, RunFiles files,
                      HarnessProcess harness, WebtoonJobRepository jobs, SafetyGuard safety,
                      RegenService regen, UsageService usage) {
        this.db=db;
        this.pages=pages;
        this.files=files;
        this.harness=harness;
        this.jobs=jobs;
        this.safety=safety;
        this.regen=regen;
        this.usage=usage;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sweep() {
        db.update("update webtoon_lou_turn set status='error', error=?, updated_at=now() where status in ('queued','running')",
                "서버가 다시 시작됐습니다. 다시 보내 주세요");
    }

    private static void require(boolean condition) {
        if (!condition) throw new BusinessException(ErrorCode.INVALID_INPUT,"루 대화 입력을 확인해 주세요.");
    }

    private static String text(JsonNode body, String key, int limit) {
        JsonNode value = body.path(key);
        require(value.isMissingNode() || value.isTextual());
        String s = value.asText("");
        require(s.codePointCount(0,s.length()) <= limit);
        return s;
    }

    private int page(JsonNode node, List<Integer> available) {
        require(node.isIntegralNumber() && node.canConvertToInt() && available.contains(node.intValue()));
        return node.intValue();
    }

    private void askInput(ObjectNode input, List<Integer> available) {
        page(input.path("active_page"),available);
        String message=text(input,"text",500);
        JsonNode pins=input.path("pins");
        require(pins.isMissingNode() || pins.isArray() && pins.size()<=8);
        Set<Integer> numbers=new HashSet<>();
        List<String> words=new ArrayList<>(List.of(message));
        for (JsonNode pin:pins) {
            require(pin.isObject());
            ((ObjectNode)pin).retain("n","page","x","y","note");
            page(pin.path("page"),available);
            JsonNode n=pin.path("n");
            require(n.isIntegralNumber() && n.canConvertToInt() && n.intValue()>0 && numbers.add(n.intValue()));
            for(String key:List.of("x","y")) {
                JsonNode v=pin.path(key);
                require(v.isNumber() && v.doubleValue()>=0 && v.doubleValue()<=100);
            }
            words.add(text(pin,"note",80));
        }
        safety.checkText("webtoon-lou",words.toArray(String[]::new));
    }

    @Transactional
    public Map<String,String> submit(String run, Long owner, String kind, Map<String,Object> body) {
        List<Integer> available=pages.pageNumbersOf(run);
        ObjectNode input=json.valueToTree(body);
        boolean ask=kind.equals("ask");
        input.retain(ask ? List.of("text","active_page","pins") : List.of("page","regen_id","note","method"));
        Path before=null;
        if(ask) {
            askInput(input,available);
        } else {
            int no=page(input.path("page"),available);
            require(Set.of("text","keep","redraw","textless").contains(input.path("method").asText()));
            String id=text(input,"regen_id",64);
            require(!id.isBlank());
            var versions=regen.versionsOf(run,no);
            if(versions.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND,"지난 판이 없습니다.");
            try {
                var job=regen.statusOf(id);
                require(run.equals(job.get("run_id")) && Objects.equals(no,job.get("page")) && "done".equals(job.get("status")));
            } catch(NoSuchElementException e) { throw new BusinessException(ErrorCode.NOT_FOUND); }
            before=runDir(run).resolve("pages/versions/page%02d.v%d.png".formatted(no,versions.getFirst().get("version")));
            safety.checkText("webtoon-lou-check",text(input,"note",80));
        }
        ObjectNode request=input.deepCopy();
        var day=LocalDate.now(ZoneId.of("Asia/Seoul"));
        if(db.update("""
                insert into webtoon_lou_quota(owner_id,day,used) values (?,?,1)
                on conflict(owner_id,day) do update set used=webtoon_lou_quota.used+1
                where webtoon_lou_quota.used < ?
                """,owner,day,DAILY_LIMIT)==0) throw new LouLimit();
        String id=UUID.randomUUID().toString();
        if(ask) input.set("history",history(run));
        input.put("lang",WebtoonLanguage.normalize(jobs.findFirstByRunIdOrderByIdDesc(run)
                .map(WebtoonJob::getLanguage).orElse("ko")));
        db.update("insert into webtoon_lou_turn(id,run_id,owner_id,kind,request_json,status) values(?,?,?,?,?,'queued')",
                id,run,owner,kind,request.toString());
        Path previous=before;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                Thread.ofVirtual().start(()->perform(id,run,kind,input,previous));
            }
        });
        return Map.of("id",id,"status","queued");
    }

    private ArrayNode history(String run) {
        ArrayNode history=json.createArrayNode();
        for(var row:list(run)) {
            if(!"done".equals(row.get("status")) || !"ask".equals(row.get("kind"))) continue;
            JsonNode request=json.valueToTree(row.get("request"));
            history.addObject().put("role","user").put("text",request.toString());
            history.addObject().put("role","lou").put("text",json.valueToTree(row.get("result")).path("reply").asText());
        }
        return history;
    }

    private Path runDir(String run) {
        return harness.runsDir().resolve(run).toAbsolutePath();
    }

    void perform(String id,String run,String kind,ObjectNode input,Path before) {
        Path dir=null;
        try {
            db.update("update webtoon_lou_turn set status='running',updated_at=now() where id=?",id);
            dir=Files.createTempDirectory("lou-");
            int no=input.path("page").asInt(input.path("active_page").asInt());
            if(kind.equals("ask") && !input.path("pins").isEmpty()) {
                Set<Integer> selected=new LinkedHashSet<>();
                input.path("pins").forEach(p->selected.add(p.path("page").asInt()));
                no=selected.stream().limit(3).mapToInt(Integer::intValue).max().orElse(no);
            }
            files.restore(run,no);
            if(before!=null) {
                input.put("before",before.toString());
                /* 하네스는 작품 폴더 밖의 그림을 견주지 않는다 — 지금 걸린 장을 그 자리에서 넘긴다. */
                input.put("after",runDir(run).resolve("pages/page%02d.png".formatted(no)).toString());
            }
            Path request=dir.resolve("input.json"), result=dir.resolve("result.json");
            json.writeValue(request.toFile(),input);
            int exit=harness.run(null,List.of("--run-id",run,"--lou-"+kind,request.toString(),"--lou-out",result.toString()),Map.of(),line->{});
            if(exit!=0) throw new IOException("루 실행 실패");
            JsonNode answer=json.readTree(result.toFile());
            if(answer==null || !answer.path("reply").isTextual()) throw new IOException("루 응답 형식 오류");
            db.update("update webtoon_lou_turn set status='done',result_json=?,updated_at=now() where id=?",answer.toString(),id);
        } catch(Exception e) {
            log.warn("루 처리 실패 ({}, {})",id,e.getClass().getSimpleName());
            db.update("update webtoon_lou_turn set status='error',error=?,updated_at=now() where id=?","확인하지 못했어요. 다시 보내 주세요.",id);
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            try {
                usage.ingestMetaFile(run,runDir(run).resolve("meta.json"));
            } catch(Exception e) { log.warn("루 원가 적재 실패 {}",id,e); }
            if(dir!=null) try(var paths=Files.walk(dir)) {
                for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            } catch(IOException e) { log.warn("루 임시 파일 정리 실패 {}",id,e); }
        }
    }

    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(LouService.class);

    private Map<String,Object> row(ResultSet rs,int index) throws SQLException {
        Map<String,Object> value=new LinkedHashMap<>();
        for(String key:List.of("id","kind","status","error")) value.put(key,rs.getString(key));
        value.put("created_at",rs.getTimestamp("created_at").toInstant());
        try {
            for(String key:List.of("request","result")) {
                String raw=rs.getString(key+"_json");
                value.put(key,raw==null ? null : json.readValue(raw,Map.class));
            }
        } catch(IOException e) { throw new SQLException(e); }
        return value;
    }

    public List<Map<String,Object>> list(String run) {
        return db.query("""
                select * from (select * from webtoon_lou_turn where run_id=?
                order by created_at desc,id desc limit 50) recent order by created_at,id
                """,this::row,run);
    }

    public Map<String,Object> view(String run,String id) {
        return db.query("select * from webtoon_lou_turn where run_id=? and id=?",this::row,run,id)
                .stream().findFirst().orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));
    }
}
