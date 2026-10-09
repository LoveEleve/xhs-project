import { useState, useEffect, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Input, Button, Spin, message, Avatar } from 'antd';
import { UserOutlined } from '@ant-design/icons';
import { getImMessages, getWsTicket, markImRead } from '../../api/im';
import { useAuthStore } from '../../store/authStore';
import { formatDate } from '../../utils';
import type { ImMessageVO } from '../../types';

export default function ImChatPage() {
  const { peerId } = useParams<{ peerId: string }>();
  const navigate = useNavigate();
  const { userId } = useAuthStore();
  const peer = peerId as string;

  const [messages, setMessages] = useState<ImMessageVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [input, setInput] = useState('');
  const [connected, setConnected] = useState(false);
  const wsRef = useRef<WebSocket | null>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const peerRef = useRef(peer);
  peerRef.current = peer;

  // ===== IM 客户端契约（服务端为 at-least-once 投递）=====
  // 1. 收到任何 CHAT / OFFLINE 消息都必须回 ACK(msgId)：服务端据此清理离线持久副本，
  //    不回 ACK 会让离线副本按 7 天 TTL 反复补发；
  // 2. 必须按 msgId 去重：重复投递（重连补发/接收侧删除失败）是设计内的可能；
  // 3. 服务端在"推送成功"时也会删副本，ACK 是第二条保险。
  const seenMsgIdsRef = useRef<Set<string>>(new Set());
  const pendingTempIdsRef = useRef<string[]>([]);

  const sendAck = (msgId: unknown) => {
    if (msgId === undefined || msgId === null) return;
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ ver: 1, type: 'ACK', msgId }));
    }
  };

  const appendIncoming = (items: ImMessageVO[]) => {
    setMessages(prev => {
      const next = [...prev];
      for (const it of items) {
        const key = String(it.id);
        if (!seenMsgIdsRef.current.has(key)) {
          seenMsgIdsRef.current.add(key);
          next.push(it);
        }
      }
      return next;
    });
  };

  useEffect(() => {
    if (!peer) return;
    setLoading(true);
    getImMessages(peer)
      .then((resp) => {
        const history = resp.data.data.records || [];
        history.forEach((m: any) => seenMsgIdsRef.current.add(String(m.id)));
        setMessages(history);
        setLoading(false);
      })
      .catch((e) => { message.error(e.response?.data?.message || '加载消息失败'); setLoading(false); });

    // 连接 WebSocket
    let disposed = false;
    let pingTimer: ReturnType<typeof setInterval> | null = null;

    (async () => {
      try {
        const ticketResp = await getWsTicket();
        if (disposed) return;
        const ticket = ticketResp.data.data.ticket;
        const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
        const ws = new WebSocket(`${proto}://${window.location.host}/api/im/ws?ticket=${ticket}`);
        wsRef.current = ws;

        ws.onopen = () => {
          if (disposed) { ws.close(); return; }
          setConnected(true);
          markImRead(peerRef.current).catch(() => {});
          pingTimer = setInterval(() => {
            if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'PING' }));
          }, 30000);
        };

        ws.onmessage = (e) => {
          try {
            const data = JSON.parse(e.data);
            if (data.type === 'CHAT') {
              if (String(data.from) === peerRef.current) {
                appendIncoming([{
                  id: data.msgId, senderId: String(data.from), receiverId: String(userId),
                  content: data.content, msgType: data.msgType || 0, createdAt: new Date().toISOString(),
                }]);
                markImRead(peerRef.current).catch(() => {});
              }
              // 其他会话的消息也要 ACK（否则其离线副本会反复补发）
              sendAck(data.msgId);
            } else if (data.type === 'OFFLINE') {
              const all = (data.msgs || []) as any[];
              const incoming: ImMessageVO[] = all
                .filter(m => String(m.from) === peerRef.current)
                .map(m => ({
                  id: m.msgId, senderId: String(m.from), receiverId: String(userId),
                  content: m.content, msgType: m.msgType || 0,
                  createdAt: new Date(m.timestamp || Date.now()).toISOString(),
                }));
              if (incoming.length) {
                appendIncoming(incoming);
                markImRead(peerRef.current).catch(() => {});
              }
              all.forEach(m => sendAck(m.msgId));
            } else if (data.type === 'ACK') {
              // 发送侧：把乐观消息的临时 id 换成服务端 msgId（按发送顺序配对）
              const tempId = pendingTempIdsRef.current.shift();
              const realId = data.msgId !== undefined && data.msgId !== null ? String(data.msgId) : '';
              if (tempId && realId) {
                seenMsgIdsRef.current.add(realId);
                setMessages(prev => prev.map(m => (String(m.id) === tempId ? { ...m, id: realId } : m)));
              }
            } else if (data.type === 'NACK') {
              message.error(`发送失败：${data.reason || '请重试'}`);
              const tempId = pendingTempIdsRef.current.shift();
              if (tempId) {
                setMessages(prev => prev.filter(m => String(m.id) !== tempId));
              }
            }
          } catch { /* ignore */ }
        };

        ws.onclose = () => setConnected(false);
        ws.onerror = () => setConnected(false);
      } catch {
        if (!disposed) message.error('连接聊天失败');
      }
    })();

    return () => {
      disposed = true;
      if (pingTimer) clearInterval(pingTimer);
      wsRef.current?.close();
      wsRef.current = null;
    };
  }, [peer]);

  // 自动滚动到底部
  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages]);

  const send = () => {
    const content = input.trim();
    if (!content || !wsRef.current || wsRef.current.readyState !== WebSocket.OPEN) {
      if (content) message.warning(connected ? '连接未就绪' : '连接已断开');
      return;
    }
    const tempId = `temp-${Date.now()}`;
    pendingTempIdsRef.current.push(tempId);
    const temp: ImMessageVO = {
      id: tempId, senderId: String(userId), receiverId: peer,
      content, msgType: 0, createdAt: new Date().toISOString(),
    };
    setMessages(prev => [...prev, temp]);
    wsRef.current.send(JSON.stringify({ type: 'CHAT', to: peer, content }));
    setInput('');
  };

  if (loading) return <div style={{ textAlign: 'center', padding: 80 }}><Spin size="large" /></div>;

  return (
    <div style={{ maxWidth: 700, margin: '0 auto', display: 'flex', flexDirection: 'column', height: 'calc(100vh - 160px)' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '8px 0' }}>
        <Button type="link" onClick={() => navigate('/im')}>← 会话列表</Button>
        <span style={{ color: connected ? '#52c41a' : '#999', fontSize: 12 }}>
          {connected ? '● 在线' : '○ 离线'}
        </span>
      </div>

      <div ref={listRef} style={{ flex: 1, overflowY: 'auto', background: '#f7f7f7', borderRadius: 8, padding: 16 }}>
        {messages.map(m => {
          const mine = m.senderId === String(userId);
          return (
            <div key={m.id} style={{ display: 'flex', justifyContent: mine ? 'flex-end' : 'flex-start', marginBottom: 12 }}>
              {!mine && <Avatar size={28} icon={<UserOutlined />} style={{ marginRight: 8 }} />}
              <div style={{ maxWidth: '70%' }}>
                <div style={{
                  background: mine ? '#ff4d4f' : '#fff', color: mine ? '#fff' : '#333',
                  padding: '8px 12px', borderRadius: 8, wordBreak: 'break-word',
                }}>
                  {m.content}
                </div>
                <div style={{ fontSize: 11, color: '#999', marginTop: 2, textAlign: mine ? 'right' : 'left' }}>
                  {formatDate(m.createdAt, true)}
                </div>
              </div>
            </div>
          );
        })}
      </div>

      <div style={{ display: 'flex', gap: 8, padding: '12px 0' }}>
        <Input.TextArea
          rows={2}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="输入消息..."
          onPressEnter={(e) => { if (!e.shiftKey) { e.preventDefault(); send(); } }}
        />
        <Button type="primary" onClick={send} style={{ height: 'auto' }}>发送</Button>
      </div>
    </div>
  );
}
