// ============================================================
// AI 诊断台（Agent Console）—— M8-4 UI 薄壳
// 只消费 Run 端点：提交 / SSE 实时进度 / 证据链可点 / 取消
// 薄壳铁律：无业务逻辑，不做推断，一切数据来自后端
// ============================================================
import { useCallback, useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Card, Input, Button, Timeline, Tag, Drawer, Alert, Space,
  Typography, Empty, Spin, Divider, message,
} from 'antd';
import {
  submitRun, getRun, cancelRun, approveRun, openRunStream, TERMINAL_TYPES,
} from '../../api/ai';
import type { HarnessEvent, RunStep } from '../../api/ai';

const { TextArea } = Input;
const { Text, Paragraph } = Typography;

const PREVIEW_CHARS = 400;

interface DisplayStep {
  key: string;
  kind: string;
  stepNumber: number;
  tool?: string;
  window?: string;
  reasoning?: string;
  result?: string;
  evidenceRefs: string[];
}

const TERMINAL_STATUS_COLOR: Record<string, string> = {
  COMPLETED: 'green',
  PARTIAL: 'orange',
  FAILED: 'red',
  CANCELLED: 'default',
};

function truncate(text: string, max: number) {
  return text.length > max ? `${text.slice(0, max)}…` : text;
}

