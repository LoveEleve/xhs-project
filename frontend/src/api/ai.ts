// ============================================================
// AI 诊断台（Agent Console）API 客户端 —— M8-4 UI 薄壳
// 只消费 my-xhs-ai-app 的 Run 端点（薄壳铁律：无业务逻辑）
// 开发环境经 vite proxy /ai-api → http://localhost:19020
// ============================================================
import axios from 'axios';

// --- Harness 事件（SSE 载荷，与后端 HarnessEvent 一一对应）---
export interface HarnessEvent {
  runId: string;
  type: string;
  stepNumber: number;
  tool?: string;
  window?: string;
  evidenceRefs?: string[];
  terminationReason?: string;
  message?: string;
}

// --- Run 完整视图（GET /api/runs/{id}）---
export interface RunStep {
  stepNumber: number;
  state?: string;
  action?: string;
  tool?: string;
  reasoning?: string;
  toolResult?: string;
  evidenceRefs?: string[];
}

export interface TraceServiceProfile {
  service: string;
  layer: string;
  role: string;
  followupCodeQuestion?: string;
  keyServices: string[];
  keyControllers: string[];
  keyConsumers: string[];
  source: string;
  owner?: { name: string; email: string; commit: string; summary: string };
  recentCommits: string[];
  callChainHints: string[];
  primaryController: string;
  primaryControllerPath: string;
  primaryControllerSnippet: string;
  primaryService: string;
  primaryServicePath: string;
  primaryServiceSnippet: string;
  nextHops: string[];
  classSources: Array<{ className: string; filePath: string; snippet: string }>;
}

export interface RecommendedFollowup {
  text: string;
  kind: string;
  priority: number;
  suggestedConversationInput: string;
}

export interface TraceDiagnosis {
  traceId: string;
  source: string;
  recommendedFollowups?: RecommendedFollowup[];
  verdict: 'complete' | 'continue' | 'blocked' | 'uncertain' | string;
  verificationStatus: string;
  reviewerMode?: string;
  reviewerRationale?: string;
  suspiciousEvents: string[];
  hypotheses: string[];
  nextActions: string[];
  entryService?: string;
  lastService?: string;
  hitServices: string[];
  hitServiceDetails?: Array<{ service: string; layer: string; matches: number }>;
  serviceProfiles: TraceServiceProfile[];
  renderedAnswer?: string;
}

export interface CodeSearchHit {
  service: string;
  primaryService?: string;
  primaryServicePath?: string;
  methodHint?: string;
  relatedTraceSamples?: Array<{ id: string; traceId: string; route: string; note: string }>;
}

export interface CodeSearchResultView {
  query: string;
  summary: string;
  topHit: CodeSearchHit;
  recommendedFollowups?: RecommendedFollowup[];
}

export interface RunView {
  runId: string;
  status: string;
  terminationReason?: string;
  query: string;
  steps: RunStep[];
  evidence: string[];
  finalAnswer?: string;
  costMs: number;
  note?: string;
  traceDiagnosis?: TraceDiagnosis;
  codeSearch?: CodeSearchResultView;
}

export const TERMINAL_TYPES = new Set(['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED']);
export const LIVE_TYPES = ['RUN_STARTED', 'THINK', 'POLICY_DENIED', 'TOOL', 'ANSWER',
  'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED', 'WAITING_APPROVAL', 'APPROVAL_RESULT'] as const;

const client = axios.create({
  baseURL: '/ai-api',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
});

export interface RunSubmitResponse {
  runId: string;
  status: string;
  /** M10 多轮会话：本次提交归属的会话（无显式 convId 时后端新建） */
  conversationId: string;
}

export interface FollowupMeta {
  sourceKind: 'codeSearch' | 'traceDiagnosis';
  sourceText: string;
  sourceRunId?: string;
  sourceService?: string;
}

export const submitRun = (message: string, conversationId?: string, followupMeta?: FollowupMeta) =>
  client.post<RunSubmitResponse>('/api/runs', {
    message,
    ...(conversationId ? { conversationId } : {}),
    ...(followupMeta ? { followupMeta } : {}),
  }).then((r) => r.data);

export const getRun = (runId: string) =>
  client.get<RunView>(`/api/runs/${runId}`).then((r) => r.data);

export const cancelRun = (runId: string) =>
  client.delete<{ runId: string; status: string }>(`/api/runs/${runId}`).then((r) => r.data);

/** M11 HITL 审批：approve → 恢复执行被审批工具；reject → CANCELLED */
export const approveRun = (runId: string, decision: 'approve' | 'reject', reason?: string) =>
  client.post<{ runId: string; decision: string; status: string }>(`/api/runs/${runId}/approve`, {
    decision,
    reason: reason || '',
  }).then((r) => r.data);

export interface RunView {
  runId: string;
  status: string;
  terminationReason?: string;
  query: string;
  steps: RunStep[];
  evidence: string[];
  finalAnswer?: string;
  costMs: number;
  /** 零步骤直答（问候/闲聊）说明，后端 view 提供 */
  note?: string;
  /** M11 HITL：WAITING_APPROVAL 时待审批工具与参数 */
  pendingTool?: string;
  pendingApproval?: Record<string, string>;
  traceDiagnosis?: TraceDiagnosis;
  codeSearch?: CodeSearchResultView;
}

/** 订阅 run 事件流（GET /api/runs/{id}/stream，SSE 命名事件；返回调用方负责 close） */
export const openRunStream = (runId: string, onEvent: (e: HarnessEvent) => void): EventSource => {
  const es = new EventSource(`/ai-api/api/runs/${runId}/stream`);
  LIVE_TYPES.forEach((t) => {
    es.addEventListener(t, (ev) => {
      try {
        onEvent(JSON.parse((ev as MessageEvent).data) as HarnessEvent);
      } catch {
        // 忽略畸形事件（防御性，SSE 数据格式异常不崩页面）
      }
    });
  });
  return es;
};
