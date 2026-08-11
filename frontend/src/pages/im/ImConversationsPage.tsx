import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Avatar, Badge, Spin, Empty, message} from 'antd';
import { UserOutlined } from '@ant-design/icons';
import { getImConversations } from '../../api/im';
import { formatRelativeTime } from '../../utils';
import type { ConversationVO } from '../../types';

export default function ImConversationsPage() {
  const navigate = useNavigate();
  const [list, setList] = useState<ConversationVO[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    getImConversations()
      .then((resp) => { setList(resp.data.data.records || []); setLoading(false); })
      .catch((e) => { message.error(e.response?.data?.message || '加载会话失败'); setLoading(false); });
  }, []);

  if (loading) return <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" /></div>;
  if (list.length === 0) return <Empty description="暂无会话" style={{ padding: 80 }} />;

  return (
    <div style={{ maxWidth: 700, margin: '0 auto' }}>
      <h2 style={{ marginBottom: 16 }}>消息</h2>
      <div>
        {list.map(c => (
          <div key={c.peerId} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '12px 4px', borderBottom: '1px solid #f0f0f0', cursor: 'pointer' }} onClick={() => navigate(`/im/${c.peerId}`)}>
            <Badge count={c.unreadCount} size="small" offset={[2, -2]}>
              <Avatar src={c.peerAvatar} icon={<UserOutlined />}>{c.peerName?.[0]}</Avatar>
            </Badge>
            <div style={{ flex: 1 }}>
              <div>{c.peerName}</div>
              <div style={{ color: '#999', fontSize: 13 }}>{c.lastContent}</div>
            </div>
            <div style={{ color: '#999', fontSize: 12 }}>{formatRelativeTime(c.updatedAt)}</div>
          </div>
        ))}
      </div>
    </div>
  );
}
