package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class PlanSchemaMigrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    JdbcTemplate jdbc(){return new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()));}
    Flyway flyway(String schema,String target){return Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword())
        .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target(target).load();}
    List<Map<String,Object>> history(String schema){return jdbc().queryForList("SELECT version,script,checksum,installed_rank,success FROM "+schema+".flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");}
    Map<String,List<String>> rows(String schema) {
        var result=new TreeMap<String,List<String>>();
        for(var table:jdbc().queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=? AND table_name<>'flyway_schema_history' ORDER BY table_name",String.class,schema))
            result.put(table,jdbc().queryForList("SELECT to_jsonb(t)::text FROM "+schema+"."+table+" t ORDER BY to_jsonb(t)::text",String.class));
        return result;
    }
    void seed(String schema) {
        var j=jdbc();
        j.update("INSERT INTO "+schema+".demo_users(session_digest,name,revision,created_at,updated_at) VALUES ('v4-synthetic-owner','가상 사용자',17,'2026-03-01T00:00:00Z','2026-03-01T00:00:00Z')");
        j.update("""
            INSERT INTO %s.loans(user_id,institution_name,product_name,loan_form,loan_type,repayment_method,original_principal,
                remaining_principal,annual_rate,rate_type,start_date,maturity_date,payment_day,next_due_date,grace_months,
                prepayment_policy,prepayment_fee_method,initial_savings_balance,status,snapshot_date,snapshot_principal,
                initial_plan_snapshot,created_at,updated_at)
            VALUES (1,'가상 은행','가상 대출','GENERAL','MORTGAGE','EQUAL_PRINCIPAL',12000000,12000000,6,'FIXED',
                '2025-01-25','2026-12-25',25,'2026-03-25',0,'KEEP_PAYMENT','NONE',0,'ACTIVE','2026-03-01',12000000,
                '{"loan_id":1,"version":1,"configured":true,"mode":"MATURITY","monthly_extra_cash":0,"monthly_saving":0,"required_monthly_saving":0,"effective_date":"2026-03-25","target_payoff_date":"2026-12-25"}',
                '2026-03-01T00:00:00Z','2026-03-01T00:00:00Z')
            """.formatted(schema));
        j.update("INSERT INTO "+schema+".loan_balance_snapshots(user_id,loan_id,effective_date,amount,reason,created_at) VALUES (1,1,'2026-03-01',12000000,'REGISTRATION','2026-03-01T00:00:00Z')");
        j.update("INSERT INTO "+schema+".loan_rate_histories(user_id,loan_id,effective_date,annual_rate,created_at) VALUES (1,1,'2026-03-01',6,'2026-03-01T00:00:00Z')");
        j.update("INSERT INTO "+schema+".due_items(user_id,loan_id,due_date,principal_amount,interest_amount,required_amount,terms_revision,created_at) VALUES (1,1,'2026-03-25',1200000,60000,1260000,17,'2026-03-01T00:00:00Z')");
        j.update("INSERT INTO "+schema+".payments(user_id,loan_id,paid_at,payment_type,amount,principal_amount,extra_amount,interest_amount,fee_amount,savings_used_amount,created_at) VALUES (1,1,'2026-03-01','REGULAR',100,0,0,100,0,0,'2026-03-01T00:00:00Z')");
        j.update("INSERT INTO "+schema+".payment_allocations(user_id,loan_id,payment_id,due_item_id,principal_amount,interest_amount,fee_amount) VALUES (1,1,1,1,0,100,0)");
        j.update("INSERT INTO "+schema+".finance_profiles(user_id,monthly_income,income_type,payday,fixed_essential_expenses,variable_essential_expenses,discretionary_expenses,savings,created_at,updated_at) VALUES (1,3000000,'REGULAR',25,0,0,0,0,'2026-03-01T00:00:00Z','2026-03-01T00:00:00Z')");
        j.update("INSERT INTO "+schema+".monthly_budgets(user_id,year_month,income_type,expected_income,payday,fixed_essential_expenses,variable_essential_expenses,discretionary_expenses,discretionary_limit,savings,created_at,updated_at) VALUES (1,'2026-03-01','REGULAR',3000000,25,0,0,0,0,0,'2026-03-01T00:00:00Z','2026-03-01T00:00:00Z')");
        j.update("INSERT INTO "+schema+".monthly_allocations(budget_id,loan_id,allocation_type,amount) VALUES (1,1,'EXTRA_PAYMENT',123)");
        j.update("INSERT INTO "+schema+".catalog_sync_state VALUES (1,'2026-03-01T00:00:00Z')");
    }
    @Test void actualFreshMigrationsThroughV4CreateAllRequiredTablesAndRestartWithNoChanges() {
        var f=flyway("fresh_plan4","4");assertThat(f.migrate().migrationsExecuted).isEqualTo(5);f.validate();
        assertThat(history("fresh_plan4").stream().map(r->r.get("version")).toList()).containsExactly("1","2","3","3.1","4");
        assertThat(rows("fresh_plan4").keySet()).contains("repayment_plan_versions","plan_application_events","payment_due_archives");
        assertThat(f.migrate().migrationsExecuted).isZero();
    }
    @Test void actualV4UpgradePreservesAllExistingCommonLoanLedgerBudgetAndCatalogFacts() {
        String schema="upgrade_plan4";flyway(schema,"3.1").migrate();seed(schema);
        var before=rows(schema);var old=history(schema);var f=flyway(schema,"4");
        assertThat(f.migrate().migrationsExecuted).isEqualTo(1);f.validate();
        var after=rows(schema);before.forEach((table,values)->assertThat(after.get(table)).as(table).isEqualTo(values));
        assertThat(history(schema).stream().filter(r->!r.get("version").equals("4")).toList()).isEqualTo(old);
        assertThat(jdbc().queryForObject("SELECT revision FROM "+schema+".demo_users WHERE id=1",Long.class)).isEqualTo(17);
        assertThat(after.get("repayment_plan_versions")).isEmpty();assertThat(after.get("plan_application_events")).isEmpty();assertThat(after.get("payment_due_archives")).isEmpty();
        assertThat(f.migrate().migrationsExecuted).isZero();
    }
    @Test void aManualArchiveTableConflictRollsBackTheEntireActualV4WithoutChangingOriginalRowsOrHistory() {
        String schema="conflict_plan4";flyway(schema,"3.1").migrate();seed(schema);
        jdbc().execute("CREATE TABLE "+schema+".payment_due_archives(marker TEXT)");jdbc().update("INSERT INTO "+schema+".payment_due_archives VALUES ('preserve-original')");
        var before=rows(schema);var old=history(schema);
        assertThatThrownBy(()->flyway(schema,"4").migrate()).isInstanceOf(FlywayException.class);
        assertThat(rows(schema)).isEqualTo(before);assertThat(history(schema)).isEqualTo(old);
        assertThat(rows(schema)).doesNotContainKeys("repayment_plan_versions","plan_application_events");
    }
}
