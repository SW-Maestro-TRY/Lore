package com.lore.webtoon.community;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.Admins;
import com.lore.webtoon.work.WorkLedger;
import com.lore.webtoon.work.WebtoonWork;
import com.lore.webtoon.safety.SafetyGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class CommunityService {
    private final JdbcTemplate db;
    private final WorkLedger works;
    private final SeriesIdentity series;
    private final Admins admins;
    private final SafetyGuard safety;
    public CommunityService(JdbcTemplate db, WorkLedger works, SeriesIdentity series, Admins admins, SafetyGuard safety) {
        this.db=db; this.works=works; this.series=series; this.admins=admins; this.safety=safety;
    }
    static BusinessException missing() { return new BusinessException(ErrorCode.NOT_FOUND,"공개된 작품이나 내용을 찾을 수 없어요."); }
    static String text(String value,int max) {
        if(value==null || value.isBlank() || value.strip().length()>max)
            throw new BusinessException(ErrorCode.INVALID_INPUT,"1자부터 "+max+"자까지 입력해 주세요.");
        return value.strip();
    }
    public static Long login(Long me) {
        if(me==null) throw new BusinessException(ErrorCode.UNAUTHORIZED,"로그인한 뒤 이용해 주세요.");
        return me;
    }
    private WebtoonWork readable(String run) {
        WebtoonWork w=works.find(run).filter(x -> x.isPublic() && !x.isTrashed()).orElseThrow(CommunityService::missing);
        if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from webtoon_job where run_id=? and status='DONE')",Boolean.class,run))) throw missing();
        return w;
    }
    private SeriesIdentity.Identity identity(String run) { return series.of(readable(run).getJobId()); }
    public Map<String,Object> state(String run,Long me) {
        var id=identity(run);
        long count=db.queryForObject("select count(*) from webtoon_subscription where root_job=?",Long.class,id.rootJob());
        boolean following=me!=null && Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from webtoon_subscription where root_job=? and user_id=?)",Boolean.class,id.rootJob(),me));
        return Map.of("subscribed",following,"subscribers",count,"episode",id.episode());
    }
    @Transactional
    public Map<String,Object> subscribe(String run,Long me,boolean on) {
        login(me); var id=identity(run);
        if(on) db.update("insert into webtoon_subscription(root_job,user_id) values (?,?) on conflict do nothing",id.rootJob(),me);
        else db.update("delete from webtoon_subscription where root_job=? and user_id=?",id.rootJob(),me);
        return state(run,me);
    }
    @Transactional
    public void unsubscribeRoot(String root,Long me) {
        db.update("delete from webtoon_subscription where root_job=? and user_id=?",root,login(me));
    }
    public List<Map<String,Object>> subscriptions(Long me) {
        return db.queryForList("""
            select s.root_job,j.run_id,s.created_at,
              coalesce(w.is_public,false) and w.deleted_at is null as available
            from webtoon_subscription s left join webtoon_job j on j.public_id=s.root_job
            left join lateral (select is_public,deleted_at from webtoon_work where run_id=j.run_id order by id limit 1) w on true
            where s.user_id=? order by s.created_at desc limit 100
            """,login(me));
    }
    public List<Map<String,Object>> comments(String run,Long me,long before) {
        readable(run);
        return db.queryForList("""
            select id,body,created_at,updated_at,('독자 ' || user_id) as author,
              user_id=? as mine from webtoon_comment
            where run_id=? and not deleted and not hidden and id<? order by id desc limit 30
            """,me==null?-1:me,run,before<=0?Long.MAX_VALUE:before);
    }
    @Transactional
    public Map<String,Object> addComment(String run,Long me,String body) {
        login(me); var work=readable(run); body=text(body,1000);
        // 계정 잠금은 동시 요청에도 10초 간격을 지킨다.
        db.queryForObject("select id from users where id=? for update",Long.class,me);
        if(Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from webtoon_comment where user_id=? and created_at>now()-interval '10 seconds')",Boolean.class,me)))
            throw new BusinessException(ErrorCode.INVALID_INPUT,"잠깐만요. 10초 뒤에 댓글을 남겨 주세요.");
        safety.checkText("episode-comment",body);
        Long id=db.queryForObject("insert into webtoon_comment(run_id,user_id,body) values (?,?,?) returning id",Long.class,run,me,body);
        if(work.getUserId()!=null && !me.equals(work.getUserId()))
            notice(work.getUserId(),"COMMENT","comment:"+id,run,null,"작품에 새 댓글이 달렸어요.");
        return Map.of("id",id);
    }
    @Transactional
    public void editComment(long id,Long me,String body) {
        login(me); body=text(body,1000);
        var rows=db.queryForList("select run_id from webtoon_comment where id=? and user_id=? and not deleted and not hidden for update",id,me);
        if(rows.isEmpty()) throw missing(); readable((String)rows.getFirst().get("run_id"));
        safety.checkText("episode-comment",body);
        db.update("update webtoon_comment set body=?,updated_at=now() where id=?",body,id);
    }
    @Transactional
    public void deleteComment(long id,Long me) {
        login(me);
        int n=db.update("update webtoon_comment set deleted=true,body='삭제된 댓글',updated_at=now() where id=? and (user_id=? or ?)",id,me,admins.isAdmin(me));
        if(n==0) throw missing();
        db.update("delete from webtoon_community_notice where event_key=?","comment:"+id);
    }
    public List<Map<String,Object>> notices(Long me) {
        return db.queryForList("""
            select n.id,n.kind,n.run_id,n.character_id,n.message,n.created_at,n.read_at
            from webtoon_community_notice n where n.user_id=?
            and exists(select 1 from webtoon_work w where w.run_id=n.run_id and w.is_public and w.deleted_at is null)
            order by n.id desc limit 100
            """,login(me));
    }
    public void readNotice(Long me,long id) {
        db.update("update webtoon_community_notice set read_at=coalesce(read_at,now()) where user_id=? and id=?",login(me),id);
    }
    void notice(Long user,String kind,String event,String run,String character,String message) {
        db.update("insert into webtoon_community_notice(user_id,kind,event_key,run_id,character_id,message) values (?,?,?,?,?,?) on conflict do nothing",user,kind,event,run,character,message);
    }
    @Transactional
    public void report(Long me,String kind,String target,String reason) {
        login(me); reason=text(reason,500);
        if("comment".equals(kind)) {
            var rows=db.queryForList("select run_id from webtoon_comment where id::text=? and not deleted and not hidden",target);
            if(rows.isEmpty()) throw missing(); readable((String)rows.getFirst().get("run_id"));
        } else if("character".equals(kind)) {
            if(!Boolean.TRUE.equals(db.queryForObject("select exists(select 1 from webtoon_character where public_id=? and community_public and not community_blocked)",Boolean.class,target))) throw missing();
        } else throw missing();
        db.update("insert into webtoon_community_report(kind,target,user_id,reason) values (?,?,?,?) on conflict do nothing",kind,target,me,reason);
    }
    public void requireAdmin(Long me) {
        if(!admins.isAdmin(login(me))) throw new BusinessException(ErrorCode.FORBIDDEN,"관리자만 이용할 수 있어요.");
    }
    public List<Map<String,Object>> reports(Long me) {
        requireAdmin(me); return db.queryForList("""
          select r.*,case when r.kind='comment' then c.body else ch.name end as content
          from webtoon_community_report r
          left join webtoon_comment c on r.kind='comment' and c.id::text=r.target
          left join webtoon_character ch on r.kind='character' and ch.public_id=r.target
          where r.resolved_at is null order by r.id limit 100
          """);
    }
    @Transactional
    public void resolve(Long me,long id,boolean hide) {
        requireAdmin(me);
        var rows=db.queryForList("select * from webtoon_community_report where id=? for update",id);
        if(rows.isEmpty()) throw missing(); var r=rows.getFirst();
        if(hide) {
            if("comment".equals(r.get("kind"))) {
                db.update("update webtoon_comment set hidden=true where id::text=?",r.get("target"));
                db.update("delete from webtoon_community_notice where event_key=?","comment:"+r.get("target"));
            } else db.update("update webtoon_character set community_public=false,community_blocked=true where public_id=?",r.get("target"));
        }
        db.update("update webtoon_community_report set resolved_at=now() where kind=? and target=?",r.get("kind"),r.get("target"));
    }
    public List<Map<String,Object>> attribution(String run) {
        readable(run);
        return db.queryForList("""
           select u.character_id,u.character_name,('창작자 ' || u.creator_id) as creator,
             coalesce(c.community_public and not c.community_blocked,false) as available
           from webtoon_character_use u join webtoon_job j on j.public_id=u.job_id
           left join webtoon_character c on c.public_id=u.character_id where j.run_id=?
           """,run);
    }
}
