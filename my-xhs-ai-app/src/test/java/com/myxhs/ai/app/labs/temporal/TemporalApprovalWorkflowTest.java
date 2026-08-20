package com.myxhs.ai.app.labs.temporal;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** D5 Temporal PoC：最小审批型长任务测试（先验证 WAITING_APPROVAL/APPROVED/REJECTED 语义）。 */
class TemporalApprovalWorkflowTest {

    @Test
    void 审批通过_完成() {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            Worker worker = env.newWorker("approval-task-queue");
            worker.registerWorkflowImplementationTypes(ApprovalWorkflowImpl.class);
            worker.registerActivitiesImplementations(new ApprovalActivitiesImpl());
            env.start();

            WorkflowClient client = env.getWorkflowClient();
            ApprovalWorkflow wf = client.newWorkflowStub(
                    ApprovalWorkflow.class,
                    WorkflowOptions.newBuilder().setTaskQueue("approval-task-queue").build());

            WorkflowClient.start(wf::start, "run-demo-1", "payload-demo");
            env.sleep(java.time.Duration.ofSeconds(1));
            assertEquals(WorkflowState.WAITING_APPROVAL, wf.queryState());

            wf.approve("tester", "approve-demo");
            env.sleep(java.time.Duration.ofSeconds(1));
            assertEquals(WorkflowState.COMPLETED, wf.queryState());
        }
    }

    @Test
    void 审批拒绝_终止() {
        try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
            Worker worker = env.newWorker("approval-task-queue");
            worker.registerWorkflowImplementationTypes(ApprovalWorkflowImpl.class);
            worker.registerActivitiesImplementations(new ApprovalActivitiesImpl());
            env.start();

            WorkflowClient client = env.getWorkflowClient();
            ApprovalWorkflow wf = client.newWorkflowStub(
                    ApprovalWorkflow.class,
                    WorkflowOptions.newBuilder().setTaskQueue("approval-task-queue").build());

            WorkflowClient.start(wf::start, "run-demo-2", "payload-demo");
            env.sleep(java.time.Duration.ofSeconds(1));
            assertEquals(WorkflowState.WAITING_APPROVAL, wf.queryState());

            wf.reject("tester", "reject-demo");
            env.sleep(java.time.Duration.ofSeconds(1));
            assertEquals(WorkflowState.REJECTED, wf.queryState());
        }
    }
}
