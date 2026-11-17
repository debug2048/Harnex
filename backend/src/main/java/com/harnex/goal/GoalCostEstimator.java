package com.harnex.goal;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import com.harnex.config.ProjectRuntime;
import com.harnex.run.RunHandle;
import com.harnex.run.RunRecord;
import com.harnex.usage.UsageLedger;

/** Goal budgets consume the same per-attempt ledger as conversation and image usage. */
@Service
@ProjectRuntime
public class GoalCostEstimator {
    private final UsageLedger ledger;
    public GoalCostEstimator(UsageLedger ledger) { this.ledger = ledger; }
    public boolean supports(String provider, String model) { return ledger.supportsText(provider, model); }
    public RunRecord.CostUsage estimate(RunHandle run) {
        var summary = ledger.summary(run.getRunId());
        // Goal's configured amount is USD. A different currency cannot silently enter that budget.
        boolean known = summary.costComplete() && summary.knownAmounts().keySet().stream().allMatch("USD"::equals);
        return new RunRecord.CostUsage(known, summary.knownAmounts().getOrDefault("USD", BigDecimal.ZERO).doubleValue(),
            "usage-ledger", "per-attempt", 0, 0, 0, 0, summary.calculatedAt());
    }
}
