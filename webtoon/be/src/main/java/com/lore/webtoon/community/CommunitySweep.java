package com.lore.webtoon.community;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;

@Component
public class CommunitySweep {
    private final JdbcTemplate db;
    private final CommunityPublications publications;
    public CommunitySweep(JdbcTemplate db, CommunityPublications publications) { this.db=db; this.publications=publications; }
    @Scheduled(fixedDelayString="${lore.webtoon.community.poll-ms:15000}", initialDelay=15000)
    public void sweep() {
        for(String id:db.queryForList("""
          select j.public_id from webtoon_job j where j.status='DONE'
          and not exists(select 1 from webtoon_community_publication p where p.job_id=j.public_id)
          and exists(select 1 from webtoon_work w where w.run_id=j.run_id and w.is_public and w.deleted_at is null)
          order by j.id limit 100
          """,String.class)) {
            try { publications.publish(id); }
            catch(RuntimeException e) { LoggerFactory.getLogger(getClass()).error("커뮤니티 소식 발행 실패 job={}",id,e); }
        }
    }
}
