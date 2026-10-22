package com.lore.zzal.metrics;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import com.lore.zzal.generation.client.PersistentProviderUsageRecorder;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class RuntimeMetricsJobTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);
    RuntimeMetricsJob job(boolean queue) { return new RuntimeMetricsJob(new SimpleMeterRegistry(),mock(RequestMappingHandlerMapping.class),db,mock(PersistentProviderUsageRecorder.class),queue,60000); }
    @Test void queueCapsRowsAndTimeoutAndMakesMissingExplicit() throws Exception {
        when(db.query(any(PreparedStatementCreator.class),any(RowMapper.class))).thenAnswer(i->List.of("QUEUED","RUNNING","QUEUED"));
        assertThat(job(true).queue()).isEqualTo(new RuntimeSnapshot.Queue("zzal",2,1,"ok"));
        var creator=org.mockito.ArgumentCaptor.forClass(PreparedStatementCreator.class);verify(db).query(creator.capture(),any(RowMapper.class));
        var connection=mock(java.sql.Connection.class);var statement=mock(java.sql.PreparedStatement.class);when(connection.prepareStatement(anyString())).thenReturn(statement);
        creator.getValue().createPreparedStatement(connection);verify(statement).setQueryTimeout(2);
        verify(connection).prepareStatement("SELECT status FROM zzal_gen_job WHERE status IN ('QUEUED','RUNNING') LIMIT 1001");
        when(db.query(any(PreparedStatementCreator.class),any(RowMapper.class))).thenAnswer(i->java.util.Collections.nCopies(1001,"QUEUED"));
        assertThat(job(true).queue()).isEqualTo(new RuntimeSnapshot.Queue("zzal",null,null,"truncated"));
        when(db.query(any(PreparedStatementCreator.class),any(RowMapper.class))).thenThrow(new IllegalStateException("unavailable"));
        assertThat(job(true).queue()).isEqualTo(new RuntimeSnapshot.Queue("zzal",null,null,"missing"));
    }
    @Test void queueCanBeDisabledWithoutDatabaseQuery() {assertThat(job(false).queue().status()).isEqualTo("missing");verifyNoInteractions(db);}
    @Test void schedulerIsOptInAndCannotRunFasterThanOneMinute() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(RuntimeMetricsJob.class)
                .run(context->{assertThat(context).hasNotFailed();assertThat(context).doesNotHaveBean(RuntimeMetricsJob.class);});
        assertThatThrownBy(()->new RuntimeMetricsJob(new SimpleMeterRegistry(),null,db,null,true,1000)).isInstanceOf(IllegalArgumentException.class);
    }
}
