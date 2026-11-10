package com.lore.zzal.attendance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomPublicationRecoveryRunnerTest {
    @TempDir Path root;
    final Instant now = Instant.parse("2026-10-24T03:00:00Z");
    final CustomBundlePublicationService publisher = mock(CustomBundlePublicationService.class);
    MockEnvironment env() throws Exception {
        return new MockEnvironment().withProperty("LORE_ENV", "staging")
                .withProperty("ZZAL_PUBLICATION_RECOVERY_ENABLED", "true")
                .withProperty("ZZAL_PUBLICATION_RECOVERY_OWNER_ID", "700")
                .withProperty("ZZAL_PUBLICATION_RECOVERY_JOB_ID", "800")
                .withProperty("ZZAL_PUBLICATION_RECOVERY_OPERATION_ID", "synthetic-operation")
                .withProperty("ZZAL_PUBLICATION_RECOVERY_EXPIRES_AT", now.plusSeconds(600).toString())
                .withProperty("ZZAL_CUSTOM_DISPATCH_OUTPUT_ROOT", root.toRealPath().toString());
    }
    CustomPublicationRecoveryRunner runner(MockEnvironment env) { return new CustomPublicationRecoveryRunner(env, publisher); }
    @Test void disabledDoesNotParseConfigOrCreateFiles() {
        assertEquals("DISABLED", runner(new MockEnvironment()).attempt(now).code());
        verifyNoInteractions(publisher);
    }
    @Test void deniesWrongEnvironmentBeforeParsingTargets() throws Exception {
        for (String name : new String[]{"prod", "production", "", "staging,prod"})
            assertEquals("ENVIRONMENT_DENIED", runner(env().withProperty("LORE_ENV", name)).attempt(now).code());
        verifyNoInteractions(publisher);
    }
    @Test void rejectsMalformedTargetsOperationsAndExpiryWithoutPublishing() throws Exception {
        for (String key : new String[]{"OWNER_ID", "JOB_ID"}) {
            assertEquals("INVALID_TARGET",runner(env().withProperty("ZZAL_PUBLICATION_RECOVERY_"+key,"0")).attempt(now).code());
            assertEquals("INVALID_CONFIG",runner(env().withProperty("ZZAL_PUBLICATION_RECOVERY_"+key,"not-a-number")).attempt(now).code());
        }
        for (String value : new String[]{"../escape", "", "line\nbreak"})
            assertEquals("INVALID_OPERATION",runner(env().withProperty("ZZAL_PUBLICATION_RECOVERY_OPERATION_ID",value)).attempt(now).code());
        for (Instant value : new Instant[]{now, now.minusSeconds(1), now.plusSeconds(3601)})
            assertEquals("INVALID_EXPIRY",runner(env().withProperty("ZZAL_PUBLICATION_RECOVERY_EXPIRES_AT",value.toString())).attempt(now).code());
        verifyNoInteractions(publisher);
    }
    @Test void requiresExistingAbsoluteNonSymlinkRoot() throws Exception {
        assertEquals("INVALID_ROOT",runner(env().withProperty("ZZAL_CUSTOM_DISPATCH_OUTPUT_ROOT","relative")).attempt(now).code());
        Path link = root.resolve("link"); Files.createSymbolicLink(link, root.toRealPath());
        assertEquals("INVALID_ROOT",runner(env().withProperty("ZZAL_CUSTOM_DISPATCH_OUTPUT_ROOT",link.toString())).attempt(now).code());
        Files.createSymbolicLink(root.resolve("publication-recovery"), root.toRealPath());
        assertEquals("INVALID_ROOT",runner(env()).attempt(now).code());
        verifyNoInteractions(publisher);
    }
    @Test void persistsTargetFenceBeforePublishAndRejectsRestartOrRetarget() throws Exception {
        when(publisher.publish(700,800,now)).thenAnswer(call -> {
            String fence = Files.readString(root.resolve("publication-recovery/synthetic-operation.attempt"));
            assertTrue(fence.contains("ownerId=700\njobId=800\n")); return 8;
        });
        var result = runner(env()).attempt(now);
        assertEquals("SUCCEEDED",result.code()); assertEquals(8,result.published());
        assertEquals("ALREADY_ATTEMPTED",runner(env()).attempt(now).code());
        assertEquals("ALREADY_ATTEMPTED",runner(env().withProperty("ZZAL_PUBLICATION_RECOVERY_JOB_ID","801")).attempt(now).code());
        verify(publisher,times(1)).publish(700,800,now);
    }
    @Test void publisherOwnerOrStateFailureIsSafeAndNeverRetried() throws Exception {
        when(publisher.publish(700,800,now)).thenThrow(new IllegalStateException("SENSITIVE invalid state/owner"));
        var result=runner(env()).attempt(now);
        assertEquals("PUBLICATION_FAILED",result.code()); assertFalse(result.toString().contains("SENSITIVE"));
        assertEquals("ALREADY_ATTEMPTED",runner(env()).attempt(now).code());
        verify(publisher,times(1)).publish(700,800,now);
    }
    @Test void alreadyPublishedZeroIsSuccessfulWithoutRemovingFence() throws Exception {
        when(publisher.publish(700,800,now)).thenReturn(0);
        var result=runner(env().withProperty("LORE_ENV","dev")).attempt(now);
        assertEquals("SUCCEEDED",result.code());assertEquals(0,result.published());
        assertTrue(Files.exists(root.resolve("publication-recovery/synthetic-operation.attempt")));
    }
    @Test void fenceFailureNeverCallsPublisher() throws Exception {
        Files.writeString(root.resolve("publication-recovery"),"blocking file");
        assertEquals("FENCE_UNAVAILABLE",runner(env()).attempt(now).code()); verifyNoInteractions(publisher);
    }
    @Test void concurrentAttemptsPublishOnce() throws Exception {
        var first=runner(env());var second=runner(env());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->first.attempt(now));var b=pool.submit(()->second.attempt(now));
            assertEquals(java.util.Set.of("SUCCEEDED","ALREADY_ATTEMPTED"),java.util.Set.of(a.get().code(),b.get().code()));
        }
        verify(publisher,times(1)).publish(700,800,now);
    }
    @Test void runnerReturnsWhilePublisherBlocksAndStartsOnlyOnce() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(publisher.publish(eq(700L),eq(800L),any())).thenAnswer(call->{entered.countDown();release.await(5,TimeUnit.SECONDS);return 8;});
        var instance=runner(env().withProperty("ZZAL_PUBLICATION_RECOVERY_EXPIRES_AT",Instant.now().plusSeconds(600).toString()));
        try { assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),()->instance.run(null));
            assertTrue(entered.await(2,TimeUnit.SECONDS)); instance.run(null);
            verify(publisher,times(1)).publish(eq(700L),eq(800L),any());
        } finally {release.countDown();}
    }
    @Test void onlyEnvironmentAndPublicationServiceAreInjected() {
        var constructor=CustomPublicationRecoveryRunner.class.getConstructors()[0];
        assertArrayEquals(new Class<?>[]{org.springframework.core.env.Environment.class,CustomBundlePublicationService.class},constructor.getParameterTypes());
    }
}
