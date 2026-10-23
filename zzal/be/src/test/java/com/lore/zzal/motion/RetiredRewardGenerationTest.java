package com.lore.zzal.motion;

import com.lore.zzal.generation.Layer2Service;
import com.lore.zzal.generation.GenerationRunner;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RetiredRewardGenerationTest {
    @Test void layerTwoCannotQueueOrClaimAnotherAttempt() {
        var submitted = new AtomicInteger();
        var service = new Layer2Service(null, null, null, null, null, null, null, null,
                command -> submitted.incrementAndGet(), 5);
        service.schedule(7L);
        service.bake(7L); // Must return before claim/runner; null collaborators make regressions fail.
        assertThat(submitted.get()).isZero();
    }

    @Test void giftsCannotStartJobsOrTouchOwnedRows() {
        var runner = mock(GenerationRunner.class);
        var rows = mock(ZzalMotionRepository.class);
        for (int seq : new int[]{9, 10, 11, 12, 13, 14, 15, 16, 101, 102}) {
            var motion = ZzalMotion.start(7L, seq, "legacy", "v1");
            when(rows.findById((long) seq)).thenReturn(Optional.of(motion));
        }
        var service = new MotionService(runner, null, null, null, null, rows,
                null, null, null, null, null, 1, 2);
        for (long id : new long[]{9, 10, 11, 12, 13, 14, 15, 16, 101, 102}) service.bakeNow(id);
        verifyNoInteractions(runner);
        verify(rows, never()).save(any());
    }

    @Test void tutorialAndFirstLossDoNotReadOrQueueLegacyGifts() {
        var planner = mock(com.lore.zzal.night.NightPlanner.class);
        var rows = mock(ZzalMotionRepository.class);
        var service = mock(MotionService.class);
        var catalog = mock(MotionCatalog.class);
        var trigger = new com.lore.zzal.night.BakeTrigger(planner, rows, service, catalog,
                command -> { throw new AssertionError("must not dispatch"); });
        trigger.onTutorialDone(null, java.time.Instant.now());
        trigger.onFirstGameLoss(null, java.time.Instant.now());
        verifyNoInteractions(planner, rows, service, catalog);
    }

    @Test void failedGiftIsNotRequeuedButUnrelatedLegacyRowsAreUnchanged() {
        var time = java.time.Instant.now();
        var pet = com.lore.zzal.PetFixture.hatching(1L, "fixture", null, "key", time);
        pet.markAlive("sheet", "identity", time);
        pet.skipTutorial(time);
        org.springframework.test.util.ReflectionTestUtils.setField(pet, "id", 7L);
        var specs = new MotionCatalog("", "", "v1");
        var catalog = mock(MotionCatalog.class);
        when(catalog.isBakeable(anyString())).thenReturn(true);
        var gift = ZzalMotion.forCatalog(7L, specs.bySeq(101).orElseThrow(), time);
        gift.markFailed();
        var repo = mock(ZzalMotionRepository.class);
        when(repo.findByPetIdOrderBySeqAsc(7L)).thenReturn(java.util.List.of(gift));
        var pieces = mock(com.lore.zzal.piece.PieceService.class);
        var planner = new com.lore.zzal.night.NightPlanner(repo, catalog, pieces);
        assertThat(planner.plan(pet, java.time.LocalDate.now(),
                com.lore.zzal.night.NightPlanner.Occasion.NIGHT)).isZero();
        assertThat(gift.getStatus()).isEqualTo(MotionStatus.FAILED);
    }

    @Test void oldOwnedGiftStaysOwnedButPendingGiftIsNotNewlyGranted() {
        var time = java.time.Instant.now();
        var pet = com.lore.zzal.PetFixture.hatching(1L, "fixture", null, "key", time);
        pet.markAlive("sheet", "identity", time);
        pet.skipTutorial(time);
        var catalog = new MotionCatalog("", "", "v1");
        var pending = ZzalMotion.forCatalog(7L, catalog.bySeq(101).orElseThrow(), time);
        var owned = ZzalMotion.forCatalog(7L, catalog.bySeq(102).orElseThrow(), time);
        pending.approve(time.minusSeconds(1));
        owned.approve(time.minusSeconds(1));
        owned.reveal(time.minusSeconds(1));
        var rows = mock(ZzalMotionRepository.class);
        when(rows.findByPetIdAndStatusAndRevealedAtIsNull(null, MotionStatus.OPEN))
                .thenReturn(java.util.List.of(pending));
        var service = mock(com.lore.zzal.pet.PetService.class, CALLS_REAL_METHODS);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "motionRepository", rows);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "reveal", pet, time);
        assertThat(pending.isRevealed()).isFalse();
        assertThat(owned.isRevealed()).isTrue();
    }

    @Test void basicLayerTwoOwnershipIsFrozenWhileNewCountersCannotUnlockMore() {
        var time = java.time.Instant.now();
        var pet = com.lore.zzal.PetFixture.hatching(1L, "fixture", null, "key", time);
        pet.markAlive("sheet", "identity", time);
        var catalog = new MotionCatalog("", "", "v1");
        for (String counter : new String[]{"feeds", "snacks", "cleans", "bathCount", "chatAnswers", "pets", "gameStarts", "wakes"}) {
            org.springframework.test.util.ReflectionTestUtils.setField(pet, counter, 100);
        }
        assertThat(com.lore.zzal.pet.LegacyLayerTwoOwnership.previewOldComputedOwnership(pet, catalog)).isEqualTo(255);
        assertThat(com.lore.zzal.pet.UnlockRules.isUnlocked(pet, catalog.bySeq(9).orElseThrow(), catalog)).isFalse();
        org.springframework.test.util.ReflectionTestUtils.setField(pet, "legacyLayer2OwnedMask", 1);
        assertThat(com.lore.zzal.pet.UnlockRules.isUnlocked(pet, catalog.bySeq(9).orElseThrow(), catalog)).isTrue();
        assertThat(com.lore.zzal.pet.UnlockRules.isUnlocked(pet, catalog.bySeq(10).orElseThrow(), catalog)).isFalse();
        assertThat(com.lore.zzal.pet.UnlockRules.isUnlocked(pet, catalog.bySeq(1).orElseThrow(), catalog)).isTrue();
    }

    @Test void cutoverPreservesRecordedOwnershipEvenWhenLayerIsTemporarilyNotReady() {
        var time = java.time.Instant.now();
        var pet = com.lore.zzal.PetFixture.hatching(1L, "fixture", null, "key", time);
        pet.markAlive("sheet", "identity", time);
        org.springframework.test.util.ReflectionTestUtils.setField(pet, "layer2Status", com.lore.zzal.pet.Layer2Status.PENDING);
        var catalog = new MotionCatalog("", "", "v1");
        var recorded = ZzalMotion.forCatalog(7L, catalog.bySeq(16).orElseThrow(), time);
        recorded.markUnlocked(time);
        assertThat(com.lore.zzal.pet.LegacyLayerTwoOwnership.previewPreservedOwnership(pet, catalog,
                java.util.List.of(recorded))).isEqualTo(128);
        assertThat(com.lore.zzal.pet.LegacyLayerTwoOwnership.previewOldComputedOwnership(pet, catalog)).isZero();
    }

    @Test void piecePlanningSkipsRetiredNewAndFailedRowsWithoutRemovingOtherTierThree() {
        var time = java.time.Instant.now();
        var pet = com.lore.zzal.PetFixture.hatching(1L, "fixture", null, "key", time);
        pet.markAlive("sheet", "identity", time);
        pet.skipTutorial(time);
        pet.enablePieces(time);
        org.springframework.test.util.ReflectionTestUtils.setField(pet, "id", 7L);
        var specs = new MotionCatalog("", "", "v1");
        var unusedNine = ZzalMotion.forCatalog(7L, specs.bySeq(9).orElseThrow(), time);
        var failedSixteen = ZzalMotion.forCatalog(7L, specs.bySeq(16).orElseThrow(), time);
        failedSixteen.markFailed();
        var other = ZzalMotion.forCatalog(7L, specs.bySeq(3).orElseThrow(), time);
        var rows = mock(ZzalMotionRepository.class);
        when(rows.findByPetIdOrderBySeqAsc(7L)).thenReturn(java.util.List.of(unusedNine, failedSixteen, other));
        var catalog = mock(MotionCatalog.class);
        when(catalog.isBakeable(anyString())).thenReturn(true);
        var piece = com.lore.zzal.piece.ZzalPiece.of(7L);
        for (var kind : com.lore.zzal.piece.PieceKind.values()) piece.grant(kind);
        var pieces = mock(com.lore.zzal.piece.PieceService.class);
        when(pieces.find(7L)).thenReturn(piece);
        var planner = new com.lore.zzal.night.NightPlanner(rows, catalog, pieces);
        assertThat(planner.plan(pet, java.time.LocalDate.now(), com.lore.zzal.night.NightPlanner.Occasion.NIGHT)).isEqualTo(1);
        assertThat(unusedNine.getStatus()).isEqualTo(MotionStatus.NONE);
        assertThat(failedSixteen.getStatus()).isEqualTo(MotionStatus.FAILED);
        assertThat(other.getStatus()).isEqualTo(MotionStatus.QUEUED);
    }
    @Test void preservedOwnershipDoesNotBecomeANewLayerTwoCelebration() {
        var at=java.time.Instant.now();
        var pet=com.lore.zzal.PetFixture.hatching(1L,"fixture",null,"key",at);
        pet.markAlive("sheet","identity",at);
        org.springframework.test.util.ReflectionTestUtils.setField(pet,"legacyLayer2OwnedMask",255);
        var service=mock(com.lore.zzal.pet.PetService.class,CALLS_REAL_METHODS);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"catalog",new MotionCatalog("","","v1"));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"announceLayer2",pet,at);
        assertThat(pet.takeLayer2JustUnlocked()).isEmpty();
    }
}
