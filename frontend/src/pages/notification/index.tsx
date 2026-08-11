import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import {Tabs, Empty, Spin, Button, message, Badge} from 'antd';
import { getNotificationList, markRead, markAllRead, markReadByType } from '../../api/notification';
import { useNotificationStore } from '../../store/notificationStore';
import { formatRelativeTime } from '../../utils';
import type { NotificationVO } from '../../types';

const TYPE_TABS = [
  { key: 'all', label: '全部', value: undefined },
  { key: '1', label: '点赞', value: 1 },
  { key: '2', label: '评论', value: 2 },
  { key: '3', label: '关注', value: 3 },
  { key: '4', label: '系统', value: 4 },
];

const PAGE_SIZE = 20;

export default function NotificationPage() {
  const navigate = useNavigate();
  const fetchUnread = useNotificationStore((s) => s.fetchUnread);
  const [type, setType] = useState<string>('all');
  const [list, setList] = useState<NotificationVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);

  const load = async (pageNum: number, t: string) => {
    setLoading(true);
    try {
      const tv = TYPE_TABS.find(x => x.key === t)?.value;
      const resp = await getNotificationList({ type: tv, page: pageNum, size: PAGE_SIZE });
      const data = resp.data.data;
      setList(data.records || []);
      setTotal(data.total || 0);
      setPage(pageNum);
    } catch (e: any) {
      message.error(e.response?.data?.message || '加载通知失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(1, type); }, [type]);

  const handleRead = (n: NotificationVO) => {
    if (n.isRead === 1) {
      navigateToTarget(n);
      return;
    }
    markRead(n.id)
      .then(() => { fetchUnread(); load(page, type); })
      .catch(() => {});
    navigateToTarget(n);
  };

  const navigateToTarget = (n: NotificationVO) => {
    if (n.targetType === 1 && n.targetId) navigate(`/note/${n.targetId}`);
  };

  const handleMarkAll = () => {
    const tv = TYPE_TABS.find(x => x.key === type)?.value;
    const p = tv ? markReadByType(tv) : markAllRead();
    p.then(() => { fetchUnread(); load(1, type); }).catch(() => message.error('操作失败'));
  };

  return (
    <div style={{ maxWidth: 800, margin: '0 auto' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <Tabs activeKey={type} onChange={setType} items={TYPE_TABS.map(t => ({ key: t.key, label: t.label }))} />
        <Button type="link" onClick={handleMarkAll}>全部已读</Button>
      </div>

      {loading ? (
        <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" /></div>
      ) : list.length === 0 ? (
        <Empty description="暂无通知" style={{ padding: 60 }} />
      ) : (
        <div>
          {list.map(n => (
            <div key={n.id} style={{ cursor: 'pointer', opacity: n.isRead === 1 ? 0.6 : 1, padding: '12px 8px', borderBottom: '1px solid #f5f5f5' }} onClick={() => handleRead(n)}>
              <Badge dot={n.isRead === 0} offset={[6, 2]}>
                <span>{n.title}</span>
              </Badge>
              <div>{n.content}</div>
              <div style={{ color: '#999', fontSize: 12, marginTop: 4 }}>{formatRelativeTime(n.createdAt)}</div>
            </div>
          ))}
        </div>
      )}

      {!loading && total > PAGE_SIZE && (
        <div style={{ textAlign: 'center', padding: 16 }}>
          <Button disabled={page * PAGE_SIZE >= total} onClick={() => load(page + 1, type)}>加载更多</Button>
        </div>
      )}
    </div>
  );
}
