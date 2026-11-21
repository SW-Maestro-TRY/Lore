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
    private final LouService service=new LouService(db,pages,files,harness,mock(WebtoonJobRepository.class),
            mock(SafetyGuard.class),regen,usage);
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
        when(regen.statusOf("regen")).thenReturn(Map.of("run_id","run","page",2,"status","done"));
        when(db.update(anyString(),any(Object[].class))).thenReturn(1);
        doAnswer(i->{Files.writeString(pagesDir.resolve("page02.png"),"after");return null;})
                .when(files).restore("run",2);
        var captured=new CompletableFuture<List<String>>();
        when(harness.run(isNull(),anyList(),anyMap(),any())).thenAnswer(invocation->{
            List<String> args=invocation.getArgument(1);
            var input=new ObjectMapper().readTree(Path.of(args.get(3)).toFile());
            captured.complete(List.of(args.get(2),Files.readString(Path.of(input.path("before").asText())),
                    Files.readString(Path.of(input.path("after").asText()))));
            Files.writeString(Path.of(args.getLast()),"{\"ok\":true,\"reply\":\"확인했어요\"}");
            return 0;
        });
        var finished=new CountDownLatch(1);
        doAnswer(i->{finished.countDown();return 1;}).when(usage).ingestMetaFile(eq("run"),any());
        TransactionSynchronizationManager.initSynchronization();
        try {
            controller.check("run",Map.of("page",2,"regen_id","regen","method","keep","note","손"));
            TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
            assertEquals(List.of("--lou-check","before","after"),captured.get(5,TimeUnit.SECONDS));
            assertTrue(finished.await(5,TimeUnit.SECONDS));
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
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
