package com.myxhs.ai.security;

/**
 * xhs-ai 角色（RBAC）：VIEWER（默认）< OPERATOR < ADMIN。
 * 来源：平台 JWT 的 role claim（如 OPERATOR），或管理/内部令牌（视为 ADMIN）。
 */
public enum AiRole {
    VIEWER, OPERATOR, ADMIN;

    public static AiRole fromClaim(String claim) {
        if (claim == null || claim.isBlank()) {
            return VIEWER;
        }
        switch (claim.trim().toUpperCase()) {
            case "ADMIN":
            case "SUPER_ADMIN":
                return ADMIN;
            case "OPERATOR":
            case "OPS":
            case "MASTER":
                return OPERATOR;
            default:
                return VIEWER;
        }
    }

    public boolean atLeast(AiRole required) {
        return this.ordinal() >= required.ordinal();
    }
}
