package com.lore.webtoon.series;

import com.lore.common.credit.CreditService;
import com.lore.common.exception.BusinessException;
import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.job.*;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.usage.SpendGuard;
import com.lore.webtoon.work.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeriesServiceTest {
    SeriesDraftRepository drafts=mock(SeriesDraftRepository.class);
    SeriesChapterRepository chapters=mock(SeriesChapterRepository.class);
    WebtoonJobRepository jobs=mock(WebtoonJobRepository.class);
    JobService creator=mock(JobService.class);
    WorkLedger ledger=mock(WorkLedger.class);
    StoryStore stories=mock(StoryStore.class);
    RunArt art=mock(RunArt.class);
    JobQueue queue=mock(JobQueue.class);
    SpendGuard guard=mock(SpendGuard.class);
    CreditGate gate=mock(CreditGate.class);
    CreditService credits=mock(CreditService.class);
    SeriesBilling billing=mock(SeriesBilling.class);
    SeriesDigestService digests=mock(SeriesDigestService.class);
    SeriesService service=new SeriesService(drafts,chapters,jobs,creator,ledger,stories,art,queue,guard,gate,billing,digests);
    @TempDir Path dir;
    WebtoonJob source;
    @BeforeEach void setup() throws Exception {
        when(digests.result(anyString())).thenReturn(Optional.empty());
        when(digests.view(anyString())).thenReturn(Map.of("status","NONE"));
        source=WebtoonJob.seeded("job1","browser","run1","romance_fantasy",
            "{\"name\":\"민수\",\"character\":\"높은 곳을 무서워한다\",\"settings\":\"비밀을 밝히지 않는다\"}",Instant.now());
        var work=mock(WebtoonWork.class);
        when(work.getJobId()).thenReturn("job1"); when(work.isPublic()).thenReturn(true);
        when(ledger.find("run1")).thenReturn(Optional.of(work));
        when(ledger.mayRead(eq("run1"),any())).thenReturn(true);
        when(ledger.mayChange("run1",7L)).thenReturn(true);
        when(ledger.isPublic("run1")).thenReturn(true);
        when(jobs.findByPublicId("job1")).thenReturn(Optional.of(source));
        when(jobs.lockByPublicId("job1")).thenReturn(Optional.of(source));
        when(chapters.findByRootJobOrderByEpisodeNumberAsc("job1")).thenReturn(List.of());
        when(stories.scenesOf("run1")).thenReturn(List.of("다리를 피했다.","먼 길로 돌아 약속에 늦었다."));
        when(art.dir("run1")).thenReturn(dir);
        Files.writeString(dir.resolve("sheet.png"),"fixture");
        when(drafts.save(any())).thenAnswer(i->i.getArgument(0));
        when(creator.create(any(),eq(7L),anyString(),isNull(),anyMap())).thenReturn("job2");
    }
    SeriesService.Form form(long revision) {
        return new SeriesService.Form("높은 곳을 무서워함. 비밀은 공개하지 않음.","늦었던 일을 기억하며 우회로를 알고 있다.","다음 약속에는 일찍 출발한다.",revision,true,true,"request1","browser",null,null);
    }
    @Test void requiresAccountAndOwnershipBeforeReturningPrivateMemory() {
        assertThatThrownBy(()->service.draft("run1",null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.draft("run1",8L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(creator,credits);
    }
    @Test void draftsSeparateFixedSettingsStateAndEvidence() {
        var d=service.draft("run1",7L);
        assertThat(d.get("fixed_settings").toString()).contains("높은 곳","비밀").doesNotContain("늦었다");
        assertThat(d.get("progress_state").toString()).contains("1화 종료","늦었다");
        assertThat(d.get("source_scenes")).isEqualTo(List.of("다리를 피했다.","먼 길로 돌아 약속에 늦었다."));
        assertThat(d.get("episode")).isEqualTo(2);
    }
    @Test void emptyBookStillSeedsCharacterSettings() {
        source=WebtoonJob.seeded("job1","browser","run1","romance_fantasy","{\"name\":\"민수\",\"character\":\"높은 곳을 무서워함\",\"lorebook\":{}}",Instant.now());
        when(jobs.findByPublicId("job1")).thenReturn(Optional.of(source));
        assertThat(service.draft("run1",7L).get("fixed_settings").toString()).contains("민수","높은 곳");
    }
    @Test void draftSaveIsFreeAndRejectsStaleRevision() {
        var d=new SeriesDraft("job1"); d.revision=2;
        when(drafts.findById("job1")).thenReturn(Optional.of(d));
        assertThatThrownBy(()->service.saveDraft("run1",7L,form(1))).hasMessageContaining("다른 창");
        assertThat(service.saveDraft("run1",7L,form(2))).containsEntry("revision",3L);
        verify(jobs,times(2)).lockByPublicId("job1");
        verifyNoInteractions(creator,credits);
    }
    @Test void nextEpisodeFreezesTwoLayersAndInheritsStyleWithAccountCharge() {
        assertThat(service.createNext("run1",7L,form(0))).containsEntry("id","job2");
        var cap=ArgumentCaptor.forClass(SeriesChapter.class); verify(chapters).save(cap.capture());
        var c=cap.getValue(); assertThat(c.rootJob).isEqualTo("job1"); assertThat(c.episodeNumber).isEqualTo(2);
        assertThat(c.memoryJson).contains("fixed_settings","progress_state","source_run","request");
        var req=ArgumentCaptor.forClass(JobService.CreateRequest.class);
        verify(creator).create(req.capture(),eq(7L),eq("browser"),isNull(),anyMap());
        assertThat(req.getValue().story()).isEqualTo(form(0).story());
        assertThat(req.getValue().settings()).isEqualTo(form(0).fixed_settings());
        assertThat(req.getValue().mode()).isEqualTo("own");
        assertThat(req.getValue().style()).isEqualTo(source.getStyle());
        verify(billing).charge(eq(7L),anyInt(),eq("job2"),anyString());
    }
    @Test void multipleBooksAndExtraSettingsCarryForwardButEditedStateWins() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var book=com.lore.webtoon.lorebook.LoreBook.clean(Map.of("confirmed",true,"books",List.of(
            Map.of("name","세계","fixed_settings","밤에는 문이 열린다","confirmed",true),
            Map.of("name","인물","fixed_settings","민수는 날지 못한다","confirmed",true))),true);
        source=WebtoonJob.seeded("job1","browser","run1","romance_fantasy",mapper.writeValueAsString(Map.of("name","민수","settings","비밀 공개 금지","lorebook",book)),Instant.now());
        when(jobs.findByPublicId("job1")).thenReturn(Optional.of(source));when(jobs.lockByPublicId("job1")).thenReturn(Optional.of(source));
        assertThat(service.draft("run1",7L).get("fixed_settings").toString()).contains("밤에는 문","날지 못한다","비밀 공개 금지");
        service.createNext("run1",7L,form(0));
        var req=ArgumentCaptor.forClass(JobService.CreateRequest.class);verify(creator).create(req.capture(),eq(7L),eq("browser"),isNull(),anyMap());
        assertThat(req.getValue().lorebook()).isEqualTo(book);assertThat(req.getValue().settings()).isEqualTo(form(0).fixed_settings());
    }
    @Test void duplicateRequestReturnsExistingJobWithoutChargingAgain() {
        when(chapters.findByRootJobAndRequestToken("job1","request1")).thenReturn(Optional.of(
            new SeriesChapter("job2","job1","job1",2,"request1","{}")));
        assertThat(service.createNext("run1",7L,form(0))).containsEntry("id","job2");
        verifyNoInteractions(creator,credits); verify(drafts,never()).save(any());
    }
    @Test void activeSuccessorBlocksAnotherAttemptButFailedSuccessorCanRetry() {
        var c=new SeriesChapter("job2","job1","job1",2,"request0","{}");
        var pending=mock(WebtoonJob.class);
        when(chapters.findByRootJobOrderByEpisodeNumberAsc("job1")).thenReturn(List.of(c));
        when(jobs.findByPublicId("job2")).thenReturn(Optional.of(pending));
        when(pending.getStatus()).thenReturn(JobStatus.QUEUED);
        assertThatThrownBy(()->service.createNext("run1",7L,form(0))).hasMessageContaining("이미 이어 만든");
        verifyNoInteractions(creator,credits);
        when(pending.getStatus()).thenReturn(JobStatus.ERROR);
        assertThat(service.createNext("run1",7L,form(0))).containsEntry("id","job2");
    }
    @Test void missingSheetAndMissingRightsCannotStartOrCharge() throws Exception {
        var f=form(0);
        var noRights=new SeriesService.Form(f.fixed_settings(),f.progress_state(),f.story(),0,true,false,"request1","browser",null,null);
        assertThatThrownBy(()->service.createNext("run1",7L,noRights)).hasMessageContaining("저작권");
        Files.delete(dir.resolve("sheet.png"));
        assertThatThrownBy(()->service.createNext("run1",7L,f)).hasMessageContaining("시트");
        verifyNoInteractions(creator,credits);
    }
    @Test void privateSourceProducesPrivateSuccessor() {
        when(ledger.isPublic("run1")).thenReturn(false);
        service.createNext("run1",7L,form(0));
        verify(ledger).setPublicByJob("job2",false);
    }
    @Test void privateOrPendingEpisodesAreNotDisclosedToReaders() {
        var c=new SeriesChapter("job2","job1","job1",2,"request0","{}");
        var next=mock(WebtoonJob.class); when(next.getPublicId()).thenReturn("job2");
        when(next.getRunId()).thenReturn("run2"); when(next.getStatus()).thenReturn(JobStatus.DONE);
        var privateWork=mock(WebtoonWork.class); when(privateWork.isPublic()).thenReturn(false);
        when(ledger.find("run2")).thenReturn(Optional.of(privateWork));
        when(chapters.findByRootJobOrderByEpisodeNumberAsc("job1")).thenReturn(List.of(c));
        when(jobs.findByPublicId("job2")).thenReturn(Optional.of(next));
        var rows=(List<?>)service.chapters("run1",null).get("chapters");
        assertThat(rows).hasSize(1); assertThat(rows.toString()).doesNotContain("run2","job2");
    }
    @Test void rolledBackCreationCompensatesSeparatelyCommittedCharge() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.createNext("run1",7L,form(0));
            var callbacks=org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            callbacks.forEach(c->c.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(billing).refund(7L,"job2");
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test void trashedSuccessorExplainsHowToContinueWithoutCharging() {
        var c=new SeriesChapter("job2","job1","job1",2,"request0","{}");
        var next=mock(WebtoonJob.class);when(next.getRunId()).thenReturn("run2");when(next.getStatus()).thenReturn(JobStatus.DONE);
        var trashed=mock(WebtoonWork.class);when(trashed.isTrashed()).thenReturn(true);
        when(ledger.find("run2")).thenReturn(Optional.of(trashed));
        when(chapters.findByRootJobOrderByEpisodeNumberAsc("job1")).thenReturn(List.of(c));
        when(jobs.findByPublicId("job2")).thenReturn(Optional.of(next));
        assertThatThrownBy(()->service.createNext("run1",7L,form(0))).hasMessageContaining("휴지통");
        verifyNoInteractions(creator,credits);
    }
    @Test void completedEpisodesChainThroughFourWithLatestMemoryAndOneRoot() {
        var saved=new LinkedHashMap<String,SeriesChapter>();
        when(chapters.findById(anyString())).thenAnswer(i->Optional.ofNullable(saved.get(i.getArgument(0))));
        when(chapters.findByRootJobOrderByEpisodeNumberAsc("job1")).thenAnswer(i->new ArrayList<>(saved.values()));
        when(chapters.save(any())).thenAnswer(i->{SeriesChapter c=i.getArgument(0);saved.put(c.jobId,c);return c;});
        for(int n=2;n<=4;n++) {
            String before="run"+(n-1), id="job"+n, run="run"+n;
            var draft=service.draft(before,7L);
            assertThat(draft).containsEntry("episode",n);
            if(n>2) assertThat(draft).containsEntry("fixed_settings","fixed-"+(n-1)).containsEntry("previous_state","state-"+(n-1));
            when(creator.create(any(),eq(7L),anyString(),isNull(),anyMap())).thenReturn(id);
            var f=new SeriesService.Form("fixed-"+n,"state-"+n,"request-"+n,0,true,true,"token-"+n,"browser",null,null);
            assertThat(service.createNext(before,7L,f)).containsEntry("id",id);
            var c=saved.get(id);
            assertThat(c.rootJob).isEqualTo("job1");assertThat(c.sourceJob).isEqualTo("job"+(n-1));
            assertThat(c.episodeNumber).isEqualTo(n);assertThat(c.memoryJson).contains("request-"+n,"state-"+n);
            // Completion is a fixture, not an actual generated episode.
            var done=WebtoonJob.seeded(id,"browser",run,"romance_fantasy","{}",Instant.now());
            when(jobs.findByPublicId(id)).thenReturn(Optional.of(done));when(jobs.lockByPublicId(id)).thenReturn(Optional.of(done));
            var work=mock(WebtoonWork.class);when(work.getJobId()).thenReturn(id);when(work.isPublic()).thenReturn(true);
            when(ledger.find(run)).thenReturn(Optional.of(work));when(ledger.mayRead(eq(run),any())).thenReturn(true);
            when(ledger.mayChange(run,7L)).thenReturn(true);when(ledger.isPublic(run)).thenReturn(true);
            when(stories.scenesOf(run)).thenReturn(List.of(n+"화 완료 사건"));when(art.dir(run)).thenReturn(dir);
            assertThatThrownBy(()->service.draft(before,7L)).hasMessageContaining("이미 이어 만든");
        }
        var rows=(List<Map<String,Object>>)service.chapters("run4",7L).get("chapters");
        assertThat(rows).extracting(row->row.get("episode")).containsExactly(1,2,3,4);
        assertThat(rows).extracting(row->row.get("run_id")).containsExactly("run1","run2","run3","run4");
        assertThat(service.draft("run4",7L)).containsEntry("episode",5);
        verify(billing,times(3)).charge(eq(7L),anyInt(),anyString(),anyString());
    }

    @Test void deletedOriginalRootDoesNotBreakRemainingEpisodes() {
        var current=new SeriesChapter("job1","deleted-root","deleted-root",2,"old","{\"fixed_settings\":\"변하지 않는 설정\",\"progress_state\":\"앞 회차 시작 상태\"}");
        when(chapters.findById("job1")).thenReturn(Optional.of(current));
        when(chapters.findByRootJobOrderByEpisodeNumberAsc("deleted-root")).thenReturn(List.of(current));
        assertThat(service.chapters("run1",7L)).containsEntry("episode",2);
        assertThat((List<?>)service.chapters("run1",7L).get("chapters")).hasSize(1);
        assertThat(service.draft("run1",7L)).containsEntry("episode",3).containsEntry("fixed_settings","변하지 않는 설정");
        service.createNext("run1",7L,form(0));
        var cap=ArgumentCaptor.forClass(SeriesChapter.class);verify(chapters).save(cap.capture());
        assertThat(cap.getValue().rootJob).isEqualTo("deleted-root");
        assertThat(cap.getValue().episodeNumber).isEqualTo(3);
        assertThat(current.memoryJson).contains("앞 회차 시작 상태").doesNotContain("우회로");
    }

    @Test void sameCharacterIndependentWorkDoesNotJoinExistingSeries() {
        var other=WebtoonJob.seeded("other-job","browser","other-run","romance_fantasy",source.getInputJson(),Instant.now());
        var work=mock(WebtoonWork.class);when(work.getJobId()).thenReturn("other-job");when(work.isPublic()).thenReturn(true);
        when(ledger.find("other-run")).thenReturn(Optional.of(work));
        when(ledger.mayRead(eq("other-run"),any())).thenReturn(true);
        when(ledger.mayChange("other-run",7L)).thenReturn(true);
        when(jobs.findByPublicId("other-job")).thenReturn(Optional.of(other));
        when(stories.scenesOf("other-run")).thenReturn(List.of("별개 이야기의 결말"));
        when(chapters.findByRootJobOrderByEpisodeNumberAsc("job1")).thenReturn(List.of(new SeriesChapter("job2","job1","job1",2,"old","{}")));
        var rows=(List<Map<String,Object>>)service.chapters("other-run",7L).get("chapters");
        assertThat(rows).extracting(r->r.get("run_id")).containsExactly("other-run");
        assertThat(service.draft("other-run",7L)).containsEntry("episode",2);
        verify(chapters,times(2)).findByRootJobOrderByEpisodeNumberAsc("other-job");
        verify(jobs,never()).findByPublicId("job2");
    }

    @Test void digestFillsFieldsSoNobodyHasToSummarise() {
        when(digests.result("job1")).thenReturn(Optional.of(Map.of(
            "entries",List.of(Map.of("id","L0001","title","다리","content","민수가 피하는 다리","new",true)),
            "progress",List.of(Map.of("key","P001","title","약속","text","민수는 약속에 늦었다")))));
        var d=service.draft("run1",7L);
        assertThat(d.get("entries").toString()).contains("다리");
        assertThat(d.get("progress").toString()).contains("민수는 약속에 늦었다");
        assertThat(d.get("progress_state")).isEqualTo("");
    }
    @Test void progressRowsGoIntoBookNotTwiceIntoMemory() {
        var f=form(0);
        var rows=List.<Map<String,Object>>of(Map.of("key","P001","title","약속","text","민수는 약속에 늦었다"));
        service.createNext("run1",7L,new SeriesService.Form(f.fixed_settings(),"",f.story(),0,true,true,"request1","browser",List.of(),rows));
        var req=ArgumentCaptor.forClass(JobService.CreateRequest.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String,Object>> state=ArgumentCaptor.forClass(Map.class);
        verify(creator).create(req.capture(),eq(7L),anyString(),isNull(),state.capture());
        assertThat(req.getValue().lorebook().get("progress").toString()).contains("민수는 약속에 늦었다");
        assertThat(state.getValue().get("progress_state")).isEqualTo("");
    }
    @Test void keptEntriesGoIntoNextEpisodeBook() {
        var e=List.<Map<String,Object>>of(Map.of("title","다리","content","민수가 피하는 다리","kind","장소","keys",List.of("다리"),"always",false,"enabled",true));
        var f=form(0);
        service.createNext("run1",7L,new SeriesService.Form(f.fixed_settings(),f.progress_state(),f.story(),0,false,true,"request1","browser",e,null));
        var req=ArgumentCaptor.forClass(JobService.CreateRequest.class);
        verify(creator).create(req.capture(),eq(7L),anyString(),isNull(),anyMap());
        assertThat(req.getValue().lorebook()).containsEntry("confirmed",true);
        assertThat(req.getValue().lorebook().get("entries").toString()).contains("민수가 피하는 다리");
    }
    @Test void digestStartsFreeForOwnersLatestEpisode() {
        when(digests.start(eq("job1"),eq(7L),anyMap(),anyString())).thenReturn(Map.of("status","RUNNING"));
        assertThat(service.startDigest("run1",7L)).containsEntry("status","RUNNING");
        verifyNoInteractions(creator,credits,billing);
    }
}
