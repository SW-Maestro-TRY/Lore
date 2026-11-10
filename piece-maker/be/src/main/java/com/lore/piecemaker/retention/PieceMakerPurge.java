package com.lore.piecemaker.retention;

import com.lore.common.retention.UserDataPurge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 계정 파기 때 Piece Maker 가설·카드와 이 서비스의 진단 이벤트만 지운다. */
@Component
public class PieceMakerPurge implements UserDataPurge {
    private final JdbcTemplate jdbc;

    public PieceMakerPurge(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String domain() {
        return "piece-maker";
    }

    @Override
    @Transactional
    public int purge(Long userId) {
        // 카드 사본은 가설 삭제에, F12·F13은 이후 users 삭제에 FK로 함께 지워진다.
        // 공통 계정 연결표에는 서비스 구분이 없다. 다른 서비스 이벤트와 함께 그대로 둔다.
        // LIKE의 '_' 와일드카드로 비슷한 이름까지 삭제하지 않도록 정확한 접두사를 비교한다.
        // 피드백은 별도 보존 정책이 있으므로 이 계정 파기 보완에서 변경하지 않는다.
        int rows = jdbc.update("delete from hypotheses where user_id = ?", userId);
        return rows + jdbc.update("""
                delete from zzal_event
                where user_id = ? and left(name, length('piece_maker_')) = 'piece_maker_'
                """, userId);
    }
}
