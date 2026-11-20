package com.loanrun.loan.read;

import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.contract.LoanQueryPort;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.domain.*;
import com.loanrun.loan.read.LoanSources.*;
import com.loanrun.loan.service.CurrentLoanTermsQueryService;
import com.loanrun.payment.domain.DueStateCalculator.Status;
import com.loanrun.payment.service.*;
import com.loanrun.repaymentsaving.service.SavingObservationQueryService;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Owner-scoped actual ledger projections. Never calls budget/plan consumers or writes derived data. */
@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class LoanLedgerQueryService implements LoanQueryPort {
    private final LoanSourceRepository sources;
    private final CurrentLoanTermsQueryService current;
    private final StoredDueQueryService dues;
    private final PaymentActualsQueryService actuals;
    private final PaymentObservationQueryService payments;
    private final SavingObservationQueryService savings;
    private final LoanHistoryQueryService history;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    public LoanLedgerQueryService(LoanSourceRepository sources,CurrentLoanTermsQueryService current,
        StoredDueQueryService dues,PaymentActualsQueryService actuals,PaymentObservationQueryService payments,
        SavingObservationQueryService savings,LoanHistoryQueryService history,NamedParameterJdbcTemplate jdbc,Clock clock) {
        this.sources=sources;this.current=current;this.dues=dues;this.actuals=actuals;this.payments=payments;
        this.savings=savings;this.history=history;this.jdbc=jdbc;this.clock=clock;
    }
    @Override public LoanTerms terms(long user,long id,LocalDate asOf){return read(()->readTerms(user,id,asOf));}
    @Override public List<LoanTerms> activeLoans(long user,LocalDate asOf){return read(()->readActive(user,asOf));}
    @Override public List<LoanTerms> historicalLoans(long user,DateRange range){return read(()->readHistorical(user,range));}
    @Override public DueSummary dueSummary(long user,YearMonth month){return read(()->readDues(user,month));}
    @Override public PaymentSummary paymentSummary(long user,YearMonth month){return read(()->readPayments(user,month));}
    @Override public SavingSummary savingSummary(long user,YearMonth month){return read(()->readSavings(user,month));}
    @Override public List<HistoryMonth> history(long user,DateRange range){return read(()->history.history(user,null,range));}
    @Override public List<ExportLoan> exportLoans(long user){return read(()->readExportLoans(user));}
    @Override public List<ExportPayment> exportPayments(long user,DateRange range){return read(()->readExportPayments(user,range));}

    private LoanTerms readTerms(long user,long id,LocalDate asOf) {
        var loan=sources.loan(user,id);Objects.requireNonNull(asOf);var today=today();
        if(asOf.isAfter(today)||loan.createdAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().isAfter(asOf))throw ApiException.unavailable();
        // V2 has no immutable contract-change history. Do not reuse changed current terms in the past.
        if(!asOf.equals(today)&&!loan.updatedAt().isBefore(asOf.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant()))throw ApiException.unavailable();
        if(asOf.equals(today)&&loan.storedStatus()==LoanStatus.ACTIVE&&loan.storedRemainingPrincipal()>0) {
            var result=current.current(user,id);if(result.terms()==null||!result.asOf().equals(asOf))throw ApiException.unavailable();return result.terms();
        }
        var snapshot=sources.loanSnapshot(user,id,asOf);
        return LoanTermMapper.current(loan,loan.storedNextDueDate(),loan.contractualNextAmount(),LoanSavingsCalculator.balance(loan,snapshot));
    }
    private List<LoanTerms> readActive(long user,LocalDate asOf) {
        Objects.requireNonNull(asOf);if(asOf.isAfter(today()))throw ApiException.unavailable();
        if(today().equals(asOf))return sources.activeLoanIds(user).stream().map(id->terms(user,id,asOf)).toList();
        var loans=sources.snapshot(user,asOf).loans();
        // Membership also needs an unchanged source; a current PAID/DELETED status cannot hide a past active loan.
        var cutoff=asOf.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        if(loans.stream().anyMatch(l->!l.updatedAt().isBefore(cutoff)))throw ApiException.unavailable();
        return loans.stream().filter(l->l.storedStatus()==LoanStatus.ACTIVE).map(l->terms(user,l.loanId(),asOf)).toList();
    }
    private List<LoanTerms> readHistorical(long user,DateRange range) {
        Objects.requireNonNull(range);var end=range.endInclusive().isAfter(today())?today():range.endInclusive();
        if(end.isBefore(range.startInclusive()))return List.of();
        return sources.snapshot(user,end).loans().stream().map(l->terms(user,l.loanId(),end)).toList();
    }
    private DueSummary readDues(long user,YearMonth month) {
        Objects.requireNonNull(month);var today=today();var snapshot=month.isBefore(YearMonth.from(today))?sources.snapshot(user,today):sources.visibleSnapshot(user,today,"ALL");
        if(jdbc.queryForObject("SELECT count(*) FROM payments WHERE user_id=:user AND paid_at>:today",Map.of("user",user,"today",today),Long.class)>0)throw ApiException.unavailable();
        var all=dues.range(user,new DateRange(LocalDate.of(1,1,1),month.atEndOfMonth()),null).stream()
            .filter(d->d.source().loanStatus()!=LoanStatus.DELETED||month.isBefore(YearMonth.from(today))).toList();
        if(all.stream().anyMatch(d->!d.evaluatedOn().equals(today)))throw ApiException.unavailable();
        var selected=all.stream().filter(d->YearMonth.from(d.source().due().dueDate()).equals(month)).toList();
        for(var loan:snapshot.loans())if(loan.storedStatus()==LoanStatus.ACTIVE&&
            !month.isBefore(YearMonth.from(loan.initialPlan().effectiveDate()))&&!month.isAfter(YearMonth.from(loan.maturityDate()))&&
            selected.stream().noneMatch(d->d.source().due().loanId()==loan.loanId()))throw ApiException.unavailable();
        long scheduled=0,previous=0,paid=0,outstanding=0,used=0;int paidCount=0,partial=0,missed=0,unrecorded=0;
        LocalDate next=null;
        for(var row:all) {
            if(row.source().due().dueDate().isBefore(month.atDay(1)))previous=Math.addExact(previous,row.state().outstandingAmount());
            else {
                var due=row.source().due();scheduled=Math.addExact(scheduled,due.requiredAmount());
                paid=Math.addExact(paid,row.state().paidRequiredAmount());outstanding=Math.addExact(outstanding,row.state().outstandingAmount());
                if(row.state().status()==Status.PAID)paidCount++;
                if(row.state().status()==Status.PARTIAL)partial++;
                if(row.state().status()==Status.UNPAID)missed++;
                if(row.state().status()==Status.UNRECORDED)unrecorded++;
            }
        }
        for(var d:dues.range(user,new DateRange(today,LocalDate.of(9999,12,31)),null))
            if(d.source().loanStatus()!=LoanStatus.DELETED&&d.state().outstandingAmount()>0&&
                (next==null||d.source().due().dueDate().isBefore(next)))next=d.source().due().dueDate();
        for(var p:snapshot.payments())if(p.savingsUsed()>0&&p.kind()==PaymentKind.REGULAR) {
            var allocation=snapshot.allocations().stream().filter(a->a.paymentId()==p.id()).toList();long allocated=0;boolean in=false,out=false;
            for(var a:allocation) {
                var due=snapshot.dues().stream().filter(d->d.id()==a.dueItemId()).findFirst().orElseThrow(ApiException::unavailable);
                if(YearMonth.from(due.dueDate()).equals(month)){in=true;allocated=Math.addExact(allocated,a.principal());}else out=true;
            }
            if(in) {
                if(out||p.extraPrincipal()>0||p.savingsUsed()>allocated)throw ApiException.unavailable();
                used=Math.addExact(used,p.savingsUsed());
            }
        }
        return new DueSummary(month,scheduled,previous,paid,outstanding,used,next,selected.size(),paidCount,partial,missed,unrecorded);
    }
    private PaymentSummary readPayments(long user,YearMonth month) {
        var range=month(month);var proof=payments.observe(user,month,null);var totals=actuals.aggregate(user,range,null);
        Map<Long,Long> extra=new LinkedHashMap<>();
        for(var p:sources.payments(user,range)) {
            if(p.paidAt().isAfter(today()))throw ApiException.unavailable();
            if(p.extraPrincipal()==0)continue;
            long cash;
            if(p.kind()==PaymentKind.EXTRA&&p.regularPrincipal()==0)cash=Math.subtractExact(p.amount(),p.savingsUsed());
            else {if(p.fee()!=0||p.savingsUsed()!=0)throw ApiException.unavailable();cash=p.extraPrincipal();}
            extra.merge(p.loanId(),cash,Math::addExact);
        }
        var regular=dues.range(user,range,null);int missed=0,partial=0;
        for(var d:regular) {if(d.state().status()==Status.UNPAID)missed++;if(d.state().status()==Status.PARTIAL)partial++;}
        return new PaymentSummary(month,totals.regularPrincipal(),totals.extraPrincipal(),totals.interest(),totals.fee(),
            totals.cashPaid(),totals.savingsUsed(),Math.toIntExact(totals.extraPrincipalCount()),proof.observed(),missed,partial,extra);
    }
    private SavingSummary readSavings(long user,YearMonth month) {
        Objects.requireNonNull(month);var now=clock.instant();var today=today();var through=month.atEndOfMonth().isAfter(today)?today:month.atEndOfMonth();
        if(month.isAfter(YearMonth.from(today)))throw ApiException.unavailable();
        var snapshot=sources.snapshot(user,through);long initial=0,closing=0,required=0,adds=0,used=0;boolean observed=true,any=false;
        Map<Long,Long> additions=new LinkedHashMap<>();
        for(var loan:snapshot.loans())if(loan.method()==RepaymentMethod.BULLET) {
            if(through.isBefore(today()))terms(user,loan.loanId(),through); // Do not infer historical status from today's PAID/DELETED flag.
            any=true;initial=Math.addExact(initial,loan.initialSavings());long balance=LoanSavingsCalculator.balance(loan,snapshot);closing=Math.addExact(closing,balance);
            var proof=savings.observeAt(user,loan.loanId(),month,now);observed&=proof.observed();
            // This field is contractual maturity funding, not a saved target/override from PlanQueryPort.
            if(loan.storedStatus()==LoanStatus.ACTIVE&&!month.isAfter(YearMonth.from(loan.maturityDate()))) {
                required=Math.addExact(required,LoanSavingsCalculator.required(loan,balance,through,loan.maturityDate()));
            }
        }
        for(var s:snapshot.savings())if(YearMonth.from(s.savedAt()).equals(month)){adds=Math.addExact(adds,s.amount());additions.merge(s.loanId(),s.amount(),Math::addExact);}
        for(var p:snapshot.payments())if(YearMonth.from(p.paidAt()).equals(month))used=Math.addExact(used,p.savingsUsed());
        return new SavingSummary(month,adds,initial,used,closing,any&&observed,required,additions);
    }
    private List<ExportLoan> readExportLoans(long user) {
        owner(user);return List.copyOf(jdbc.query("SELECT id,product_name,remaining_principal FROM loans WHERE user_id=:user ORDER BY id",Map.of("user",user),
            (r,n)->new ExportLoan(r.getLong("id"),r.getString("product_name"),r.getLong("remaining_principal"))));
    }
    private List<ExportPayment> readExportPayments(long user,DateRange range) {
        owner(user);Objects.requireNonNull(range);return List.copyOf(jdbc.query("""
            SELECT p.*,l.product_name FROM payments p JOIN loans l ON l.id=p.loan_id AND l.user_id=p.user_id
            WHERE p.user_id=:user AND p.paid_at BETWEEN :from AND :to ORDER BY p.paid_at,p.id
            """,Map.of("user",user,"from",range.startInclusive(),"to",range.endInclusive()),
            (r,n)->new ExportPayment(r.getLong("loan_id"),r.getString("product_name"),r.getObject("paid_at",LocalDate.class),
                r.getLong("amount"),r.getLong("principal_amount"),r.getLong("interest_amount"),r.getLong("extra_amount"),r.getLong("fee_amount"),r.getLong("savings_used_amount"))));
    }
    private DateRange month(YearMonth month){Objects.requireNonNull(month);return new DateRange(month.atDay(1),month.atEndOfMonth());}
    private LocalDate today(){return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));}
    private static void owner(long user){if(user<=0)throw new IllegalArgumentException("Positive internal owner required");}
    private static <T>T read(Supplier<T> query) {
        try{return query.get();}catch(ArithmeticException|DataAccessException e){throw ApiException.unavailable();}
    }
}
