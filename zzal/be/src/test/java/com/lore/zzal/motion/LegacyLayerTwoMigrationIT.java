package com.lore.zzal.motion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import static org.assertj.core.api.Assertions.assertThat;

/** Real PostgreSQL, transaction-local temporary tables; never alters another database schema. */
@EnabledIfEnvironmentVariable(named = "LORE_TEST_DB_URL", matches = ".*_test$")
class LegacyLayerTwoMigrationIT {
    @Test void exactMigrationPreservesUnionFreezesCutoverAndDefaultsNewPetsToZero() throws Exception {
        String url = System.getenv("LORE_TEST_DB_URL");
        try (var db = DriverManager.getConnection(url, System.getenv("LORE_TEST_DB_USERNAME"),
                System.getenv("LORE_TEST_DB_PASSWORD"))) {
            db.setAutoCommit(false);
            try (var sql = db.createStatement()) {
                sql.execute("CREATE TEMP TABLE zzal_pet (id bigint primary key, layer2_status varchar(20), feeds int default 0, snacks int default 0, cleans int default 0, bath_count int default 0, chat_answers int default 0, pets int default 0, game_starts int default 0, wakes int default 0) ON COMMIT DROP");
                sql.execute("CREATE TEMP TABLE zzal_motion (pet_id bigint, seq int, unlocked_at timestamptz) ON COMMIT DROP");
                sql.execute("INSERT INTO zzal_pet(id,layer2_status) SELECT n,'READY' FROM generate_series(1,12)n");
                String[] columns = {"feeds", "snacks", "cleans", "bath_count", "chat_answers", "pets", "game_starts", "wakes"};
                int[] thresholds = {9,9,13,3,4,4,4,4};
                for (int i=0;i<8;i++) sql.execute("UPDATE zzal_pet SET "+columns[i]+"="+thresholds[i]+" WHERE id="+(i+1));
                sql.execute("UPDATE zzal_pet SET feeds=8,snacks=8,cleans=12,bath_count=2,chat_answers=3,pets=3,game_starts=3,wakes=3 WHERE id=9");
                sql.execute("UPDATE zzal_pet SET layer2_status='PENDING',feeds=99 WHERE id=10");
                sql.execute("INSERT INTO zzal_motion VALUES(10,16,now()),(10,16,now()),(10,8,now()),(11,9,now()),(11,10,null)");
                sql.execute("UPDATE zzal_pet SET feeds=9 WHERE id=12");
                sql.execute("INSERT INTO zzal_motion VALUES(12,10,now())");
                sql.execute(Files.readString(Path.of("apps/api/src/main/resources/db/migration/V20261016_2280__zzal_retire_layer2_ownership.sql")));
                try (var rows=sql.executeQuery("SELECT id,legacy_layer2_owned_mask FROM zzal_pet ORDER BY id")) {
                    int[] expected={1,2,4,8,16,32,64,128,0,128,1,3};int i=0;
                    while(rows.next()) assertThat(rows.getInt(2)).as("pet %s",rows.getLong(1)).isEqualTo(expected[i++]);
                    assertThat(i).isEqualTo(expected.length);
                }
                sql.execute("UPDATE zzal_pet SET feeds=99,snacks=99 WHERE id=9");
                sql.execute("INSERT INTO zzal_pet(id,layer2_status,feeds) VALUES(13,'READY',999)");
                try(var rows=sql.executeQuery("SELECT legacy_layer2_owned_mask FROM zzal_pet WHERE id IN(9,13)")) {
                    while(rows.next()) assertThat(rows.getInt(1)).isZero();
                }
                // Transaction savepoint proves the mask range constraint without aborting the fixture.
                var point=db.setSavepoint();boolean rejected=false;
                try{sql.execute("UPDATE zzal_pet SET legacy_layer2_owned_mask=256 WHERE id=1");}
                catch(java.sql.SQLException expected){rejected=true;db.rollback(point);}
                assertThat(rejected).isTrue();
            } finally { db.rollback(); }
        }
    }
}
