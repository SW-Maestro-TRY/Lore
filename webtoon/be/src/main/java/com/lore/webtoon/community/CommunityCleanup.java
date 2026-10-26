package com.lore.webtoon.community;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 영구 삭제 전, 타인이 남긴 댓글·구독·알림까지 함께 지운다. */
@Component
public class CommunityCleanup {
    private final JdbcTemplate db;
    public CommunityCleanup(JdbcTemplate db) { this.db=db; }
    @Transactional
    public int run(String run) {
        int n=db.update("delete from webtoon_community_report where kind='comment' and target in (select id::text from webtoon_comment where run_id=?)",run);
        n+=db.update("delete from webtoon_comment where run_id=?",run);
        n+=db.update("delete from webtoon_community_notice where run_id=?",run);
        n+=db.update("delete from webtoon_subscription where root_job in (select public_id from webtoon_job where run_id=?)",run);
        n+=db.update("delete from webtoon_character_use where job_id in (select public_id from webtoon_job where run_id=?)",run);
        n+=db.update("delete from webtoon_community_publication where job_id in (select public_id from webtoon_job where run_id=?)",run);
        return n;
    }
    public int character(String id) {
        return db.update("delete from webtoon_community_report where kind='character' and target=?",id);
    }
    @Transactional
    public int user(Long user) {
        int n=db.update("delete from webtoon_community_report where user_id=? or (kind='character' and target in (select public_id from webtoon_character where owner_id=?)) or (kind='comment' and target in (select id::text from webtoon_comment where user_id=?))",user,user,user);
        n+=db.update("delete from webtoon_community_notice where user_id=? or event_key in (select 'comment:' || id from webtoon_comment where user_id=?)",user,user);
        n+=db.update("delete from webtoon_comment where user_id=?",user);
        n+=db.update("delete from webtoon_subscription where user_id=?",user);
        n+=db.update("update webtoon_character_use set creator_id=null where creator_id=?",user);
        return n;
    }
}
