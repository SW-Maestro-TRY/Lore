package com.lore.webtoon.community;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** fixture.sql을 넣은 localhost 전용 DB에서만 실행한다. */
@EnabledIfEnvironmentVariable(named="COMMUNITY_TEST_DB",matches="lore_community_[a-zA-Z0-9_]+")
class CommunityCleanupPostgresTest {
    @Test void permanentDeletionRemovesOtherReadersContentToo() {
        String name=System.getenv("COMMUNITY_TEST_DB");
        var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://localhost:5432/"+name,"mint",""));
        db.update("insert into webtoon_job(public_id,browser_uid,checkpoints,created_at,updated_at,stage,status,style,run_id,user_id) values ('cleanup-job','cleanup',false,now(),now(),'BIND','DONE','frost','cleanup-run',91001)");
        try {
            Long comment=db.queryForObject("insert into webtoon_comment(run_id,user_id,body) values ('cleanup-run',91002,'남이 남긴 댓글') returning id",Long.class);
            db.update("insert into webtoon_subscription(root_job,user_id) values ('cleanup-job',91002)");
            db.update("insert into webtoon_community_notice(user_id,kind,event_key,run_id,message) values (91002,'EPISODE','cleanup','cleanup-run','새 화')");
            db.update("insert into webtoon_community_report(kind,target,user_id,reason) values ('comment',?,91003,'검토')",comment.toString());
            db.update("insert into webtoon_character_use(job_id,character_id,creator_id,character_name) values ('cleanup-job','community-harin',91001,'하린')");
            db.update("insert into webtoon_community_publication(job_id) values ('cleanup-job')");
            assertThat(new CommunityCleanup(db).run("cleanup-run")).isEqualTo(6);
            assertThat(db.queryForObject("select count(*) from webtoon_comment where run_id='cleanup-run'",Long.class)).isZero();
            assertThat(db.queryForObject("select count(*) from webtoon_subscription where root_job='cleanup-job'",Long.class)).isZero();
            assertThat(db.queryForObject("select count(*) from webtoon_community_notice where run_id='cleanup-run'",Long.class)).isZero();
        } finally {
            new CommunityCleanup(db).run("cleanup-run");
            db.update("delete from webtoon_job where public_id='cleanup-job'");
        }
    }
}
