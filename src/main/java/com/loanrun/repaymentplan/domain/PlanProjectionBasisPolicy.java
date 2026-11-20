package com.loanrun.repaymentplan.domain;

import com.loanrun.common.contract.FinanceModels.RepaymentMethod;
import java.math.*;
import java.time.*;
import java.util.*;

/** Pure selection of verified monthly inputs, not evidence verification or a payoff/fee calculator. */
public final class PlanProjectionBasisPolicy {
    private PlanProjectionBasisPolicy() {}
    public enum Metric { EXTRA_PRINCIPAL,SAVING_ADDITION }
    public enum AmountKind { EXTRA_PRINCIPAL,EXTRA_CASH,SAVING_ADDITION }
    public enum Source { ACTUAL_AVERAGE,FALLBACK_PLAN,INSUFFICIENT }
    /** The caller must supply independently verified monthly completeness for this exact metric/scope. */
    public record Observation(long userId,long loanId,YearMonth month,Metric metric,boolean observed,Long amount) {
        public Observation {
            Objects.requireNonNull(month);Objects.requireNonNull(metric);
            if(userId<=0||loanId<=0||month.getYear()<1||month.getYear()>9999||observed!=(amount!=null)||amount!=null&&amount<0)
                throw new IllegalArgumentException("Invalid observed projection input");
        }
    }
    public record Basis(Source source,AmountKind amountKind,Long monthlyAmount,List<YearMonth> usedMonths) {
        public Basis {usedMonths=List.copyOf(usedMonths);}
        public int observedMonthCount(){return usedMonths.size();}
    }
    public static Basis select(long userId,long loanId,RepaymentMethod method,LocalDate registeredOn,LocalDate asOf,
        SavedPlanSource fallback,List<Observation> observations) {
        Objects.requireNonNull(method);Objects.requireNonNull(registeredOn);Objects.requireNonNull(asOf);Objects.requireNonNull(observations);
        if(userId<=0||loanId<=0||registeredOn.isAfter(asOf))throw new IllegalArgumentException("Invalid projection scope");
        if(fallback!=null&&(fallback.userId()!=userId||fallback.loanId()!=loanId))throw new IllegalArgumentException("Foreign fallback plan");
        var metric=method==RepaymentMethod.BULLET?Metric.SAVING_ADDITION:Metric.EXTRA_PRINCIPAL;
        var current=YearMonth.from(asOf);var first=current.minusMonths(6);var registration=YearMonth.from(registeredOn);
        var selected=new TreeMap<YearMonth,Observation>();
        for(var observation:observations) {
            if(observation.userId()!=userId||observation.loanId()!=loanId)throw new IllegalArgumentException("Mixed projection owners/loans");
            if(observation.metric()!=metric||observation.month().isBefore(first)||!observation.month().isBefore(current)||observation.month().isBefore(registration))continue;
            if(selected.putIfAbsent(observation.month(),observation)!=null)throw new IllegalArgumentException("Duplicate projection month");
        }
        var confirmed=selected.values().stream().filter(Observation::observed).toList();
        if(!confirmed.isEmpty()) {
            var total=confirmed.stream().map(o->BigDecimal.valueOf(o.amount())).reduce(BigDecimal.ZERO,BigDecimal::add);
            long mean=total.divide(BigDecimal.valueOf(confirmed.size()),0,RoundingMode.HALF_UP).longValueExact();
            return new Basis(Source.ACTUAL_AVERAGE,metric==Metric.SAVING_ADDITION?AmountKind.SAVING_ADDITION:AmountKind.EXTRA_PRINCIPAL,
                mean,confirmed.stream().map(Observation::month).toList());
        }
        var kind=method==RepaymentMethod.BULLET?AmountKind.SAVING_ADDITION:AmountKind.EXTRA_CASH;
        if(fallback==null||!fallback.configured())return new Basis(Source.INSUFFICIENT,kind,null,List.of());
        long amount=method==RepaymentMethod.BULLET?fallback.monthlySaving():fallback.monthlyExtraCash();
        if(amount<0)throw new IllegalArgumentException("Negative fallback amount");
        return new Basis(Source.FALLBACK_PLAN,kind,amount,List.of());
    }
}
