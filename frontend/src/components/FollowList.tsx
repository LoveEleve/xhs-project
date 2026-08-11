import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Avatar, Button, Spin, Empty, message, Pagination, Tag} from 'antd';
import { UserOutlined } from '@ant-design/icons';
import { getFollowing, getFollowers, follow, unfollow } from '../api/social';
import { getUserPublicInfo } from '../api/auth';
import { useAuthStore } from '../store/authStore';
import type { UserPublicInfoResponse } from '../types';

const PAGE_SIZE = 20;

interface FollowItem {
  userId: string | number;
  followedAt?: string;
  isFollowBack: boolean;
  user?: UserPublicInfoResponse;
}

interface FollowListProps {
  mode: 'following' | 'follower';
  userId: string | number;
}

export default function FollowList({ mode, userId }: FollowListProps) {
  const navigate = useNavigate();
  const { userId: myId } = useAuthStore();
  const [items, setItems] = useState<FollowItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [pageNum, setPageNum] = useState(1);
  const [total, setTotal] = useState(0);

  const load = async (page: number) => {
    setLoading(true);
    try {
      const resp = mode === 'following'
        ? await getFollowing(userId, page, PAGE_SIZE)
        : await getFollowers(userId, page, PAGE_SIZE);
      const list = (resp.data.data.list || []) as { userId: string; followedAt?: string; isFollowBack: boolean }[];
      setTotal(resp.data.data.total || 0);
      // 补齐昵称/头像（FollowVO 里可能为空）
      const enriched = await Promise.all(list.map(async (it) => {
        try {
          const u = (await getUserPublicInfo(it.userId)).data.data;
          return { ...it, user: u };
        } catch { return { ...it }; }
      }));
      setItems(enriched);
    } catch (e: any) {
      message.error(e.response?.data?.message || '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(1); }, [mode, userId]);

  const toggleFollow = async (item: FollowItem) => {
    const old = item.isFollowBack;
    setItems(prev => prev.map(i => i.userId === item.userId ? { ...i, isFollowBack: !old } : i));
    try {
      if (old) await unfollow(item.userId);
      else await follow(item.userId);
    } catch (e: any) {
      setItems(prev => prev.map(i => i.userId === item.userId ? { ...i, isFollowBack: old } : i));
      message.error(e.response?.data?.message || '操作失败');
    }
  };

  if (loading) return <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" /></div>;
  if (items.length === 0) return <Empty description={mode === 'following' ? '暂无关注' : '暂无粉丝'} style={{ padding: 60 }} />;

  return (
    <div>
      <div>
        {items.map(item => {
          const isSelf = myId === item.userId;
          return (
            <div key={item.userId} style={{
              display: 'flex', justifyContent: 'space-between', alignItems: 'center',
              padding: '12px 0', borderBottom: '1px solid #f0f0f0',
            }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <Avatar src={item.user?.avatar} icon={<UserOutlined />}>{item.user?.nickname?.[0]}</Avatar>
                <div>
                  <div><a onClick={() => navigate(`/user/${item.userId}`)}>{item.user?.nickname || `用户${item.userId}`}</a></div>
                  {item.isFollowBack && <Tag color="blue">已互关</Tag>}
                </div>
              </div>
              {!isSelf && (
                <Button size="small" type={item.isFollowBack ? 'default' : 'primary'} onClick={() => toggleFollow(item)}>
                  {item.isFollowBack ? '已关注' : '关注'}
                </Button>
              )}
            </div>
          );
        })}
      </div>
      {total > PAGE_SIZE && (
        <div style={{ textAlign: 'center', marginTop: 16 }}>
          <Pagination current={pageNum} total={total} pageSize={PAGE_SIZE} showSizeChanger={false}
            onChange={(page) => { setPageNum(page); load(page); }} />
        </div>
      )}
    </div>
  );
}
