package com.myxhs.common.spel;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

/**
 * SpEL 表达式解析器
 * <p>
 * 从 AOP 切面的方法参数中提取动态 Key 值。
 * 例：@DistributedLock(key = "'inventory:' + #skuId")
 *     方法参数 skuId=123 → 解析为 "inventory:123"
 * </p>
 */
public final class SpELParser {

    private static final ExpressionParser PARSER = new SpelExpressionParser();

    private SpELParser() {
        // 工具类禁止实例化
    }

    /**
     * 解析 SpEL 表达式
     *
     * @param expression SpEL 表达式字符串
     * @param joinPoint  AOP 切点
     * @return 解析后的字符串值
     */
    public static String parse(String expression, ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] paramNames = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();

        EvaluationContext context = new StandardEvaluationContext();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }

        try {
            Object value = PARSER.parseExpression(expression).getValue(context);
            return value != null ? value.toString() : "null";
        } catch (Exception e) {
            // SpEL 解析失败时返回原始表达式，避免 NPE 导致业务中断
            return expression;
        }
    }
}
