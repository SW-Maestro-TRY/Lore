package com.lore.webtoon.art;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DB <b>어디에든</b> 적혀 있는 웹툰 그림 주소를 모은다.
 *
 * <h2>왜 표를 정해 두지 않고 전부 훑나</h2>
 *
 * 그림 주소를 들고 있는 칸은 웹툰 표 몇 개만이 아니다. 다른 서비스 표, JSON 칸, 기록 칸에도
 * 들어갈 수 있고, 앞으로 생기는 표도 있다. 정해 둔 목록에서 하나만 빠져도 그 표가 가리키는
 * <b>살아 있는 그림을 지우게 된다.</b> 그래서 이 점검은 목록을 믿지 않고, 지금 DB 의 모든
 * 글자 칸을 훑어 주소처럼 생긴 것을 전부 「쓰는 중」으로 친다. 남길 것을 지우는 것보다
 * 지울 것을 남기는 쪽으로 틀리게 한다.
 *
 * 관리자가 누를 때만 돈다. 표를 통째로 훑으므로 매일 도는 정리에는 쓰지 않는다
 * ({@link ArtDiscardSweep} 은 웹툰 표만 다시 본다).
 */
@Component
public class ArtReferences {

    /** 주소 하나. 주소 뒤에 붙는 따옴표·쉼표·물음표(?w=320) 앞에서 끊긴다. */
    static final Pattern KEY = Pattern.compile("(?:images|private)/webtoon/[A-Za-z0-9._/-]+");

    private final JdbcTemplate db;

    public ArtReferences(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * 모든 표의 글자 칸(문자열 · JSON · 배열)에서 웹툰 그림 주소를 모은다.
     *
     * 한 칸이라도 못 읽으면 예외를 그대로 올린다 — 일부만 훑고 「안 쓴다」고 판단하면
     * 못 훑은 칸이 가리키는 그림이 지워진다.
     */
    @Transactional(readOnly = true)
    public Set<String> everywhere() {
        List<Map<String, Object>> columns = db.queryForList("""
                select c.table_name, c.column_name
                  from information_schema.columns c
                  join information_schema.tables t
                    on t.table_schema = c.table_schema and t.table_name = c.table_name
                 where c.table_schema = current_schema()
                   and t.table_type = 'BASE TABLE'
                   and c.data_type in ('character varying', 'character', 'text', 'json', 'jsonb', 'ARRAY')
                   and c.table_name not in ('webtoon_art_discard', 'flyway_schema_history')
                """);
        Set<String> found = new HashSet<>();
        for (Map<String, Object> one : columns) {
            String table = quoted(String.valueOf(one.get("table_name")));
            String column = quoted(String.valueOf(one.get("column_name")));
            db.query("select " + column + "::text from " + table
                            + " where " + column + "::text like '%images/webtoon/%'"
                            + " or " + column + "::text like '%private/webtoon/%'",
                    rs -> {
                        collect(rs.getString(1), found);
                    });
        }
        return found;
    }

    /**
     * 글 하나에서 주소를 뽑아 담는다.
     *
     * 문장 끝의 마침표처럼 주소 뒤에 붙은 기호까지 잡힐 수 있어서, 끝의 {@code . _ / -} 를 뗀
     * 것도 같이 담는다. 실제 키가 어느 쪽이든 「쓰는 중」으로 남는다.
     */
    static void collect(String text, Set<String> into) {
        if (text == null) {
            return;
        }
        Matcher m = KEY.matcher(text);
        while (m.find()) {
            String key = m.group();
            into.add(key);
            into.add(key.replaceAll("[._/-]+$", ""));
        }
    }

    /** 표·칸 이름을 따옴표로 감싼다. 이름은 information_schema 에서 온 것이지만 그래도 감싼다. */
    static String quoted(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }
}
