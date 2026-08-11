import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Avatar, Spin, Alert, Button, Space, Typography, message, Empty } from 'antd';
import { UserOutlined } from '@ant-design/icons';
import { getUserProfile } from '../../api/home';
import { follow, unfollow } from '../../api/social';
import { useAuthStore } from '../../store/authStore';
import NoteCard from '../../components/NoteCard';
import type { UserProfileAggVO } from '../../types';

export default function UserProfilePage() {
  const { userId } = useParams<{ userId: string }>();
  const navigate = useNavigate();
  const { userId: myId } = useAuthStore();

  const [profile, setProfile] = useState<UserProfileAggVO | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [following, setFollowing] = useState(false);

  const load = () => {
    if (!userId) return;
    setLoading(true);
    getUserProfile(userId)
      .then((resp) => { setProfile(resp.data.data); setFollowing(resp.data.data.isFollowing); setLoading(false); })
      .catch((e) => { setError(e.response?.data?.message || '加载失败'); setLoading(false); });
  };

  useEffect(() => { load(); }, [userId]);

  if (loading) return <div style={{ textAlign: 'center', padding: 80 }}><Spin size="large" /></div>;
  if (error) return <Alert type="error" title={error} style={{ margin: 24 }} />;
  if (!profile) return <Alert type="error" title="用户不存在" style={{ margin: 24 }} />;

  const isSelf = myId === profile.userId;
  const targetId = profile.userId;

  const toggleFollow = async () => {
    const old = following;
    setFollowing(!old);
    try {
      if (old) await unfollow(targetId);
      else await follow(targetId);
    } catch (e: any) {
      setFollowing(old);
      message.error(e.response?.data?.message || '操作失败');
    }
  };

  const followBtnText = following ? (profile.isFollowBack ? '互相关注' : '已关注') : '关注';

  return (
    <div style={{ maxWidth: 1100, margin: '0 auto' }}>
      <Card style={{ marginBottom: 16 }}>
        <Space size={24} align="center">
          <Avatar size={72} src={profile.avatar} icon={<UserOutlined />}>{profile.nickname?.[0]}</Avatar>
          <div>
            <Typography.Title level={4} style={{ margin: 0 }}>{profile.nickname}</Typography.Title>
            <Typography.Text type="secondary">{profile.bio || '这个人很懒，什么都没写'}</Typography.Text>
            <div style={{ marginTop: 8 }}>
              <Space size={24}>
                <span><b>{profile.followingCount}</b> 关注</span>
                <a onClick={() => navigate(`/user/${targetId}/following`)}><b>{profile.followerCount}</b> 粉丝</a>
                <span><b>{profile.noteCount}</b> 笔记</span>
                <span><b>{profile.likeAndCollectCount}</b> 获赞与收藏</span>
              </Space>
            </div>
          </div>
        </Space>
        <Space style={{ float: 'right', marginTop: 8 }}>
          {isSelf ? (
            <Button onClick={() => navigate('/me')}>编辑资料</Button>
          ) : (
            <Button type="primary" onClick={toggleFollow}>
              {followBtnText}
            </Button>
          )}
        </Space>
      </Card>

      <Typography.Title level={5} style={{ marginBottom: 16 }}>笔记 ({profile.noteCount})</Typography.Title>
      {profile.notes?.length === 0 ? (
        <Empty description="暂无笔记" style={{ padding: 40 }} />
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 16 }}>
          {profile.notes.map(n => <NoteCard key={n.noteId} {...n} />)}
        </div>
      )}
    </div>
  );
}
