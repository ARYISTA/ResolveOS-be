package com.resolvos.enforcement;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record EnforcementCheck(
        String rule,
        boolean passed,
        String reason
) {
    public static EnforcementCheck pass(String rule, String reason) {
        return new EnforcementCheck(rule, true, reason);
    }

    public static EnforcementCheck fail(String rule, String reason) {
        return new EnforcementCheck(rule, false, reason);
    }
}
