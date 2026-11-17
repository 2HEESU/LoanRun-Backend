package com.loanrun.user.domain;

import com.loanrun.common.contract.IncomeType;
import com.loanrun.common.contract.PlanType;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "demo_users")
public class DemoUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 64)
    private String sessionDigest;
    @Column(nullable = false, length = 50)
    private String name;
    @Column(length = 50)
    private String occupation;
    @Enumerated(EnumType.STRING) @Column(length = 16)
    private IncomeType incomeType;
    @Column(nullable = false)
    private boolean onboardingCompleted;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8)
    private PlanType planType;
    @Column(nullable = false)
    private long revision;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected DemoUser() {}
    public Long getId() { return id; }
    public String getName() { return name; }
    public String getOccupation() { return occupation; }
    public IncomeType getIncomeType() { return incomeType; }
    public boolean isOnboardingCompleted() { return onboardingCompleted; }
    public PlanType getPlanType() { return planType; }
    public long getRevision() { return revision; }
    public void setName(String name) { this.name = name; }
    public void setOccupation(String occupation) { this.occupation = occupation; }
    public void setIncomeType(IncomeType incomeType) { this.incomeType = incomeType; }
    public void setOnboardingCompleted(boolean completed) { this.onboardingCompleted = completed; }
    public void setPlanType(PlanType planType) { this.planType = planType; }
    public void changed(Instant now) { revision = Math.incrementExact(revision); updatedAt = now; }
}
