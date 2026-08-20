import { useState, useEffect } from 'react';
import { Avatar, Button, Spin, Empty, message} from 'antd';
import { UserOutlined } from '@ant-design/icons';
import { getBlockList, unblockUser } from '../../api/auth';
import { getUserPublicInfo } from '../../api/auth';
import type { UserPublicInfoResponse } from '../../types';

interface BlockItem {
  userId: string;
  user?: UserPublicInfoResponse;
}

export default function BlockPage() {
  const [items, setItems] = useState<BlockItem[]>([]);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    try {
      const resp = await getBlockList();
      const ids = (resp.data.data || []) as Array<number | string>;
      const enriched = await Promise.all(ids.map(async (raw) => {
        // 雪花 ID 超 JS 安全整数，保持字符串防精度丢失
        const userId = String(raw);
        try {
          const u = (await getUserPublicInfo(userId)).data.data;
          return { userId, user: u };
        } catch { return { userId }; }
      }));
      setItems(enriched);
    } catch (e: any) {
      message.error(e.response?.data?.message || '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const handleUnblock = (userId: string) => {
    unblockUser(userId)
      .then(() => { message.success('已解除拉黑'); load(); })
      .catch((e: any) => message.error(e.response?.data?.message || '操作失败'));
  };

  if (loading) return <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" /></div>;
  if (items.length === 0) return <Empty description="暂无拉黑用户" style={{ padding: 60 }} />;

  return (
    <div style={{ maxWidth: 700, margin: '0 auto' }}>
      <h2 style={{ marginBottom: 16 }}>拉黑列表</h2>
      <div>
        {items.map(item => (
          <div key={item.userId} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px 0', borderBottom: '1px solid #f0f0f0' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              <Avatar src={item.user?.avatar} icon={<UserOutlined />}>{item.user?.nickname?.[0]}</Avatar>
              <span>{item.user?.nickname || `用户${item.userId}`}</span>
            </div>
            <Button size="small" danger onClick={() => handleUnblock(item.userId)}>解除拉黑</Button>
          </div>
        ))}
      </div>
    </div>
  );
}
