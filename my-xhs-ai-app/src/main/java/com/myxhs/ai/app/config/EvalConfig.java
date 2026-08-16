package com.myxhs.ai.app.config;

import com.myxhs.ai.app.eval.BadCaseCollector;
import com.myxhs.ai.app.eval.EvalJudge;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M14 评测装配（P2 收尾）：LLM-as-judge（复用主 ChatModel——成本红线仅 flash 无新模型）+
 * bad case 回流路径配置化（jar 部署可配绝对路径）。
 * judge 默认关闭（myxhs.ai.eval.judge.enabled=false，成本控制）；nightly 开启。
 */
@Configuration
public class EvalConfig {

    @Bean
    public EvalJudge evalJudge(ChatModel chatModel,
                               @Value("${myxhs.ai.eval.judge.enabled:false}") boolean enabled) {
        return new EvalJudge(chatModel, enabled);
    }

    @Bean
    public BadCaseCollector badCaseCollector(
            @Value("${myxhs.ai.eval.badcase-file:src/main/resources/eval/cases-badcases.yaml}") String path) {
        return new BadCaseCollector(path);
    }
}
