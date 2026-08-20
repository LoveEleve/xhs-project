package com.myxhs.ai.app.labs.temporal;

import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D5 Temporal PoC：独立 Worker 启动类（接近真实 restart 场景）。
 *
 * 使用方式：
 *   java ... TemporalWorkerBootstrap [target] [taskQueue]
 * 默认：target=127.0.0.1:7233, taskQueue=approval-restart-task-queue
 */
public class TemporalWorkerBootstrap {

    private static final Logger log = LoggerFactory.getLogger(TemporalWorkerBootstrap.class);

    public static void main(String[] args) {
        String target = args.length > 0 ? args[0] : "127.0.0.1:7233";
        String taskQueue = args.length > 1 ? args[1] : "approval-restart-task-queue";

        WorkflowServiceStubs service = WorkflowServiceStubs.newServiceStubs(
                io.temporal.serviceclient.WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
        WorkflowClient client = WorkflowClient.newInstance(service);
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(taskQueue);
        worker.registerWorkflowImplementationTypes(ApprovalWorkflowImpl.class);
        worker.registerActivitiesImplementations(new ApprovalActivitiesImpl());
        factory.start();

        log.info("[temporal-poc] worker started target={} taskQueue={}", target, taskQueue);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("[temporal-poc] worker shutting down");
            factory.shutdownNow();
            service.shutdown();
        }));

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
