import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Row, Col, Button, Space, Tag, Divider, Image, Input, message, Modal, Spin, Alert } from 'antd';
import { HeartOutlined, HeartFilled, StarOutlined, StarFilled, ShareAltOutlined } from '@ant-design/icons';
import { getNoteDetail } from '../../api/home';
import { getSimilarNotes } from '../../api/recommend';
import { like, unlike, favorite, unfavorite, follow, unfollow } from '../../api/social';
import { shareNote, deleteNote as deleteNoteApi, postComment } from '../../api/note';
import { useAuthStore } from '../../store/authStore';
import { formatRelativeTime } from '../../utils';
import CommentList from '../../components/CommentList';
import NoteCard from '../../components/NoteCard';
import UserAvatar from '../../components/UserAvatar';
import type { NoteDetailAggVO, NoteCardVO } from '../../types';

export default function NoteDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { userId } = useAuthStore();

  const [note, setNote] = useState<NoteDetailAggVO | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [similarNotes, setSimilarNotes] = useState<NoteCardVO[]>([]);
  const [commentText, setCommentText] = useState('');

  useEffect(() => {
    if (!id) return;
    setLoading(true);
    getNoteDetail(id)
      .then(resp => { setNote(resp.data.data); setLoading(false); })
      .catch(e => { setError(e.response?.data?.message || '加载失败'); setLoading(false); });

    getSimilarNotes(id, 6)
      .then(async (resp) => {
        const list = resp.data.data || [];
        const cards = await Promise.all(list.map(async (item) => {
          try {
            const d = (await getNoteDetail(item.noteId)).data.data;
            return {
              noteId: d.noteId, title: d.title,
              coverUrl: d.coverUrl || d.images?.[0] || '', noteType: d.noteType,
              authorId: d.authorId, authorNickname: d.authorNickname, authorAvatar: d.authorAvatar,
              likeCount: d.likeCount, collectCount: d.collectCount, commentCount: d.commentCount,
              isLiked: d.isLiked, isCollected: d.isCollected, isFollowed: d.isFollowed,
              createdAt: d.createdAt, score: 0,
            } as NoteCardVO;
          } catch { return null; }
        }));
        setSimilarNotes(cards.filter((c): c is NoteCardVO => c !== null));
      })
      .catch(() => {});
  }, [id]);

  if (loading) return <div style={{ textAlign: 'center', padding: 80 }}><Spin size="large" /></div>;
  if (error) return <Alert type="error" title={error} style={{ margin: 24 }} />;
  if (!note) return <Alert type="error" title="笔记不存在" style={{ margin: 24 }} />;

  const isAuthor = userId === note.authorId;
  const noteId = note.noteId;

  // 乐观更新 helper
  const toggleLike = async () => {
    const old = note.isLiked;
    const oldCount = note.likeCount;
    setNote(prev => prev ? { ...prev, isLiked: !old, likeCount: oldCount + (old ? -1 : 1) } : prev);
    try {
      if (old) await unlike(noteId, 1);
      else await like(noteId, 1);
    } catch {
      setNote(prev => prev ? { ...prev, isLiked: old, likeCount: oldCount } : prev);
      message.error('操作失败');
    }
  };

  const toggleCollect = async () => {
    const old = note.isCollected;
    const oldCount = note.collectCount;
    setNote(prev => prev ? { ...prev, isCollected: !old, collectCount: oldCount + (old ? -1 : 1) } : prev);
    try {
      if (old) await unfavorite(noteId);
      else await favorite(noteId);
    } catch {
      setNote(prev => prev ? { ...prev, isCollected: old, collectCount: oldCount } : prev);
      message.error('操作失败');
    }
  };

  const toggleFollow = async () => {
    const old = note.isFollowed;
    setNote(prev => prev ? { ...prev, isFollowed: !old } : prev);
    try {
      if (old) await unfollow(note.authorId);
      else await follow(note.authorId);
    } catch {
      setNote(prev => prev ? { ...prev, isFollowed: old } : prev);
      message.error('操作失败');
    }
  };

  const handleShare = async () => {
    try {
      await shareNote(noteId);
      message.success('分享链接已复制');
    } catch { message.error('分享失败'); }
  };

  const handleDelete = () => {
    Modal.confirm({
      title: '确认删除这条笔记？',
      okText: '删除',
      okType: 'danger',
      onOk: async () => {
        try {
          await deleteNoteApi(noteId);
          message.success('已删除');
          navigate('/feed');
        } catch { message.error('删除失败'); }
      },
    });
  };

  const submitComment = async () => {
    if (!commentText.trim()) return;
    try {
      await postComment({ noteId, content: commentText });
      setCommentText('');
      message.success('评论成功');
      // 重新加载 NoteDetail 获取最新计数
      getNoteDetail(id!).then(resp => setNote(resp.data.data)).catch(() => {});
    } catch { message.error('评论失败'); }
  };

  const allImages = note.images?.length ? note.images : (note.coverUrl ? [note.coverUrl] : []);

  return (
    <div>
      <Row gutter={24}>
        {/* 左侧 — 图片/视频 */}
        <Col span={14}>
          <div style={{ position: 'sticky', top: 72 }}>
            {note.videoUrl ? (
              <video src={note.videoUrl} controls style={{ width: '100%', borderRadius: 8 }} />
            ) : allImages.length > 0 ? (
              <Image.PreviewGroup>
                {allImages.map((src, i) => (
                  <Image key={i} src={src} style={{ borderRadius: 8 }} fallback="" />
                ))}
              </Image.PreviewGroup>
            ) : (
              <div style={{ background: '#f0f0f0', height: 400, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#999', borderRadius: 8 }}>暂无图片</div>
            )}
          </div>
        </Col>

        {/* 右侧 — 信息区 */}
        <Col span={10} style={{ maxHeight: 'calc(100vh - 72px)', overflowY: 'auto' }}>
          {/* 作者 + 按钮 */}
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
            <UserAvatar
              name={note.authorNickname}
              src={note.authorAvatar}
              size={40}
              onClick={() => navigate(`/user/${note.authorId}`)}
            />
            <Space>
              {isAuthor ? (
                <>
                  <Button size="small" onClick={() => navigate(`/note/publish?id=${noteId}`)}>编辑</Button>
                  <Button size="small" danger onClick={handleDelete}>删除</Button>
                </>
              ) : note.isFollowed ? (
                <Button size="small" onClick={toggleFollow}>已关注</Button>
              ) : (
                <Button size="small" type="primary" ghost onClick={toggleFollow}>关注</Button>
              )}
            </Space>
          </div>

          <h2 style={{ fontSize: 22, marginBottom: 12 }}>{note.title}</h2>
          <div style={{ lineHeight: '24px', marginBottom: 16, whiteSpace: 'pre-wrap' }}>{note.content}</div>

          {note.tags?.length > 0 && (
            <Space style={{ marginBottom: 12 }}>
              {note.tags.map(tag => <Tag key={tag}>#{tag}</Tag>)}
            </Space>
          )}

          <div style={{ color: '#999', fontSize: 13, marginBottom: 16 }}>
            {formatRelativeTime(note.createdAt)}
          </div>

          <Space size="large" style={{ marginBottom: 16 }}>
            <Button
              icon={note.isLiked ? <HeartFilled style={{ color: '#ff4d4f' }} /> : <HeartOutlined />}
              onClick={toggleLike}
              style={{ color: note.isLiked ? '#ff4d4f' : undefined }}
            >
              {note.isLiked ? '已赞' : '点赞'} {note.likeCount}
            </Button>
            <Button
              icon={note.isCollected ? <StarFilled style={{ color: '#faad14' }} /> : <StarOutlined />}
              onClick={toggleCollect}
            >
              {note.isCollected ? '已收藏' : '收藏'} {note.collectCount}
            </Button>
            <Button icon={<ShareAltOutlined />} onClick={handleShare}>分享</Button>
          </Space>

          <Divider>热门评论</Divider>
          {note.hotComments?.map((c: any, i: number) => (
            <div key={i} style={{ marginBottom: 8 }}>
              <span style={{ fontWeight: 500, color: '#666', fontSize: 13 }}>用户{c.userId}: </span>
              <span style={{ fontSize: 13 }}>{c.content}</span>
            </div>
          ))}

          <Divider>全部评论 ({note.commentCount})</Divider>
          <CommentList noteId={noteId} currentUserId={userId ?? undefined} />

          <div style={{ borderTop: '1px solid #f0f0f0', padding: '12px 0', marginBottom: 24 }}>
            <Input.TextArea
              rows={2}
              value={commentText}
              onChange={e => setCommentText(e.target.value)}
              placeholder="发表评论..."
            />
            <Button type="primary" style={{ marginTop: 8 }} onClick={submitComment}>发送</Button>
          </div>

          {similarNotes.length > 0 && (
            <>
              <Divider>相似笔记</Divider>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 12 }}>
                {similarNotes.map(n => <NoteCard key={n.noteId} {...n} />)}
              </div>
            </>
          )}
        </Col>
      </Row>
    </div>
  );
}
