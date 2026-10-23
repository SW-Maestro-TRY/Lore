package com.lore.webtoon.series;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.credit.CreditDomain;
import com.lore.common.credit.CreditService;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.job.*;
import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.usage.SpendGuard;
import com.lore.webtoon.work.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.*;
import java.util.*;

@Service
public class SeriesService {
    static final int MEMORY_MAX = 16000;
    private final SeriesDraftRepository drafts;
    private final SeriesChapterRepository chapters;
    private final WebtoonJobRepository jobs;
    private final JobService creator;
    private final WorkLedger ledger;
    private final StoryStore stories;
    private final RunArt art;
    private final JobQueue queue;
    private final SpendGuard guard;
    private final CreditGate gate;
    private final SeriesBilling billing;
    private final ObjectMapper json = new ObjectMapper();

    public SeriesService(SeriesDraftRepository drafts, SeriesChapterRepository chapters,
            WebtoonJobRepository jobs, JobService creator, WorkLedger ledger, StoryStore stories,
            RunArt art, JobQueue queue, SpendGuard guard, CreditGate gate, SeriesBilling billing) {
        this.drafts=drafts; this.chapters=chapters; this.jobs=jobs; this.creator=creator;
        this.ledger=ledger; this.stories=stories; this.art=art; this.queue=queue;
        this.guard=guard; this.gate=gate; this.billing=billing;
    }
    public record Form(String fixed_settings, String progress_state, String story, long revision,
                       boolean confirmed, boolean agree_ip, String request_token, String uid) {}

