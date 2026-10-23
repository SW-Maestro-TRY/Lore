package com.lore.webtoon.community;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** DB outbox: 공개·완료된 회차만 발행한다. 재기동/공개 전환/다중 서버에도 한 번만. */
@Service
public class CommunityPublications {
    private final JdbcTemplate db;
    private final SeriesIdentity series;
    private final CommunityService community;
    public CommunityPublications(JdbcTemplate db, SeriesIdentity series, CommunityService community) {
        this.db=db; this.series=series; this.community=community;
    }
    @Transactional
    public void publish(String jobId) {
        var jobs=db.queryForList("""
          select j.public_id,j.run_id,j.user_id from webtoon_job j
          where j.public_id=? and j.status='DONE'
          and exists(select 1 from webtoon_work w where w.run_id=j.run_id and w.is_public and w.deleted_at is null)
          """,jobId);
        if(jobs.isEmpty()) return;
        if(db.update("insert into webtoon_community_publication(job_id) values (?) on conflict do nothing",jobId)==0) return;
        var job=jobs.getFirst(); String run=(String)job.get("run_id");
        var identity=series.of(jobId);
        if(identity.episode()>1) {
            for(Long user:db.queryForList("select user_id from webtoon_subscription where root_job=?",Long.class,identity.rootJob())) {
                if(!user.equals(job.get("user_id"))) community.notice(user,"EPISODE","episode:"+jobId,run,null,identity.episode()+"화가 공개됐어요. 기다리던 이야기를 만나 보세요.");
            }
        }
        var uses=db.queryForList("select * from webtoon_character_use where job_id=?",jobId);
        for(var use:uses) {
            Long creator=(Long)use.get("creator_id");
            if(creator!=null && !creator.equals(job.get("user_id")))
                community.notice(creator,"CHARACTER_USE","character:"+jobId,run,(String)use.get("character_id"),"내 캐릭터 ‘"+use.get("character_name")+"’을 사용한 새 작품이 공개됐어요.");
        }
    }
}
