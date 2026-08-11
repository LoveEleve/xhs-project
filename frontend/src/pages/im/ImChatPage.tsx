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

  useEffect(() => {
    if (!peer) return;
    setLoading(true);
    getImMessages(peer)
      .then((resp) => { setMessages(resp.data.data.records || []); setLoading(false); })
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
            if (data.type === 'CHAT' && String(data.from) === peerRef.current) {
              setMessages(prev => [...prev, {
                id: data.msgId, senderId: String(data.from), receiverId: String(userId),
                content: data.content, msgType: data.msgType || 0, createdAt: new Date().toISOString(),
              }]);
              markImRead(peerRef.current).catch(() => {});
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
    const temp: ImMessageVO = {
      id: String(Date.now()), senderId: String(userId), receiverId: peer,
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
