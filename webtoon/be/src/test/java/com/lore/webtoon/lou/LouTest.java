package com.lore.webtoon.lou;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.Admins;
import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.job.*;
import com.lore.webtoon.runs.RegenService;
import com.lore.webtoon.safety.SafetyGuard;
import com.lore.webtoon.usage.UsageService;
import com.lore.webtoon.work.WorkLedger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LouTest {
    @TempDir Path root;
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final PageStore pages=mock(PageStore.class);
    private final HarnessProcess harness=mock(HarnessProcess.class);
    private final RunFiles files=mock(RunFiles.class);
    private final RegenService regen=mock(RegenService.class);
    private final UsageService usage=mock(UsageService.class);
    private final EditorRegen editor=mock(EditorRegen.class);
    private final LouService service=new LouService(db,pages,files,harness,mock(WebtoonJobRepository.class),
            mock(SafetyGuard.class),regen,usage,editor);
    private final WorkLedger ledger=mock(WorkLedger.class);
    private final LouController controller=new LouController(service,mock(Admins.class),ledger);
    private final Map<String,Object> ask=Map.of("active_page",2,"text","");

    @BeforeEach void setup() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(7L,null));
        when(ledger.mayChange("run",7L)).thenReturn(true);
        when(pages.pageNumbersOf("run")).thenReturn(List.of(1,2));
        when(harness.runsDir()).thenReturn(root);
    }

    @AfterEach void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test void ownershipBeforeSubmissionAndRead() {
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BusinessException.class,()->controller.ask("other",ask)).getErrorCode());
        assertThrows(BusinessException.class,()->controller.view("other","id"));
        verifyNoInteractions(db,harness,pages);
    }

    @Test void invalidPageAndPin() {
        assertThrows(BusinessException.class,()->controller.ask("run",Map.of("active_page",3)));
        assertThrows(BusinessException.class,()->controller.ask("run",Map.of("active_page",2,"pins",List.of(
                Map.of("page",2,"n",1,"x",101,"y",0)))));
        verifyNoInteractions(db,harness);
    }

    @Test void dailyLimit() {
        assertThrows(LouLimit.class,()->controller.ask("run",ask));
        assertEquals(429,controller.limit().getStatusCode().value());
    }

    @Test void missingVersion() {
        var form=Map.<String,Object>of("page",2,"regen_id","missing","note","","method","keep");
        assertEquals(ErrorCode.NOT_FOUND,assertThrows(BusinessException.class,()->controller.check("run",form)).getErrorCode());
    }

    @Test void comparesArchivedVersionWithRestoredCurrentPage() throws Exception {
        Path pagesDir=root.resolve("run/pages");
        Files.createDirectories(pagesDir.resolve("versions"));
        Path before=pagesDir.resolve("versions/page02.v12.png");
        Files.writeString(before,"before");
        when(regen.versionsOf("run",2)).thenReturn(List.of(Map.of("version",12),Map.of("version",9)));
        // 루가 낸 지시는 80자를 넘는다 — 견줄 때는 화면이 보낸 글이 아니라 그 다시 그리기에 쓴 메모를 쓴다
        String used="1컷 남자의 상의를 흰 셔츠로 바꾼다. ".repeat(6)+"나머지는 그대로.";
        when(regen.statusOf("regen")).thenReturn(Map.of("run_id","run","page",2,"status","done","note",used));
        when(db.update(anyString(),any(Object[].class))).thenReturn(1);
        // 그 뒤에 되돌리거나 또 그렸으면 그 다시 그리기 번호로는 확인하지 않는다(지시와 견줄 그림이 어긋난다)
        when(regen.beforeIfCurrent("run",2,"regen")).thenReturn(Optional.empty());
        assertEquals(ErrorCode.INVALID_INPUT,assertThrows(BusinessException.class,
                ()->controller.check("run",Map.of("page",2,"regen_id","regen","method","keep"))).getErrorCode());
        when(regen.beforeIfCurrent("run",2,"regen")).thenReturn(Optional.of(before));
        when(regen.snapshotIfCurrent(eq("run"),eq(2),eq("regen"),any())).thenAnswer(i->{
            Files.copy(pagesDir.resolve("page02.png"),i.<Path>getArgument(3));
            return Optional.of(before);
        });
        doAnswer(i->{Files.writeString(pagesDir.resolve("page02.png"),"after");return null;})
                .when(files).restore("run",2);
        var captured=new CompletableFuture<List<String>>();
        var afterPath=new java.util.concurrent.atomic.AtomicReference<Path>();
        when(harness.run(isNull(),anyList(),anyMap(),any())).thenAnswer(invocation->{
            List<String> args=invocation.getArgument(1);
            var input=new ObjectMapper().readTree(Path.of(args.get(3)).toFile());
            afterPath.set(Path.of(input.path("after").asText()));
            captured.complete(List.of(args.get(2),Files.readString(Path.of(input.path("before").asText())),
                    Files.readString(Path.of(input.path("after").asText())),input.path("note").asText()));
            Files.writeString(Path.of(args.getLast()),"{\"ok\":true,\"reply\":\"확인했어요\"}");
            return 0;
        });
        var finished=new CountDownLatch(1);
        doAnswer(i->{finished.countDown();return 1;}).when(usage).ingestMetaFile(eq("run"),any());
        TransactionSynchronizationManager.initSynchronization();
        try {
            controller.check("run",Map.of("page",2,"regen_id","regen","method","keep","note","손"));
            TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
            assertEquals(List.of("--lou-check","before","after",used),captured.get(5,TimeUnit.SECONDS));
            assertTrue(finished.await(5,TimeUnit.SECONDS));
            assertEquals(pagesDir,afterPath.get().getParent());              // 지금 장을 작품 폴더 안에 떠 두고 견준다
            assertTrue(afterPath.get().getFileName().toString().startsWith(".lou-after-"));
            assertFalse(Files.exists(afterPath.get()));                       // 떠 둔 사본은 치운다
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test void pageChangedBeforeTheCheckRunsIsNotCompared() throws Exception {
        // 받을 때는 그 다시 그리기 그대로였는데, 확인이 돌기 전에 되돌렸다 — 견주지 않고 오류로 남긴다
        Path pagesDir=root.resolve("run/pages");
        Files.createDirectories(pagesDir.resolve("versions"));
        Path before=pagesDir.resolve("versions/page02.v12.png");
        Files.writeString(before,"before");
        Files.writeString(pagesDir.resolve("page02.png"),"reverted");
        when(regen.versionsOf("run",2)).thenReturn(List.of(Map.of("version",12)));
        when(regen.statusOf("regen")).thenReturn(Map.of("run_id","run","page",2,"status","done","note","손"));
        when(regen.beforeIfCurrent("run",2,"regen")).thenReturn(Optional.of(before));
        when(regen.snapshotIfCurrent(eq("run"),eq(2),eq("regen"),any())).thenReturn(Optional.empty());
        when(db.update(anyString(),any(Object[].class))).thenReturn(1);
        var finished=new CountDownLatch(1);
        doAnswer(i->{finished.countDown();return 1;}).when(usage).ingestMetaFile(eq("run"),any());
        TransactionSynchronizationManager.initSynchronization();
        try {
            controller.check("run",Map.of("page",2,"regen_id","regen","method","keep"));
            TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
            assertTrue(finished.await(5,TimeUnit.SECONDS));
            verify(harness,never()).run(any(),anyList(),anyMap(),any());
            verify(db).update(contains("status='error'"),any(Object[].class));
            try(var left=Files.list(pagesDir)) { assertTrue(left.noneMatch(f->f.getFileName().toString().startsWith(".lou-after-"))); }
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test void onlyLousOwnNoteTurnsOnTextGuardAndCheck() {
        var records=new LouRecords(db,ledger);
        when(db.queryForList(anyString(),eq(String.class),any(Object[].class))).thenReturn(List.of(
                "못 읽는 옛 답",
                "{\"plans\":[{\"method\":\"keep\",\"pages\":[2],\"note\":\"상의를 흰 셔츠로 바꾼다. 나머지는 그대로.\"},"
                        +"{\"method\":\"redraw\",\"pages\":[2],\"note\":\"새로 그린다.\"}]}"));
        assertTrue(records.madeByLou("run",2,"상의를 흰 셔츠로 바꾼다. 나머지는 그대로."));
        assertFalse(records.madeByLou("run",1,"상의를 흰 셔츠로 바꾼다. 나머지는 그대로."));   // 다른 장
        assertFalse(records.madeByLou("run",2,"상의를 파란 셔츠로 바꾼다. 나머지는 그대로."));  // 사람이 고쳐 쓴 메모
        assertFalse(records.madeByLou("run",2,"새로 그린다."));                               // 구도 유지가 아니다

        var regenJobs=mock(RegenService.class);
        var made=mock(LouRecords.class);
        when(made.madeByLou("run",2,"루 지시")).thenReturn(true);
        var queue=new EditorRegen(regenJobs,mock(com.lore.webtoon.credit.CreditGate.class),made);
        var free=new EditorRegen.Price(null,0);
        queue.enqueue("run",2,7L,free,"루 지시","keep",null,false);
        verify(regenJobs).queue("run",2,"루 지시",null,null,"keep",null,true);
        queue.enqueue("run",2,7L,free,"내 메모","keep",null,false);
        verify(regenJobs).queue("run",2,"내 메모",null,null,"keep",null,false);
    }

    @Test void automaticCheckWritesCompletedTurnWithoutQuota() throws Exception {
        var job = mock(com.lore.webtoon.runs.PageRegen.class);
        when(job.getId()).thenReturn("regen");
        when(job.getRunId()).thenReturn("run");
        when(job.getPageNo()).thenReturn(2);
        when(job.getNote()).thenReturn("손");
        var records = new LouRecords(db, ledger);
        var result = new ObjectMapper().readTree("{\"ok\":false}");
        records.checked(job, result);
        verify(db).update(contains("'check',?,'done'"), anyString(), eq("run"), eq(0L),
                eq("{\"page\":2,\"regen_id\":\"regen\",\"note\":\"손\",\"method\":\"keep\",\"auto\":true}"),
                eq(result.toString()));
        verifyNoMoreInteractions(db);
    }

    @Test void reviewUsesQuotaAndRestoresEveryPage() throws Exception {
        assertThrows(LouLimit.class, () -> controller.review("run"));
        when(db.update(anyString(),any(Object[].class))).thenReturn(1);
        doAnswer(call -> {
            Path dir = Files.createDirectories(root.resolve("run/pages"));
            Files.writeString(dir.resolve("page01.png"), "첫 장");
            Files.writeString(dir.resolve("page02.png"), "끝 장");
            return null;
        }).when(files).restore("run", 2);
        when(harness.run(isNull(),anyList(),anyMap(),any())).thenAnswer(call -> {
            List<String> args = call.getArgument(1);
            assertEquals("--lou-review", args.get(2));
            assertEquals(Map.of("lang","ko"), new ObjectMapper().readValue(Path.of(args.get(3)).toFile(), Map.class));
            Files.writeString(Path.of(args.getLast()), "{\"reply\":\"완료\"}");
            return 0;
        });
        TransactionSynchronizationManager.initSynchronization();
        try {
            controller.review("run");
            verify(db).update(contains("insert into webtoon_lou_turn"),anyString(),eq("run"),eq(7L),eq("review"),eq("{}"));
            TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
            verify(usage,timeout(5000)).ingestMetaFile(eq("run"),any());
            verify(db).update(contains("status='done'"),eq("{\"reply\":\"완료\"}"),anyString());
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test void fixesSelectsStoredItemsAndUsesPlan() throws Exception {
        LouService lou = spy(service);
        var result = Map.of("items", List.of(Map.of("id","a","page",1), Map.of("id","skip","page",1)),
                "dropped", List.of(Map.of("id","b","page",2)));
        doReturn(Map.of("kind","review","status","done","result",result)).when(lou).view("run","r");
        Path dir = Files.createDirectories(root.resolve("run/pages"));
        Files.createFile(dir.resolve("page01.png"));
        Files.createFile(dir.resolve("page02.png"));
        when(harness.run(isNull(),anyList(),anyMap(),any())).thenAnswer(call -> {
            List<String> args = call.getArgument(1);
            assertEquals("--lou-plan", args.get(2));
            var items = new ObjectMapper().readTree(Path.of(args.get(3)).toFile()).get("items");
            assertEquals(2,items.size());
            assertEquals("a",items.get(0).get("id").asText());
            assertEquals("b",items.get(1).get("id").asText());
            Files.writeString(Path.of(args.getLast()), """
                    {"jobs":[{"page":1,"method":"keep","note":"손","basis":[2]},
                             {"page":2,"method":"redraw","note":"문","basis":[]}]}
                    """);
            return 0;
        });
        when(editor.together(any())).thenAnswer(i -> i.<java.util.function.Supplier<?>>getArgument(0).get());
        var price = new EditorRegen.Price("surf",3);
        when(editor.price(eq("run"),anyInt(),eq(7L),eq("surf"))).thenReturn(price);
        when(editor.enqueue("run",1,7L,price,"손","keep",2,true)).thenReturn("j1");
        when(editor.enqueue("run",2,7L,price,"문","redraw",null,false)).thenReturn("j2");
        assertEquals(Map.of("jobs",List.of(Map.of("page",1,"method","keep","regen_id","j1"),
                Map.of("page",2,"method","redraw","regen_id","j2"))),
                lou.fixes("run",7L,Map.of("review_id","r","item_ids",List.of("b","a"),"quality","surf")));
        assertThrows(BusinessException.class,()->lou.fixes("run",7L,Map.of("review_id","r","item_ids",List.of("missing"))));
    }

    @Test void fixesRejectsEmptyForeignAndUnfinishedReviews() {
        assertThrows(BusinessException.class,()->service.fixes("run",7L,Map.of("review_id","r","item_ids",List.of())));
        var body=Map.<String,Object>of("review_id","r","item_ids",List.of("a"));
        assertThrows(BusinessException.class,()->service.fixes("other",7L,body));
        LouService lou=spy(service);
        doReturn(Map.of("kind","review","status","queued")).when(lou).view("run","r");
        assertThrows(BusinessException.class,()->lou.fixes("run",7L,body));
        verifyNoInteractions(editor,harness);
    }

    @Test void queuedToDone() throws Exception { flow(true); }
    @Test void queuedToError() throws Exception { flow(false); }

    private void flow(boolean success) throws Exception {
        List<String> states=new CopyOnWriteArrayList<>();
        CountDownLatch accounted=new CountDownLatch(1);
        when(db.update(anyString(),any(Object[].class))).thenAnswer(invocation->{
            String sql=invocation.getArgument(0);
            for(String state:List.of("queued","running","done","error")) {
                if(sql.contains("'"+state+"'")) states.add(state);
            }
            return 1;
        });
        when(harness.run(isNull(),anyList(),anyMap(),any())).thenAnswer(invocation->{
            List<String> args=invocation.getArgument(1);
            assertEquals(List.of("--run-id","run","--lou-ask"),args.subList(0,3));
            var input=new ObjectMapper().readTree(Path.of(args.get(3)).toFile());
            assertEquals("ko",input.get("lang").asText());
            if(success) Files.writeString(Path.of(args.getLast()),"{\"reply\":\"확인했어요\"}");
            return success ? 0 : 1;
        });
        doAnswer(i->{accounted.countDown();return 1;}).when(usage).ingestMetaFile(eq("run"),any());
        TransactionSynchronizationManager.initSynchronization();
        try {
            var queued=service.submit("run",7L,"ask",ask);
            assertEquals("queued",queued.get("status"));
            verifyNoInteractions(harness);
            TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
            assertTrue(accounted.await(5,TimeUnit.SECONDS));
            assertEquals(List.of("queued","running",success?"done":"error"),states);
            verify(files).restore("run",2);
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
}
