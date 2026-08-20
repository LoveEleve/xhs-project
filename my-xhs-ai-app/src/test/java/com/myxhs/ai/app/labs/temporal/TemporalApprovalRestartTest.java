package com.myxhs.ai.app.labs.temporal;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * D5 Temporal PoC：第二阶段测试——审批等待 + worker restart 恢复。
 *
 * 当前目的不是一次性把 restart 完全打通，而是把卡点定位清楚：
 * 1. workflow 是否仍存在
 * 2. signal 是否送达
 * 3. 新 worker 是否在继续 poll
 */
class TemporalApprovalRestartTest {

    @Test
    void waitingApproval_重启Worker后仍可批准完成() {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            WorkflowClient client = env.getWorkflowClient();

            WorkerFactory factory1 = env.getWorkerFactory();
            Worker worker1 = factory1.newWorker("approval-restart-task-queue");
            worker1.registerWorkflowImplementationTypes(ApprovalWorkflowImpl.class);
            worker1.registerActivitiesImplementations(new ApprovalActivitiesImpl());
            env.start();

            String workflowId = "approval-restart-demo";
            ApprovalWorkflow wf = client.newWorkflowStub(
                    ApprovalWorkflow.class,
                    WorkflowOptions.newBuilder()
                            .setWorkflowId(workflowId)
                            .setTaskQueue("approval-restart-task-queue")
                            .build());

            WorkflowClient.start(wf::start, "run-restart-1", "payload-restart");
            env.sleep(Duration.ofSeconds(1));
            assertEquals(WorkflowState.WAITING_APPROVAL, wf.queryState());

            // 模拟 worker 崩溃/重启：关停第一个 factory
            factory1.shutdownNow();
            factory1.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS);

            // 用同一个 test service + client 启第二个 worker factory（模拟重启后恢复）
            WorkerFactory factory2 = WorkerFactory.newInstance(client);
            Worker worker2 = factory2.newWorker("approval-restart-task-queue");
            worker2.registerWorkflowImplementationTypes(ApprovalWorkflowImpl.class);
            worker2.registerActivitiesImplementations(new ApprovalActivitiesImpl());
            factory2.start();
            env.sleep(Duration.ofSeconds(1));

            // 核心排查 1：workflow 仍可按 workflowId 重新获取 stub
            WorkflowStub recovered = client.newUntypedWorkflowStub(workflowId);
            assertNotNull(recovered);

            // 核心排查 2：signal 能否送达（不立即 getResult，先推进时间）
            recovered.signal("approve", "tester", "restart-approve");
            env.sleep(Duration.ofSeconds(2));

            // 核心排查 3：结果是否已完成
            // 注：若这里仍阻塞，说明 restart 后 workflow task 没被正确恢复/调度
            String result = recovered.getResult(String.class);
            assertEquals("COMPLETED", result);

            factory2.shutdownNow();
            factory2.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