export default function AgentConsolePage() {
  const [searchParams, setSearchParams] = useSearchParams();

  const [query, setQuery] = useState('');
  const [runId, setRunId] = useState(searchParams.get('run') || '');
  // M10 多轮会话：?conv= 恢复上次会话（连续提问同上下文）；"新建会话"清空
  const [convId, setConvId] = useState(searchParams.get('conv') || '');
  const [phase, setPhase] = useState<'idle' | 'running' | 'done' | 'approval'>('idle');
  const [status, setStatus] = useState<string | undefined>();
  const [terminationReason, setTerminationReason] = useState<string | undefined>();
  const [finalAnswer, setFinalAnswer] = useState<string | undefined>();
  const [costMs, setCostMs] = useState<number | undefined>();
  const [steps, setSteps] = useState<DisplayStep[]>([]);
  const [runNote, setRunNote] = useState<string | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [detail, setDetail] = useState<DisplayStep | null>(null);
  const [loading, setLoading] = useState(false);
  // M11 HITL：WAITING_APPROVAL 时的待审批信息 + 拒绝理由输入
  const [pendingTool, setPendingTool] = useState<string | undefined>();
  const [pendingApproval, setPendingApproval] = useState<Record<string, string> | undefined>();
  const [rejectReason, setRejectReason] = useState('');
  const [approving, setApproving] = useState(false);
  const esRef = useRef<EventSource | null>(null);

  const closeStream = useCallback(() => {
    esRef.current?.close();
    esRef.current = null;
  }, []);

  const applyEvent = useCallback((ev: HarnessEvent) => {
    const base = { key: `${ev.type}-${ev.stepNumber}`, stepNumber: ev.stepNumber, evidenceRefs: ev.evidenceRefs || [] };
    if (ev.type === 'RUN_STARTED') {
      if (ev.message) setRunNote(ev.message);
    } else if (ev.type === 'THINK') {
      setSteps((s) => [...s, { ...base, kind: 'THINK', reasoning: ev.message }]);
    } else if (ev.type === 'POLICY_DENIED') {
      setSteps((s) => [...s, { ...base, kind: 'POLICY_DENIED', tool: ev.tool, reasoning: ev.message }]);
    } else if (ev.type === 'TOOL') {
      setSteps((s) => [...s, { ...base, kind: 'TOOL', tool: ev.tool, window: ev.window, result: ev.message }]);
    } else if (ev.type === 'ANSWER') {
      setSteps((s) => [...s, { ...base, kind: 'ANSWER', reasoning: ev.message }]);
    } else if (ev.type === 'WAITING_APPROVAL') {
      // M11 HITL：挂起 → 拉取视图（pendingTool/pendingApproval）显示审批卡片
      if (ev.message) setRunNote(ev.message);
      if (runId) loadView(runId);
    } else if (TERMINAL_TYPES.has(ev.type)) {
      setStatus(ev.type);
      setTerminationReason(ev.terminationReason);
      if (ev.message) setFinalAnswer(ev.message);
    }
  }, []);

  const watchRun = useCallback((id: string, initialStatus?: string) => {
    closeStream();
    const es = openRunStream(id, (ev) => {
      // WAITING_APPROVAL：挂起后无更多事件，流关闭（审批卡片由 applyEvent→loadView 渲染）
      if (TERMINAL_TYPES.has(ev.type) || ev.type === 'WAITING_APPROVAL') {
        es.close();
        esRef.current = null;
      }
      applyEvent(ev);
    });
    esRef.current = es;
    if (initialStatus) {
      setStatus(initialStatus);
      setPhase('running');
    }
  }, [applyEvent, closeStream]);

  // 终态（含 SSE 补发触达）→ 拉全量视图取权威 steps/evidence/costMs
  const fetchedRef = useRef('');
  useEffect(() => {
    if (!runId || !status || !TERMINAL_TYPES.has(status) || fetchedRef.current === runId) return;
    fetchedRef.current = runId;
    (async () => {
      try {
        const view = await getRun(runId);
        setPhase('done');
        setStatus(view.status);
        setTerminationReason(view.terminationReason);
        if (view.finalAnswer) setFinalAnswer(view.finalAnswer);
        setCostMs(view.costMs);
        if (view.note) setRunNote(view.note);
        const mapped: DisplayStep[] = (view.steps || []).map((s: RunStep) => ({
          key: `${s.state || 'STEP'}-${s.stepNumber}`,
          kind: s.state || s.action || 'STEP',
          stepNumber: s.stepNumber,
          tool: s.tool,
          reasoning: s.reasoning,
          result: s.toolResult,
          evidenceRefs: s.evidenceRefs || [],
        }));
        setSteps(mapped);
      } catch {
        // 拉全量视图失败不阻断：保留 SSE 已渲染的步骤
      }
    })();
  }, [runId, status]);

  const loadView = useCallback(async (id: string) => {
    try {
      const view = await getRun(id);
      if (view.status === 'RUNNING') {
        setStatus('RUNNING');
        setPhase('running');
        watchRun(id, 'RUNNING');
        return;
      }
      if (view.status === 'WAITING_APPROVAL') {
        // M11 HITL：挂起待审批（不订阅 SSE——run 已暂停，审批后刷新拉取）
        setPhase('approval');
        setStatus('WAITING_APPROVAL');
        setPendingTool(view.pendingTool);
        setPendingApproval(view.pendingApproval);
        setSteps([]);
        setFinalAnswer(undefined);
        return;
      }
      setPhase('done');
      setStatus(view.status);
      setTerminationReason(view.terminationReason);
      setFinalAnswer(view.finalAnswer);
      setCostMs(view.costMs);
      if (view.note) setRunNote(view.note);
      fetchedRef.current = id;
      const mapped: DisplayStep[] = (view.steps || []).map((s: RunStep) => ({
        key: `${s.state || 'STEP'}-${s.stepNumber}`,
        kind: s.state || s.action || 'STEP',
        stepNumber: s.stepNumber,
        tool: s.tool,
        reasoning: s.reasoning,
        result: s.toolResult,
        evidenceRefs: s.evidenceRefs || [],
      }));
      setSteps(mapped);
    } catch {
      setErrorMsg('加载 run 失败：该 run 不存在（历史 run 已落库可追溯，可检查 runId 是否正确）');
      setPhase('idle');
    }
  }, [watchRun]);

  // runId 单一驱动：新提交与打开已有 run（?run=xxx）共用此路径
  useEffect(() => {
    if (!runId) return;
    loadView(runId);
    return closeStream;
  }, [runId, loadView, closeStream]);

  useEffect(() => () => closeStream(), [closeStream]);

  const handleSubmit = async () => {
    if (!query.trim()) {
      message.warning('请输入诊断问题');
      return;
    }
    setErrorMsg(null);
    setLoading(true);
    setSteps([]);
    setRunNote(null);
    setFinalAnswer(undefined);
    setStatus(undefined);
    setTerminationReason(undefined);
    setCostMs(undefined);
    fetchedRef.current = '';
    try {
      const { runId: id, conversationId: cid } = await submitRun(query.trim(), convId || undefined);
      setRunId(id);
      if (cid) setConvId(cid);
      setSearchParams({ run: id, conv: cid }, { replace: true });
    } catch (e) {
      const err = e as { response?: { status?: number; data?: { message?: string } } };
      const status409 = err.response?.status === 409;
      setErrorMsg(status409
        ? '该会话有进行中的诊断（同会话串行），请等待完成后再提问，或新建会话'
        : `提交失败: ${(e as Error).message}`);
      setPhase('idle');
    } finally {
      setLoading(false);
    }
  };

  /** 新建会话：清空会话关联（下次提交后端新建 convId） */
  const handleNewConversation = () => {
    setConvId('');
    setRunId('');
    setSearchParams({}, { replace: true });
    setSteps([]);
    setRunNote(null);
    setFinalAnswer(undefined);
    setStatus(undefined);
    setPhase('idle');
    message.info('已新建会话（后续提问不再带上轮上下文）');
  };

  const handleCancel = async () => {
    if (!runId) return;
    try {
      await cancelRun(runId);
      message.info('已发送取消请求（当前步完成后生效）');
    } catch (e) {
      message.error(`取消失败: ${(e as Error).message}`);
    }
  };

  /** M11 HITL 审批：通过/拒绝 → 刷新视图（resume 后 RUNNING/终态） */
  const handleApprove = async (decision: 'approve' | 'reject') => {
    if (!runId || !pendingTool) return;
    if (decision === 'reject' && !rejectReason.trim()) {
      message.warning('拒绝需填写理由（审计要求）');
      return;
    }
    setApproving(true);
    try {
      await approveRun(runId, decision, rejectReason);
      message.success(decision === 'approve' ? '已通过审批，Agent 恢复执行' : '已拒绝，任务终止');
      setPhase('running');
      setStatus('RUNNING');
      setPendingTool(undefined);
      setPendingApproval(undefined);
      setRejectReason('');
      // 审批后轮询：resume 执行 → 终态；或拒绝 → CANCELLED
      loadView(runId);
    } catch (e) {
      const err = e as { response?: { status?: number } };
      message.error(err.response?.status === 409
        ? '该审批已处理或 run 状态已变化，请刷新'
        : `审批失败: ${(e as Error).message}`);
    } finally {
      setApproving(false);
    }
  };

  const timelineItems = steps.map((s) => {
    const color =
      s.kind === 'TOOL' ? 'blue' :
      s.kind === 'POLICY_DENIED' ? 'red' :
      s.kind === 'ANSWER' ? 'green' : 'gray';
    const label =
      s.kind === 'TOOL' ? (
        <Space size={4}>
          <Tag color="blue">{s.tool}</Tag>
          {s.window && <Tag>{s.window}</Tag>}
          <Text type="secondary">step {s.stepNumber}</Text>
        </Space>
      ) : (
        <Space size={4}>
          <Tag>{s.kind}</Tag>
          <Text type="secondary">step {s.stepNumber}</Text>
        </Space>
      );
    const content =
      s.kind === 'TOOL' ? (
        <a onClick={() => setDetail(s)}>
          <Text code style={{ fontSize: 12, whiteSpace: 'pre-wrap' }}>
            {truncate(s.result || '', PREVIEW_CHARS)}
          </Text>
          {s.evidenceRefs.length > 0 && (
            <Text type="secondary" style={{ marginLeft: 8, fontSize: 12 }}>
              [{s.evidenceRefs.join(', ')}] 点击查看全文
            </Text>
          )}
        </a>
      ) : (
        <Text type="secondary" style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}>
          {truncate(s.reasoning || '', PREVIEW_CHARS)}
        </Text>
      );
    return { color, title: label, content };
  });

  const running = phase === 'running';

  return (
    <div style={{ maxWidth: 900, margin: '0 auto' }}>
      <Card title="AI 诊断台" style={{ marginBottom: 16 }}>
        <Space orientation="vertical" style={{ width: '100%' }} size={12}>
          <TextArea
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="输入运营/运维诊断问题，例如：为什么订单量下降了？"
            autoSize={{ minRows: 2, maxRows: 5 }}
            disabled={running}
          />
          <Space>
            <Button type="primary" onClick={handleSubmit} loading={loading} disabled={running}>
              开始诊断
            </Button>
            <Button danger disabled={!running} onClick={handleCancel}>
              取消
            </Button>
            <Button disabled={running} onClick={handleNewConversation}>
              新建会话
            </Button>
            {runId && (
              <Text type="secondary" copyable={{ text: runId }} style={{ fontSize: 12 }}>
                run: {runId}
              </Text>
            )}
          </Space>
          {convId ? (
            <Text type="secondary" copyable={{ text: convId }} style={{ fontSize: 12 }}>
              会话: {convId.slice(0, 12)}…（多轮上下文已开启）
            </Text>
          ) : (
            <Text type="secondary" style={{ fontSize: 12 }}>
              会话: 新建（本次提问独立上下文）
            </Text>
          )}
        </Space>
      </Card>

      {errorMsg && (
        <Alert type="error" showIcon message={errorMsg} style={{ marginBottom: 16 }} closable
          onClose={() => setErrorMsg(null)} />
      )}

      {phase === 'approval' && pendingTool && (
        <Card title="L3 高危操作审批（HITL）" style={{ marginBottom: 16 }}
          extra={<Tag color="orange">WAITING_APPROVAL</Tag>}>
          <Space direction="vertical" style={{ width: '100%' }} size={12}>
            <Alert type="warning" showIcon
              message={`Agent 请求执行高危工具：${pendingTool}`}
              description={pendingApproval ? `参数：${Object.entries(pendingApproval)
                .map(([k, v]) => `${k}=${v}`).join('，')}` : undefined} />
            <Input.TextArea
              value={rejectReason}
              onChange={(e) => setRejectReason(e.target.value)}
              placeholder="拒绝理由（拒绝必填，审批审计要求）"
              autoSize={{ minRows: 2, maxRows: 4 }}
            />
            <Space>
              <Button type="primary" loading={approving} onClick={() => handleApprove('approve')}>
                审批通过
              </Button>
              <Button danger loading={approving} onClick={() => handleApprove('reject')}>
                拒绝
              </Button>
            </Space>
          </Space>
        </Card>
      )}

      {runId && !errorMsg && (
        <Card title="执行过程" extra={status && <Tag color={TERMINAL_STATUS_COLOR[status] || 'processing'}>{status}</Tag>}>
          {running ? (
            <Spin description="Agent 执行中…">
              <div style={{ minHeight: 60 }} />
            </Spin>
          ) : null}
          {steps.length > 0 ? (
            <Timeline items={timelineItems} />
          ) : (
            <>
              <Empty description={running ? '等待执行事件…' : '暂无执行步骤'} />
              {/* 零步骤完成（问候/闲聊直答）：说明事件流给出的原因，避免"空执行"误解 */}
              {!running && runNote && (
                <Alert type="info" showIcon style={{ marginTop: 12 }}
                  title="未执行工具调查" description={runNote} />
              )}
            </>
          )}
          {phase === 'done' && (
            <>
              <Divider />
              <Paragraph>
                <Text strong>结论：</Text>
              </Paragraph>
              <Paragraph style={{ whiteSpace: 'pre-wrap', background: '#fafafa', padding: 12, borderRadius: 8 }}>
                {finalAnswer || '（无最终答案）'}
              </Paragraph>
              <Space size={16} wrap>
                <Text type="secondary">终止原因: {terminationReason || '—'}</Text>
                {costMs !== undefined && <Text type="secondary">耗时: {(costMs / 1000).toFixed(1)}s</Text>}
              </Space>
            </>
          )}
        </Card>
      )}

      <Drawer
        title={`工具结果全文${detail ? ` — ${detail.tool}` : ''}`}
        open={!!detail}
        onClose={() => setDetail(null)}
        size={640}
      >
        {detail && (
          <>
            <Paragraph>
              <Text type="secondary">window: {detail.window || '—'}</Text>
              {detail.evidenceRefs.length > 0 && (
                <span style={{ marginLeft: 12 }}>
                  <Text type="secondary">evidenceRefs: [{detail.evidenceRefs.join(', ')}]</Text>
                </span>
              )}
            </Paragraph>
            <Paragraph style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}>
              {detail.result}
            </Paragraph>
          </>
        )}
      </Drawer>
    </div>
  );
}