    private WebtoonJob source(String run, Long user, boolean own) {
        if (own && user == null) throw new BusinessException(ErrorCode.UNAUTHORIZED, "연재를 이어가려면 로그인해 주세요.");
        WebtoonWork work = ledger.find(run).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (work.isTrashed() || work.isRemovedByAdmin() || !ledger.mayRead(run, user))
            throw new BusinessException(ErrorCode.NOT_FOUND);
        if (own && (!ledger.mayChange(run,user) || work.isHiddenByAdmin()))
            throw new BusinessException(ErrorCode.FORBIDDEN, "내가 만든 작품만 이어 만들 수 있어요.");
        return jobs.findByPublicId(work.getJobId()).orElseThrow(() ->
                new BusinessException(ErrorCode.INVALID_INPUT, "이 작품의 생성 기록을 찾지 못했어요."));
    }
    private String rootOf(WebtoonJob job) {
        return chapters.findById(job.getPublicId()).map(c -> c.rootJob).orElse(job.getPublicId());
    }
    private int number(WebtoonJob job) {
        return chapters.findById(job.getPublicId()).map(c -> c.episodeNumber).orElse(1);
    }
    private void lock(WebtoonJob job) {
        jobs.lockByPublicId(job.getPublicId()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
    private Map<String,Object> obj(String value) {
        if (value == null || value.isBlank()) return new LinkedHashMap<>();
        try { return json.readValue(value, new TypeReference<LinkedHashMap<String,Object>>() {}); }
        catch (Exception e) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, "작품 기록을 읽지 못했어요."); }
    }
    private static String str(Object value) { return value == null ? "" : value.toString(); }
    private Map<String,Object> memory(WebtoonJob source) {
        return chapters.findById(source.getPublicId()).map(c -> obj(c.memoryJson)).orElseGet(LinkedHashMap::new);
    }
    private String initialFixed(WebtoonJob source) {
        Map<String,Object> saved = memory(source);
        if (saved.containsKey("fixed_settings")) return str(saved.get("fixed_settings"));
        var input = obj(source.getInputJson());
        if(input.get("lorebook") instanceof Map<?,?> b && !b.isEmpty()) return String.join("\n", List.of(str(input.get("settings")), str(b.get("fixed_settings")))).trim();
        return String.join("\n", List.of(str(input.get("name")), str(input.get("character")), str(input.get("settings"))));
    }
    private String initialState(WebtoonJob source) {
        List<String> scenes = stories.scenesOf(source.getRunId());
        String end = String.join("\n\n", scenes.subList(Math.max(0, scenes.size()-2), scenes.size()));
        if (end.length() > MEMORY_MAX - 100) end = ""; // 긴 원문은 참고 기록에 그대로 제공한다.
        return "현재 시점: " + number(source) + "화 종료\n\n마지막 장면 기록 (확인하고 현재 상태로 정리해 주세요):\n" + end;
    }
    private WebtoonJob liveSuccessor(WebtoonJob source) {
        return chapters.findByRootJobOrderByEpisodeNumberAsc(rootOf(source)).stream()
            .filter(c -> c.episodeNumber > number(source))
            .map(c -> jobs.findByPublicId(c.jobId).orElse(null))
            .filter(j -> j != null && j.getStatus() != JobStatus.ERROR).findFirst().orElse(null);
    }
    private void mustBeLatest(WebtoonJob source) {
        if (source.getStatus() != JobStatus.DONE)
            throw new BusinessException(ErrorCode.INVALID_INPUT, "앞 화를 완성한 뒤 다음 화를 만들 수 있어요.");
        WebtoonJob next = liveSuccessor(source);
        if (next != null) {
            var work = next.getRunId() == null ? Optional.<WebtoonWork>empty() : ledger.find(next.getRunId());
            if (work.isPresent() && work.get().isRemovedByAdmin())
                throw new BusinessException(ErrorCode.FORBIDDEN,"다음 회차가 관리자에 의해 제한되어 있어요. 내 작품에서 처리 사유를 확인해 주세요.");
            if (work.isPresent() && work.get().isTrashed())
                throw new BusinessException(ErrorCode.INVALID_INPUT,"다음 회차가 휴지통에 있어요. 내 작품의 휴지통에서 복원한 뒤 이어가 주세요.");
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이미 이어 만든 회차가 있어요. 회차 목록에서 이어가 주세요.");
        }
    }
    @Transactional(readOnly=true)
    public Map<String,Object> draft(String run, Long user) {
        WebtoonJob source = source(run,user,true);
        mustBeLatest(source);
        SeriesDraft d = drafts.findById(source.getPublicId()).orElse(null);
        var out = new LinkedHashMap<String,Object>();
        out.put("episode", number(source)+1);
        out.put("fixed_settings", d == null ? initialFixed(source) : d.fixedSettings);
        out.put("progress_state", d == null ? initialState(source) : d.progressState);
        out.put("story", d == null ? "" : d.nextStory);
        out.put("revision", d == null ? 0 : d.revision);
        out.put("previous_state", str(memory(source).get("progress_state")));
        out.put("source_scenes", stories.scenesOf(run));
        out.put("source_run", run);
        out.put("credit_cost", WebtoonQuality.creditsOf(source.getQuality()));
        out.put("balance", gate.balanceOf(user));
        out.put("blocked", guard.whyBlocked(queue.reserved()));
        out.put("title", stories.chosenOf(run).map(s -> s.displayTitle()).orElse(""));
        return out;
    }
    static String checked(String value, int max, String label, boolean required) {
        String text = value == null ? "" : value.trim();
        if (text.length() > max || required && text.isBlank())
            throw new BusinessException(ErrorCode.INVALID_INPUT, label + "을(를) " + max + "자 이내로 적어 주세요.");
        return text;
    }
    private SeriesDraft save(WebtoonJob source, Form form, Long user) {
        var d = drafts.findById(source.getPublicId()).orElseGet(() -> new SeriesDraft(source.getPublicId()));
        if (form.revision() != d.revision)
            throw new BusinessException(ErrorCode.INVALID_INPUT, "다른 창에서 초안이 바뀌었어요. 다시 열어 최신 내용을 확인해 주세요.");
        d.ownerId=user;
        d.fixedSettings=checked(form.fixed_settings(),MEMORY_MAX,"고정 설정",false);
        d.progressState=checked(form.progress_state(),MEMORY_MAX,"진행 상태",false);
        d.nextStory=checked(form.story(),20000,"이번 화 이야기",false);
        d.revision++;
        return drafts.save(d);
    }
    @Transactional
    public Map<String,Object> saveDraft(String run, Long user, Form form) {
        var source=source(run,user,true); lock(source); mustBeLatest(source);
        return Map.of("revision",save(source,form,user).revision);
    }
    @Transactional
    public Map<String,Object> createNext(String run, Long user, Form form) {
        var source=source(run,user,true); lock(source);
        String token=checked(form.request_token(),64,"요청 번호",true);
        var existing=chapters.findByRootJobAndRequestToken(rootOf(source),token);
        if (existing.isPresent()) {
            if (!existing.get().sourceJob.equals(source.getPublicId())) throw new BusinessException(ErrorCode.INVALID_INPUT);
            return Map.of("id",existing.get().jobId);
        }
        mustBeLatest(source);
        if (!form.confirmed() || !form.agree_ip())
            throw new BusinessException(ErrorCode.INVALID_INPUT, "설정·진행 상태와 저작권을 확인해 주세요.");
        checked(form.progress_state(),MEMORY_MAX,"진행 상태",true);
        checked(form.story(),20000,"이번 화 이야기",true);
        String blocked=guard.whyBlocked(queue.reserved());
        if (blocked != null) throw new BusinessException(ErrorCode.INVALID_INPUT,blocked);
        int cost=WebtoonQuality.creditsOf(source.getQuality());
        gate.requireEnough(user,cost);
        // 시트가 없을 때 새 인물을 그리는 방식으로 조용히 대체하지 않는다.
        Path sourceDir=art.dir(run);
        if (!Files.isRegularFile(sourceDir.resolve("sheet.png")))
            throw new BusinessException(ErrorCode.INVALID_INPUT,"앞 화의 캐릭터 시트를 찾지 못했어요. 다음 화를 시작하지 않았습니다.");
        var d=save(source,form,user);
        var input=obj(source.getInputJson());
        var state=new LinkedHashMap<String,Object>();
        state.put("source_run",run); state.put("source_dir",sourceDir.toAbsolutePath().toString());
        state.put("episode_number",number(source)+1);
        state.put("fixed_settings",d.fixedSettings); state.put("progress_state",d.progressState);
        state.put("source_scenes",stories.scenesOf(run)); state.put("request",d.nextStory);
        state.put("revision",d.revision);
        Map<String,String> fields = new LinkedHashMap<>();
        if (input.get("fields") instanceof Map<?,?> savedFields)
            savedFields.forEach((k,v) -> fields.put(str(k),str(v)));
        var req=new JobService.CreateRequest(str(input.get("name")),str(input.get("character")),
                str(input.get("genre")),d.nextStory,source.getStyle(),fields,List.of(),List.of(),true,
                true,checked(form.uid(),64,"브라우저 번호",true),null,"",source.getQuality(),
                source.getLanguage(),"own",d.fixedSettings,"","", input.get("lorebook") instanceof Map<?,?> b ? json.convertValue(b,new TypeReference<Map<String,Object>>() {}) : null);
        String id=creator.create(req,user,req.uid(),null,state);
        try {
            var chapter = new SeriesChapter(id,rootOf(source),source.getPublicId(),number(source)+1,token,json.writeValueAsString(state));
            chapter.ownerId=user;
            chapters.save(chapter);
        }
        catch (java.io.IOException e) { throw new BusinessException(ErrorCode.INTERNAL_ERROR); }
        // 공용 크레딧 장부는 REQUIRES_NEW. 생성 트랜잭션이 실패하면 별도 보상한다.
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) billing.refund(user,id);
                    }
                });
        }
        billing.charge(user,cost,id,WebtoonQuality.labelOf(source.getQuality()));
        queue.remember(id,queue.ahead());
        if (!ledger.isPublic(run)) ledger.setPublicByJob(id,false);
        return Map.of("id",id);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> chapters(String run, Long user) {
        var source=source(run,user,false);
        String root = rootOf(source);
        List<Map<String,Object>> list=new ArrayList<>();
        jobs.findByPublicId(root).ifPresent(j -> addVisible(list,j,1,user));
        for (var c: chapters.findByRootJobOrderByEpisodeNumberAsc(root))
            jobs.findByPublicId(c.jobId).ifPresent(j -> addVisible(list,j,c.episodeNumber,user));
        return Map.of("episode",number(source),"chapters",list);
    }
    private void addVisible(List<Map<String,Object>> list, WebtoonJob job, int number, Long user) {
        if (job.getStatus()==JobStatus.ERROR) return;
        var work=job.getRunId()==null ? Optional.<WebtoonWork>empty() : ledger.find(job.getRunId());
        boolean owner=user!=null && (user.equals(job.getUserId()) || job.getRunId()!=null && ledger.mayChange(job.getRunId(),user));
        if (job.getStatus()==JobStatus.DONE && work.isEmpty()) return;
        if (work.isPresent() && (work.get().isTrashed() || work.get().isRemovedByAdmin())) return;
        if (!owner && (job.getStatus()!=JobStatus.DONE || work.isEmpty() || !work.get().isPublic())) return;
        var row=new LinkedHashMap<String,Object>();
        row.put("episode",number); row.put("run_id",job.getStatus()==JobStatus.DONE ? job.getRunId() : null);
        if (owner) row.put("job_id",job.getPublicId());
        row.put("status",job.getStatus().name());
        row.put("title", job.getRunId()==null ? "" : stories.chosenOf(job.getRunId()).map(s->s.displayTitle()).orElse(""));
        list.add(row);
    }
}
