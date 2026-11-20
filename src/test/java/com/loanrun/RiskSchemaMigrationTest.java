package com.loanrun;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RiskSchemaMigrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16.16-bookworm");

    private static Flyway flyway(DriverManagerDataSource source, String target) {
        var config = Flyway.configure().dataSource(source).locations("classpath:db/migration");
        if (target != null) config.target(target);
        return config.load();
    }

    private static long insertUser(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
            "INSERT INTO demo_users(session_digest,created_at,updated_at) VALUES (?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id",
            Long.class,
            UUID.randomUUID().toString().replace("-", "").repeat(2)
        );
    }

    @Test
    void upgradesV5ToV6WithoutLosingRowsAndEnforcesImmutabilityAndConstraints() {
        var source = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        var jdbc = new JdbcTemplate(source);

        flyway(source, "5").migrate();
        long userId = insertUser(jdbc);

        var latest = flyway(source, null);
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        latest.validate();
        assertThat(latest.migrate().migrationsExecuted).isZero();

        var versions = jdbc.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success=TRUE ORDER BY installed_rank",
            String.class
        );
        assertThat(versions).containsExactly("1", "2", "3", "3.1", "4", "5", "6");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_users WHERE id=?", Long.class, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM information_schema.tables WHERE table_schema='public'
            AND table_name IN ('finance_change_events','finance_input_versions','risk_assessment_versions','risk_assessment_requests')
            """, Long.class)).isEqualTo(4);

        // No-op amount change or unknown cash impact inconsistency is rejected.
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO finance_change_events(user_id,data_revision,origin,basis_month,event_date,kind,before_amount,after_amount,cash_impact_known,created_at)
            VALUES (?,1,'PROFILE','2026-11-01','2026-11-20','INCOME',100,100,TRUE,CURRENT_TIMESTAMP)
            """, userId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO finance_change_events(user_id,data_revision,origin,basis_month,event_date,kind,before_amount,after_amount,cash_impact_known,created_at)
            VALUES (?,1,'PROFILE','2026-11-01','2026-11-20','INCOME',NULL,100,TRUE,CURRENT_TIMESTAMP)
            """, userId)).isInstanceOf(DataIntegrityViolationException.class);

        // Month-end as_of constraint on risk_assessment_versions.
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO risk_assessment_versions(user_id,basis_month,data_revision,as_of,input_hash,household_payload,loan_payloads,created_at)
            VALUES (?,'2026-10-01',1,'2026-10-30',repeat('a',64),'{}'::jsonb,'{}'::jsonb,CURRENT_TIMESTAMP)
            """, userId)).isInstanceOf(DataIntegrityViolationException.class);

        // Insert valid rows across the three immutable journals and verify append-only triggers.
        jdbc.update("""
            INSERT INTO finance_change_events(user_id,data_revision,origin,basis_month,event_date,kind,before_amount,after_amount,cash_impact_known,created_at)
            VALUES (?,1,'PROFILE','2026-11-01','2026-11-20','INCOME',100,200,TRUE,CURRENT_TIMESTAMP)
            """, userId);
        jdbc.update("""
            INSERT INTO finance_input_versions(user_id,data_revision,origin,basis_month,before_payload,after_payload,created_at)
            VALUES (?,1,'PROFILE','2026-11-01',NULL,'{}'::jsonb,CURRENT_TIMESTAMP)
            """, userId);
        jdbc.update("""
            INSERT INTO risk_assessment_versions(user_id,basis_month,data_revision,as_of,input_hash,household_payload,loan_payloads,created_at)
            VALUES (?,'2026-10-01',1,'2026-10-31',repeat('a',64),'{}'::jsonb,'{}'::jsonb,CURRENT_TIMESTAMP)
            """, userId);

        assertThatThrownBy(() -> jdbc.update("UPDATE finance_change_events SET after_amount=300 WHERE user_id=?", userId))
            .isInstanceOf(UncategorizedSQLException.class)
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM finance_input_versions WHERE user_id=?", userId))
            .isInstanceOf(UncategorizedSQLException.class)
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE risk_assessment_versions SET household_payload='{}'::jsonb WHERE user_id=?", userId))
            .isInstanceOf(UncategorizedSQLException.class)
            .hasMessageContaining("append-only");

        // Dirty-request marker remains mutable.
        jdbc.update("INSERT INTO risk_assessment_requests(user_id,basis_month,data_revision,pending) VALUES (?,'2026-10-01',1,TRUE)", userId);
        jdbc.update("UPDATE risk_assessment_requests SET pending=FALSE WHERE user_id=? AND basis_month='2026-10-01'", userId);
        assertThat(jdbc.queryForObject(
            "SELECT pending FROM risk_assessment_requests WHERE user_id=? AND basis_month='2026-10-01'",
            Boolean.class,
            userId
        )).isFalse();
    }
}
