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
}

export const TERMINAL_TYPES = new Set(['COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED']);
export const LIVE_TYPES = ['RUN_STARTED', 'THINK', 'POLICY_DENIED', 'TOOL', 'ANSWER',
  'COMPLETED', 'PARTIAL', 'FAILED', 'CANCELLED'] as const;

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

export const submitRun = (message: string, conversationId?: string) =>
  client.post<RunSubmitResponse>('/api/runs', conversationId
    ? { message, conversationId }
    : { message }).then((r) => r.data);

export const getRun = (runId: string) =>
  client.get<RunView>(`/api/runs/${runId}`).then((r) => r.data);

export const cancelRun = (runId: string) =>
  client.delete<{ runId: string; status: string }>(`/api/runs/${runId}`).then((r) => r.data);

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
