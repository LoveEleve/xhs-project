package com.myxhs.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * xhs-ai — 企业级运维诊断与知识问答 Agent（AgentScope 2.0）
 */
@SpringBootApplication
@EnableScheduling
public class XhsAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(XhsAiApplication.class, args);
    }
}
