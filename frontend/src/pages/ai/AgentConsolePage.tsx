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
  submitRun, getRun, cancelRun, openRunStream, TERMINAL_TYPES,
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
  const [phase, setPhase] = useState<'idle' | 'running' | 'done'>('idle');
  const [status, setStatus] = useState<string | undefined>();
  const [terminationReason, setTerminationReason] = useState<string | undefined>();
  const [finalAnswer, setFinalAnswer] = useState<string | undefined>();
  const [costMs, setCostMs] = useState<number | undefined>();
  const [steps, setSteps] = useState<DisplayStep[]>([]);
  const [runNote, setRunNote] = useState<string | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [detail, setDetail] = useState<DisplayStep | null>(null);
  const [loading, setLoading] = useState(false);
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
    } else if (TERMINAL_TYPES.has(ev.type)) {
      setStatus(ev.type);
      setTerminationReason(ev.terminationReason);
      if (ev.message) setFinalAnswer(ev.message);
    }
  }, []);

  const watchRun = useCallback((id: string, initialStatus?: string) => {
    closeStream();
    const es = openRunStream(id, (ev) => {
      if (TERMINAL_TYPES.has(ev.type)) {
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
      const { runId: id } = await submitRun(query.trim());
      setRunId(id);
      setSearchParams({ run: id }, { replace: true });
    } catch (e) {
      setErrorMsg(`提交失败: ${(e as Error).message}`);
      setPhase('idle');
    } finally {
      setLoading(false);
    }
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
            {runId && (
              <Text type="secondary" copyable={{ text: runId }} style={{ fontSize: 12 }}>
                run: {runId}
              </Text>
            )}
          </Space>
        </Space>
      </Card>

      {errorMsg && (
        <Alert type="error" showIcon message={errorMsg} style={{ marginBottom: 16 }} closable
          onClose={() => setErrorMsg(null)} />
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
