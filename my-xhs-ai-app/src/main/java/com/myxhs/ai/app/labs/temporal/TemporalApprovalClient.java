package com.myxhs.ai.app.labs.temporal;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;

/**
 * D5 Temporal PoC：client 入口（start / approve / reject / query / result）。
 *
 * 用法：
 *   java ... TemporalApprovalClient start <workflowId> <runId> <payload>
 *   java ... TemporalApprovalClient approve <workflowId> <approver> <reason>
 *   java ... TemporalApprovalClient reject <workflowId> <approver> <reason>
 *   java ... TemporalApprovalClient query <workflowId>
 *   java ... TemporalApprovalClient result <workflowId>
 */
public class TemporalApprovalClient {

    public static void main(String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException("用法: start/approve/reject/query/result ...");
        }
        String action = args[0];
        String workflowId = args[1];
        String target = System.getenv().getOrDefault("TEMPORAL_TARGET", "127.0.0.1:7233");
        String taskQueue = System.getenv().getOrDefault("TEMPORAL_TASK_QUEUE", "approval-restart-task-queue");

        WorkflowServiceStubs service = WorkflowServiceStubs.newServiceStubs(
                io.temporal.serviceclient.WorkflowServiceStubsOptions.newBuilder().setTarget(target).build());
        WorkflowClient client = WorkflowClient.newInstance(service);
        try {
            switch (action) {
                case "start" -> {
                    String runId = args.length > 2 ? args[2] : workflowId;
                    String payload = args.length > 3 ? args[3] : "payload-demo";
                    ApprovalWorkflow wf = client.newWorkflowStub(
                            ApprovalWorkflow.class,
                            WorkflowOptions.newBuilder().setWorkflowId(workflowId).setTaskQueue(taskQueue).build());
                    WorkflowClient.start(wf::start, runId, payload);
                    System.out.println("STARTED " + workflowId);
                }
                case "approve" -> {
                    WorkflowStub stub = client.newUntypedWorkflowStub(workflowId);
                    String approver = args.length > 2 ? args[2] : "tester";
                    String reason = args.length > 3 ? args[3] : "approve-demo";
                    stub.signal("approve", approver, reason);
                    System.out.println("APPROVED " + workflowId);
                }
                case "reject" -> {
                    WorkflowStub stub = client.newUntypedWorkflowStub(workflowId);
                    String approver = args.length > 2 ? args[2] : "tester";
                    String reason = args.length > 3 ? args[3] : "reject-demo";
                    stub.signal("reject", approver, reason);
                    System.out.println("REJECTED " + workflowId);
                }
                case "query" -> {
                    WorkflowStub stub = client.newUntypedWorkflowStub(workflowId);
                    System.out.println(stub.query("queryState", WorkflowState.class).name());
                }
                case "result" -> {
                    WorkflowStub stub = client.newUntypedWorkflowStub(workflowId);
                    System.out.println(stub.getResult(String.class));
                }
                default -> throw new IllegalArgumentException("未知 action: " + action);
            }
        } finally {
            service.shutdown();
        }
    }
}
