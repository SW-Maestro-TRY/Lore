package com.lore.webtoon.community;
import com.lore.webtoon.character.WebtoonCharacter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
@Service
public class CharacterUseRecorder {
    private final JdbcTemplate db;
    public CharacterUseRecorder(JdbcTemplate db) { this.db=db; }
    public void record(String job,WebtoonCharacter c) {
        var rows=db.queryForList("select public_id from webtoon_character where public_id=? and community_public and not community_blocked for update",c.getPublicId());
        if(rows.isEmpty()) throw CommunityService.missing();
        db.update("insert into webtoon_character_use(job_id,character_id,creator_id,character_name) values (?,?,?,?) on conflict do nothing",job,c.getPublicId(),c.getOwnerId(),c.getName());
    }
}
