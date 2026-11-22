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
    private final EditorRegen editor;
    private final UsageService usage;

    public LouService(JdbcTemplate db, PageStore pages, RunFiles files,
                      HarnessProcess harness, WebtoonJobRepository jobs, SafetyGuard safety,
                      RegenService regen, UsageService usage, EditorRegen editor) {
        this.editor=editor;
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
        } else if (kind.equals("check")) {
            int no=page(input.path("page"),available);
            require(Set.of("text","keep","redraw","textless").contains(input.path("method").asText()));
            String id=text(input,"regen_id",64);
            require(!id.isBlank());
            var versions=regen.versionsOf(run,no);
            if(versions.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND,"지난 판이 없습니다.");
            Map<String,Object> job;
            try {
                job=regen.statusOf(id);
                require(run.equals(job.get("run_id")) && Objects.equals(no,job.get("page")) && "done".equals(job.get("status")));
            } catch(NoSuchElementException e) { throw new BusinessException(ErrorCode.NOT_FOUND); }
            /* 견주는 그림은 그 다시 그리기의 고치기 전 판과 지금 장이다. 그 뒤에 되돌리거나 또 그렸으면
               지시와 그림이 어긋나므로 받지 않는다. */
            before=regen.beforeIfCurrent(run,no,id).orElseThrow(()->new BusinessException(ErrorCode.INVALID_INPUT,
                    "이 장은 그 뒤에 바뀌어서(되돌리기 · 다시 그리기) 확인할 수 없어요."));
            /* 견줄 지시는 그 다시 그리기에 실제로 쓴 메모다. 루가 낸 지시는 80자를 넘기 일쑤라,
               화면이 보낸 글을 받으면 잘리거나(→ 「지시가 끊겨 확인 못 함」) 400 으로 막혔다(2026-11-22 실측). */
            String used=String.valueOf(job.getOrDefault("note",""));
            if(used.isBlank()) safety.checkText("webtoon-lou-check",text(input,"note",80));
            else input.put("note",used);
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

    private void restoreReviewPages(String run) throws IOException {
        List<Integer> available = pages.pageNumbersOf(run);
        // RunFiles restores every original up to and including its argument.
        files.restore(run, Collections.max(available));
        for (int page : available) {
            if (!Files.isRegularFile(runDir(run).resolve("pages/page%02d.png".formatted(page)))) {
                throw new IOException("검토할 장을 복구하지 못했습니다");
            }
        }
    }

    @Transactional
    public Map<String,Object> fixes(String run, Long owner, Map<String,Object> body) {
        JsonNode input = json.valueToTree(body);
        String reviewId = text(input, "review_id", 36);
        require(!reviewId.isBlank());
        JsonNode ids = input.path("item_ids");
        require(ids.isArray() && !ids.isEmpty());
        Set<String> wanted = new LinkedHashSet<>();
        for (JsonNode id : ids) {
            require(id.isTextual() && !id.asText().isBlank());
            wanted.add(id.asText());
        }
        var review = view(run, reviewId);
        require("review".equals(review.get("kind")) && "done".equals(review.get("status")));
        JsonNode result = json.valueToTree(review.get("result"));
        ObjectNode request = json.createObjectNode();
        ArrayNode selected = request.putArray("items");
        Set<Integer> selectedPages = new HashSet<>();
        for (String group : List.of("items", "dropped")) {
            for (JsonNode item : result.path(group)) {
                if (wanted.remove(item.path("id").asText())) {
                    selected.add(item);
                    selectedPages.add(item.path("page").asInt());
                }
            }
        }
        require(wanted.isEmpty() && selectedPages.size() <= 30);
        String quality = input.path("quality").asText(null);
        JsonNode plan = plan(run, request);
        require(plan.isArray() && plan.size() == selectedPages.size());
        List<Map<String,Object>> queued = new ArrayList<>();
        for (JsonNode job : plan) {
            int page = job.path("page").asInt();
            String method = job.path("method").asText();
            require(selectedPages.remove(page) && Set.of("keep", "redraw").contains(method));
            JsonNode basis = job.path("basis").path(0);
            var price = editor.price(run, page, owner, quality);
            String id = editor.enqueue(run, page, owner, price, job.path("note").asText(), method,
                    basis.isIntegralNumber() ? basis.intValue() : null, method.equals("keep"));
            queued.add(Map.of("page", page, "method", method, "regen_id", id));
        }
        return Map.of("jobs", queued);
    }

    private JsonNode plan(String run, ObjectNode input) {
        Path dir = null;
        try {
            restoreReviewPages(run);
            dir = Files.createTempDirectory("lou-plan-");
            Path request = dir.resolve("input.json"), result = dir.resolve("result.json");
            json.writeValue(request.toFile(), input);
            int exit = harness.run(null, List.of("--run-id", run, "--lou-plan", request.toString(),
                    "--lou-out", result.toString()), Map.of(), line -> {});
            if (exit != 0) throw new IOException("루 계획 실행 실패");
            return json.readTree(result.toFile()).path("jobs");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "고칠 장을 묶지 못했습니다.");
        } finally {
            clean(dir);
        }
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
        Path dir=null, snap=null;
        try {
            db.update("update webtoon_lou_turn set status='running',updated_at=now() where id=?",id);
            dir=Files.createTempDirectory("lou-");
            int no=input.path("page").asInt(input.path("active_page").asInt());
            if (kind.equals("review")) {
                restoreReviewPages(run);
            } else {
                for (JsonNode pin : input.path("pins")) no = Math.max(no, pin.path("page").asInt());
                files.restore(run, no);
            }
            if(before!=null) {
                /* 지금 장을 먼저 떠 두고, 그다음에 아직 그 다시 그리기가 그린 그대로인지 본다. 되돌리기 · 다시 그리기는
                   장을 바꾸기 전에 지금 그림을 판으로 남겨 판 번호부터 키우므로, 떠 두는 사이에 바뀌었으면 여기서 걸린다.
                   하네스는 작품 폴더 밖의 그림을 견주지 않아 작품 폴더 안에 뜬다(이름이 pageNN 이 아니라 장으로 안 잡힌다). */
                snap=runDir(run).resolve("pages/.lou-after-%s.png".formatted(id));
                Files.copy(runDir(run).resolve("pages/page%02d.png".formatted(no)),snap,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if(regen.beforeIfCurrent(run,no,input.path("regen_id").asText()).isEmpty())
                    throw new IOException("확인하기 전에 장이 바뀌었습니다");
                input.put("before",before.toString());
                input.put("after",snap.toString());
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
            if(snap!=null) try { Files.deleteIfExists(snap); } catch(IOException e) { log.warn("루 확인 사본 정리 실패 {}",snap,e); }
            try {
                usage.ingestMetaFile(run,runDir(run).resolve("meta.json"));
            } catch(Exception e) { log.warn("루 원가 적재 실패 {}",id,e); }
            clean(dir);
        }
    }

    private void clean(Path dir) {
        if (dir == null) return;
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("루 임시 파일 정리 실패 {}", dir, e);
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
