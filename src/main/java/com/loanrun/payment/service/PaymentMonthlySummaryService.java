package com.loanrun.payment.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.payment.read.PaymentObservationSource;
import com.loanrun.payment.repository.PaymentMonthlyRepository;
import com.loanrun.repaymentsaving.read.SavingObservationSource;
import com.loanrun.repaymentsaving.service.SavingObservationQueryService;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class PaymentMonthlySummaryService {
    private final PaymentMonthlyRepository rows;private final PaymentObservationQueryService payments;private final SavingObservationQueryService savings;
    private final ObjectProvider<PaymentObservationSource> paymentProof;private final ObjectProvider<SavingObservationSource> savingProof;
    private final ObjectProvider<PlanPresentationPort> plans;private final ObjectProvider<BudgetQueryPort> budgets;private final ObjectProvider<LoanQueryPort> history;private final ObjectMapper mapper;private final Clock clock;
    public PaymentMonthlySummaryService(PaymentMonthlyRepository rows,PaymentObservationQueryService payments,SavingObservationQueryService savings,ObjectProvider<PaymentObservationSource> paymentProof,ObjectProvider<SavingObservationSource> savingProof,ObjectProvider<PlanPresentationPort> plans,ObjectProvider<BudgetQueryPort> budgets,ObjectProvider<LoanQueryPort> history,ObjectMapper mapper,Clock clock) {
        this.rows=rows;this.payments=payments;this.savings=savings;this.paymentProof=paymentProof;this.savingProof=savingProof;this.plans=plans;this.budgets=budgets;this.history=history;this.mapper=mapper;this.clock=clock;
    }
    public JsonNode summary(long user,String fromText,String toText,String loanText) {
        try{return read(user,fromText,toText,loanText);}
        catch(ArithmeticException|IllegalArgumentException|org.springframework.dao.DataAccessException e){throw ApiException.unavailable();}
    }
    private JsonNode read(long user,String fromText,String toText,String loanText) {
        var now=clock.instant();var today=now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();var current=YearMonth.from(today);
        var from=parse(fromText,current.minusMonths(5));var to=parse(toText,current);if(to.isBefore(from))throw range();Long id=null;
        if(loanText!=null)try {if(!loanText.matches("[0-9]+"))throw new IllegalArgumentException();id=Long.parseLong(loanText);if(id<=0)throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw ApiException.validation("loan_id","대출 ID를 확인해 주세요.");}
        var loans=rows.loans(user,id);var counts=rows.counts(user,id,from,to,today);var result=mapper.createObjectNode();var months=result.putArray("months");
        Map<YearMonth,HistoryMonth> opening=new HashMap<>();var source=history.getIfAvailable();
        if(id==null&&source!=null) {
            var records=source.history(user,new DateRange(from.atDay(1),to.atEndOfMonth()));if(records==null)throw ApiException.unavailable();
            for(var h:records)if(h==null||h.month().isBefore(from)||h.month().isAfter(to)||opening.put(h.month(),h)!=null||h.openingBalance()<0)throw ApiException.unavailable();
        }
        for(var m=from;!m.isAfter(to);m=m.plusMonths(1)) {
            var month=m;var selected=loans.stream().filter(l->!l.registered().isAfter(month.atEndOfMonth())).toList();var count=counts.getOrDefault(m,new PaymentMonthlyRepository.Counts(0,0,0));
            if(count.futurePayments()>0||count.futureSavings()>0)throw ApiException.unavailable();
            var proof=paymentProof.getIfAvailable()==null?null:payments.observeAt(user,m,id,now);
            boolean observed=proof!=null&&proof.observed();long adds=0;
            for(var loan:selected)if(loan.bullet()) {
                if(savingProof.getIfAvailable()==null){observed=false;continue;}
                var s=savings.observeAt(user,loan.id(),m,now);observed&=s.observed();if(s.observed())adds=Math.addExact(adds,s.actuals().additions());
            }
            if(proof!=null&&proof.source().recorded().paymentCount()!=count.payments())throw ApiException.unavailable();
            ObjectNode row=months.addObject();row.put("year_month",m.toString());row.put("payment_count",count.payments());row.put("observed",observed);row.put("data_status",observed?"RECORDED":"INSUFFICIENT");
            Long actual=null,principal=null,extra=null,interest=null,fee=null,used=null,actualSaving=null;
            if(observed) {var a=proof.actuals();actual=Math.addExact(a.cashPaid(),adds);principal=a.regularPrincipal();extra=a.extraPrincipal();interest=a.interest();fee=a.fee();used=a.savingsUsed();actualSaving=adds;}
            put(row,"actual_amount",actual);put(row,"principal_amount",principal);put(row,"extra_amount",extra);put(row,"interest_amount",interest);put(row,"fee_amount",fee);put(row,"savings_used_amount",used);put(row,"actual_saving_amount",actualSaving);
            Long start=null;if(!m.isAfter(current)&&selected.stream().noneMatch(l->l.registered().isBefore(month.atDay(1))))start=0L;
            var h=opening.get(m);if(h!=null&&h.observed()&&!m.isAfter(current))start=h.openingBalance();put(row,"monthly_start_principal",start);
            put(row,"monthly_principal_repayment_rate",observed&&start!=null&&start>0?BigDecimal.valueOf(Math.addExact(principal,extra)).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(start),2,RoundingMode.HALF_UP):null);
            var target=target(user,id,today,m,selected);put(row,"target_amount",target[0]);put(row,"planned_saving_amount",target[1]);put(row,"difference",actual!=null&&target[0]!=null?Math.subtractExact(actual,target[0]):null);
            if(m.equals(to))break; // Avoid stepping beyond YYYY=9999.
        }
        return result;
    }
    private Long[] target(long user,Long id,LocalDate asOf,YearMonth month,List<PaymentMonthlyRepository.Loan> loans) {
        Long saving=loans.stream().noneMatch(PaymentMonthlyRepository.Loan::bullet)?0L:null;
        var port=plans.getIfAvailable();var budget=budgets.getIfAvailable();if(port==null||budget==null)return new Long[]{null,saving};
        var overrides=budget.monthlyOverrides(user,month);if(overrides==null)throw ApiException.unavailable();var views=port.monthViews(user,asOf,month,overrides);if(views==null)throw ApiException.unavailable();
        Long total=0L;saving=0L;var seen=new HashSet<Long>();boolean found=false;
        var owned=rows.loans(user,null).stream().map(PaymentMonthlyRepository.Loan::id).toList();
        for(var p:views) {
            if(p==null||!seen.add(p.loanId())||!owned.contains(p.loanId())||p.monthlyAmount()!=null&&p.monthlyAmount()<0||p.plannedSavingAmount()!=null&&p.plannedSavingAmount()<0)throw ApiException.unavailable();
            if(id!=null&&p.loanId()!=id)continue;found=true;
            saving=add(saving,p.plannedSavingAmount());total=add(total,add(p.monthlyAmount(),p.plannedSavingAmount()));
            // Gross maturity principal cannot be assumed to be new cash after using previously saved assets.
            for(var loan:loans)if(loan.id()==p.loanId()&&loan.bullet()&&YearMonth.from(loan.maturity()).equals(month))total=null;
        }
        if(!found&&!loans.isEmpty())return new Long[]{null,loans.stream().noneMatch(PaymentMonthlyRepository.Loan::bullet)?0L:null};return new Long[]{total,saving};
    }
    private void put(ObjectNode n,String k,Object v){n.set(k,mapper.valueToTree(v));}
    private static Long add(Long a,Long b){return a==null||b==null?null:Math.addExact(a,b);}
    private static YearMonth parse(String text,YearMonth fallback) {if(text==null)return fallback;try{if(!text.matches("[0-9]{4}-[0-9]{2}")||text.startsWith("0000"))throw new IllegalArgumentException();return YearMonth.parse(text);}catch(DateTimeException|IllegalArgumentException e){throw range();}}
    public static ApiException range(){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_DATE_RANGE","조회 기간을 확인해 주세요.");}
}
