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
    private final SeriesDigestService digests;
    private final ObjectMapper json = new ObjectMapper();

    public SeriesService(SeriesDraftRepository drafts, SeriesChapterRepository chapters,
            WebtoonJobRepository jobs, JobService creator, WorkLedger ledger, StoryStore stories,
            RunArt art, JobQueue queue, SpendGuard guard, CreditGate gate, SeriesBilling billing,
            SeriesDigestService digests) {
        this.drafts=drafts; this.chapters=chapters; this.jobs=jobs; this.creator=creator;
        this.ledger=ledger; this.stories=stories; this.art=art; this.queue=queue;
        this.guard=guard; this.gate=gate; this.billing=billing; this.digests=digests;
    }
    /** agree_ip 는 옛 화면만 보낸다 — 다음 화는 1화의 권리 확인을 잇는다(createNext). 안 보내도 되게 Boolean. */
    public record Form(String fixed_settings, String progress_state, String story, long revision,
                       boolean confirmed, Boolean agree_ip, String request_token, String uid,
                       List<Map<String,Object>> entries, List<Map<String,Object>> progress) {}

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
    /**
     * 앞 화까지 이어 온 2층 로어북 — 고정 로어북(entries: 키워드 항목, 세계 항목은 항상 켜짐)과 진행 상태(progress).
     * 1화에 붙인 설정집이거나 앞 회차에서 넘긴 연재 설정집이다.
     */
    private Map<String,Object> sourceBook(WebtoonJob source) {
        var out = new LinkedHashMap<String,Object>();
        out.put("entries", new ArrayList<>()); out.put("progress", new ArrayList<>());
        var input = obj(source.getInputJson());
        if (!(input.get("lorebook") instanceof Map<?,?> raw) || raw.isEmpty()) return out;
        try {
            var book = com.lore.webtoon.lorebook.LoreBook.clean(json.convertValue(raw, new TypeReference<Map<String,Object>>() {}), false);
            out.put("entries", new ArrayList<>((List<?>) book.getOrDefault("entries", List.of())));
            out.put("progress", new ArrayList<>((List<?>) book.getOrDefault("progress", List.of())));
        } catch (RuntimeException e) { /* 읽지 못한 옛 설정집은 빈 것으로 본다 */ }
        return out;
    }
    /** 정리에 넘길 「지금까지 확정된 것」. 로어북도 앞 회차도 없는 1화는 고정 설정을 비워 정리가 새로 쓰게 한다. */
    private Map<String,Object> previous(WebtoonJob source) {
        Map<String,Object> saved = memory(source);
        var input = obj(source.getInputJson());
        boolean book = input.get("lorebook") instanceof Map<?,?> b && !b.isEmpty();
        var out = new LinkedHashMap<String,Object>();
        out.put("fixed_settings", saved.containsKey("fixed_settings") || book ? initialFixed(source) : "");
        out.put("progress_state", str(saved.get("progress_state")));
        out.putAll(sourceBook(source));
        return out;
    }
    private Map<String,Object> digestPacket(WebtoonJob source, String run) {
        var packet = new LinkedHashMap<String,Object>();
        packet.put("source_dir", art.dir(run).toAbsolutePath().toString());
        packet.put("episode_number", number(source));
        packet.put("source_scenes", stories.scenesOf(run));
        packet.put("previous", previous(source));
        return packet;
    }
    /** 앞 화 정리를 시작한다. 크레딧은 받지 않는다 — 다음 화 크레딧에 들어 있다. */
    @Transactional
    public Map<String,Object> startDigest(String run, Long user) {
        var source = source(run,user,true);
        mustBeLatest(source);
        if (!Files.isRegularFile(art.dir(run).resolve("directions.json")) && stories.scenesOf(run).isEmpty())
            throw new BusinessException(ErrorCode.INVALID_INPUT, "앞 화의 이야기 기록을 찾지 못해 정리할 수 없어요. 직접 적어 주세요.");
        return digests.start(source.getPublicId(), user, digestPacket(source, run),
                com.lore.webtoon.job.WebtoonLanguage.normalize(source.getLanguage()));
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
        var digest = d == null ? digests.result(source.getPublicId()).orElse(null) : null;
        var out = new LinkedHashMap<String,Object>();
        out.put("episode", number(source)+1);
        if (d != null) {
            out.put("fixed_settings", d.fixedSettings);
            out.put("progress_state", d.progressState);
            out.putAll(carries(d) ? sourceBook(source) : book(d.entriesJson));
        } else if (digest != null) {
            // 앞 화를 읽고 갱신한 2층 로어북으로 채운다. 사람은 틀린 줄만 뺀다(실험과 같다).
            var carried = previous(source);
            out.put("fixed_settings", str(carried.get("fixed_settings")));
            out.put("progress_state", "");
            out.put("entries", digest.getOrDefault("entries", List.of()));
            out.put("progress", digest.getOrDefault("progress", List.of()));
        } else {
            out.put("fixed_settings", initialFixed(source));
            out.put("progress_state", initialState(source));
            out.putAll(sourceBook(source));
        }
        out.put("digest", d == null ? digests.view(source.getPublicId()) : Map.of("status","SAVED"));
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
    /** 마이페이지 「만드는 중」 — 써 두고 아직 만들지 않은 다음 화 초안. 다음 화를 이미 만들었거나 앞 화가
     *  휴지통·관리자 제한이면 뺀다. 표지는 화면이 내 작품 목록에서 찾는다. */
    @Transactional(readOnly=true)
    public List<Map<String,Object>> myDrafts(Long user) {
        if (user == null) return List.of();
        var out = new ArrayList<Map<String,Object>>();
        for (SeriesDraft d : drafts.findByOwnerId(user)) {
            WebtoonJob job = jobs.findByPublicId(d.sourceJob).orElse(null);
            if (job == null || job.getRunId() == null || job.getStatus() != JobStatus.DONE || liveSuccessor(job) != null) continue;
            var work = ledger.find(job.getRunId());
            if (work.isEmpty() || work.get().isTrashed() || work.get().isRemovedByAdmin()) continue;
            var row = new LinkedHashMap<String,Object>();
            row.put("run", job.getRunId());
            row.put("episode", number(job) + 1);
            row.put("title", stories.chosenOf(job.getRunId()).map(s -> s.displayTitle()).orElse(""));
            row.put("story", d.nextStory.length() > 80 ? d.nextStory.substring(0, 80) : d.nextStory);
            out.add(row);
        }
        return out;
    }
    /** 항목을 따로 고르지 않은 초안 — 앞 화의 설정집을 그대로 넘긴다. */
    static final String CARRY = "carry";
    private static boolean carries(SeriesDraft d) { return d.entriesJson == null || CARRY.equals(d.entriesJson); }
    /** 저장한 초안의 {entries, progress}. */
    private Map<String,Object> book(String value) {
        var out = new LinkedHashMap<String,Object>();
        out.put("entries", new ArrayList<>()); out.put("progress", new ArrayList<>());
        try {
            var got = json.readValue(value, new TypeReference<Map<String,Object>>() {});
            if (got.get("entries") instanceof List<?> e) out.put("entries", e);
            if (got.get("progress") instanceof List<?> p) out.put("progress", p);
        } catch (Exception e) { /* 빈 것으로 본다 */ }
        return out;
    }
    /** 사람이 남긴 항목·진행 상태. 다음 화 생성에 들어가는 모양(LoreBook)으로 거른다 — 크기 제한도 거기서 본다. */
    private Map<String,Object> bookOf(Form form) {
        var raw = new LinkedHashMap<String,Object>();
        raw.put("name","연재 설정집"); raw.put("confirmed", true);
        raw.put("entries", form.entries() == null ? List.of() : form.entries());
        raw.put("progress", form.progress() == null ? List.of() : form.progress());
        var clean = com.lore.webtoon.lorebook.LoreBook.clean(raw, true);
        var out = new LinkedHashMap<String,Object>();
        out.put("entries", clean.get("entries")); out.put("progress", clean.get("progress"));
        return out;
    }
    private static String progressText(Object rows) {
        if (!(rows instanceof List<?> list)) return "";
        var out = new ArrayList<String>();
        for (Object row : list) if (row instanceof Map<?,?> p) out.add("- " + str(p.get("title")) + ": " + str(p.get("text")));
        return String.join("\n", out);
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
        // 항목을 안 보낸 요청(옛 화면)은 앞 화의 설정집을 그대로 넘긴다.
        try {
            if (form.entries()==null && form.progress()==null) d.entriesJson=CARRY;
            else {
                var kept = bookOf(form);
                d.entriesJson=json.writeValueAsString(kept);
                // 진행 상태를 줄로 받았으면 글 칸은 그 줄로 채워 둔다 — 회차 기록(앞 화 상태 참고)에 남는다.
                if (d.progressState.isBlank()) d.progressState=progressText(kept.get("progress"));
            }
        }
        catch (java.io.IOException e) { throw new BusinessException(ErrorCode.INTERNAL_ERROR); }
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
        // 권리 확인은 1화를 만들 때(만들기 화면의 저작권 확인) 같은 캐릭터·설정으로 받았다. 다음 화는 그 확인을
        // 잇는다 — 새로 올리는 사진·파일이 없고, 이번 화에 적는 글은 사용자가 직접 쓴 것이다.
        // 이번 화 내용은 비워도 된다 — 비우면 하네스가 앞 화가 남긴 일에서 다음 사건을 정한다(continuation.story_input).
        checked(form.story(),20000,"이번 화 이야기",false);
        String blocked=guard.whyBlocked(queue.reserved());
        if (blocked != null) throw new BusinessException(ErrorCode.INVALID_INPUT,blocked);
        int cost=WebtoonQuality.creditsOf(source.getQuality());
        gate.requireEnough(user,cost);
        // 시트가 없을 때 새 인물을 그리는 방식으로 조용히 대체하지 않는다.
        Path sourceDir=art.dir(run);
        if (!Files.isRegularFile(sourceDir.resolve("sheet.png")))
            throw new BusinessException(ErrorCode.INVALID_INPUT,"앞 화의 캐릭터 시트를 찾지 못했어요. 다음 화를 시작하지 않았습니다.");
        var d=save(source,form,user);
        if (d.progressState.isBlank())
            throw new BusinessException(ErrorCode.INVALID_INPUT, "앞 화가 끝난 진행 상태가 비어 있어요. 앞 화 정리를 다시 해 주세요.");
        var input=obj(source.getInputJson());
        boolean progressInBook = !carries(d) && !progressText(book(d.entriesJson).get("progress")).isBlank();
        var state=new LinkedHashMap<String,Object>();
        state.put("source_run",run); state.put("source_dir",sourceDir.toAbsolutePath().toString());
        state.put("episode_number",number(source)+1);
        // 진행 상태가 설정집 줄로 가면 하네스가 설정집에서 넣는다 — 같은 것을 두 번 넣지 않는다.
        state.put("fixed_settings",d.fixedSettings); state.put("progress_state",progressInBook ? "" : d.progressState);
        state.put("source_scenes",stories.scenesOf(run)); state.put("request",d.nextStory);
        state.put("revision",d.revision);
        Map<String,String> fields = new LinkedHashMap<>();
        if (input.get("fields") instanceof Map<?,?> savedFields)
            savedFields.forEach((k,v) -> fields.put(str(k),str(v)));
        var req=new JobService.CreateRequest(str(input.get("name")),str(input.get("character")),
                str(input.get("genre")),d.nextStory,source.getStyle(),fields,List.of(),List.of(),true,
                true,checked(form.uid(),64,"브라우저 번호",true),null,"",source.getQuality(),
                source.getLanguage(),"own",d.fixedSettings,"","", seriesBook(source, d));
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
    /**
     * 다음 화에 붙일 설정집. 사람이 남긴 항목이 다음 화에 들어가고, 그 화의 입력에 남아 그다음 화로
     * 이어진다(이 회차가 다음 회차의 앞 화가 되면 sourceBook 이 여기서 읽는다).
     */
    private Map<String,Object> seriesBook(WebtoonJob source, SeriesDraft d) {
        var input = obj(source.getInputJson());
        Map<String,Object> carried = input.get("lorebook") instanceof Map<?,?> b && !b.isEmpty()
                ? json.convertValue(b, new TypeReference<Map<String,Object>>() {}) : null;
        if (carries(d)) return carried;
        var kept = book(d.entriesJson);
        if (((List<?>) kept.get("entries")).isEmpty() && ((List<?>) kept.get("progress")).isEmpty() && carried == null) return null;
        var book = new LinkedHashMap<String,Object>();
        book.put("name", stories.chosenOf(source.getRunId()).map(s -> s.displayTitle()).filter(t -> !t.isBlank()).orElse("연재") + " 설정집");
        book.put("source", "series");
        book.put("fixed_settings", d.fixedSettings.length() > 8000 ? d.fixedSettings.substring(0, 8000) : d.fixedSettings);
        book.put("progress_state", "");
        book.put("entries", kept.get("entries"));
        book.put("progress", kept.get("progress"));
        book.put("confirmed", true);
        return book;
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
