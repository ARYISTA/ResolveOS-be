package com.resolvos.securitylab.dto;

public record RunAttackRequest(
        String attackId,
        String attack
) {
    public String effectiveAttackId() {
        if (attackId != null && !attackId.isBlank()) return attackId.trim();
        if (attack != null && !attack.isBlank()) return attack.trim();
        return "sec-prompt-injection";
    }
}
