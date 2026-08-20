import { useState, useEffect } from 'react';
import { Button, Input, Space, message, Modal, Spin } from 'antd';
import { getComments, getChildComments, postComment, deleteComment } from '../api/note';
import { formatRelativeTime } from '../utils';
import UserAvatar from './UserAvatar';
import type { CommentVO } from '../types';

interface CommentListProps {
  noteId: string | number;
  /** 当前用户 ID，用于判断是否是自己的评论 */
  currentUserId?: string | number;
}

export default function CommentList({ noteId, currentUserId }: CommentListProps) {
  const [comments, setComments] = useState<CommentVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(true);
  const [replyTo, setReplyTo] = useState<{ id: string; name: string; parentId?: string } | null>(null);
  const [replyText, setReplyText] = useState('');

  const fetchComments = async (pageNum = 1) => {
    setLoading(true);
    try {
      const resp = await getComments(noteId, pageNum, 20);
      const data = resp.data.data;
      if (pageNum === 1) {
        setComments(data.records);
      } else {
        setComments(prev => [...prev, ...data.records]);
      }
      setHasMore(pageNum * 20 < data.total);
      setPage(pageNum);
    } catch { message.error('加载评论失败'); }
    finally { setLoading(false); }
  };

  useEffect(() => { fetchComments(1); }, [noteId]);

  const handleReply = async () => {
    if (!replyText.trim() || !replyTo) return;
    try {
      const resp = await postComment({
        noteId,
        content: replyText,
        parentId: replyTo.parentId ?? replyTo.id,
        replyToId: replyTo.parentId ? replyTo.id : undefined,
      });
      if (resp.data.code === 200) {
        message.success('评论成功');
        // 后端只返回 {commentId}，直接重拉第一页刷新列表
        fetchComments(1);
      } else {
        message.error(resp.data.message || '评论失败');
      }
    } catch { message.error('评论失败'); }
    setReplyText('');
    setReplyTo(null);
  };

  const handleDelete = (commentId: string | number) => {
    Modal.confirm({
      title: '确认删除这条评论？',
      onOk: async () => {
        try {
          await deleteComment(commentId);
          // 递归过滤：顶层和子评论（children）都要移除
          const removeComment = (list: CommentVO[]): CommentVO[] =>
            list
              .filter(c => c.id !== commentId)
              .map(c => c.children?.length
                ? { ...c, children: removeComment(c.children) }
                : c);
          setComments(prev => removeComment(prev));
          message.success('已删除');
        } catch { message.error('删除失败'); }
      },
    });
  };

  const toggleChildren = async (comment: CommentVO) => {
    if (comment.children?.length > 0) {
      // 已加载，折叠
      setComments(prev => prev.map(c => c.id === comment.id ? { ...c, children: [] } : c));
    } else {
      // 未加载，请求子评论
      try {
        const resp = await getChildComments(comment.id);
        setComments(prev => prev.map(c => c.id === comment.id ? { ...c, children: resp.data.data } : c));
      } catch { message.error('加载回复失败'); }
    }
  };

  const renderComment = (comment: CommentVO, isChild = false, parentCommentId?: string) => {
    const replyToData = isChild
      ? { id: comment.id, name: `用户${comment.userId}`, parentId: parentCommentId! }
      : { id: comment.id, name: `用户${comment.userId}` };
    return (
    <div key={comment.id} style={{ marginBottom: 12, marginLeft: isChild ? 32 : 0 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
        <UserAvatar name={`用户${comment.userId}`} size={24} />
        <span style={{ fontSize: 12, color: '#999' }}>{formatRelativeTime(comment.createdAt)}</span>
      </div>
      <div style={{ marginBottom: 4, lineHeight: '22px' }}>{comment.content}</div>
      <Space size={16} style={{ fontSize: 12 }}>
        <span style={{ color: '#999' }}>♡ {comment.likeCount || 0}</span>
        <a onClick={() => setReplyTo(replyToData)}>回复</a>
        {currentUserId === comment.userId && (
          <a style={{ color: '#999' }} onClick={() => handleDelete(comment.id)}>删除</a>
        )}
        {!isChild && comment.childCount > 0 && (
          <a onClick={() => toggleChildren(comment)}>
            {comment.children?.length > 0 ? '收起回复' : `展开${comment.childCount}条回复`}
          </a>
        )}
      </Space>

      {/* 回复输入框 */}
      {replyTo?.id === comment.id && (
        <div style={{ display: 'flex', gap: 8, marginTop: 8, marginLeft: isChild ? 0 : 0 }}>
          <Input.TextArea
            size="small"
            rows={2}
            value={replyText}
            onChange={e => setReplyText(e.target.value)}
            placeholder={`回复 ${replyTo.name}`}
            onPressEnter={handleReply}
          />
          <Button type="primary" size="small" onClick={handleReply} style={{ marginTop: 28 }}>发送</Button>
        </div>
      )}

      {/* 子评论 */}
      {comment.children?.map(child => renderComment(child, true, comment.id))}
    </div>
  );
  };

  return (
    <div>
      {loading ? (
        <div style={{ textAlign: 'center', padding: 24 }}><Spin /></div>
      ) : comments.length === 0 ? (
        <div style={{ textAlign: 'center', color: '#999', padding: 24 }}>暂无评论</div>
      ) : (
        <>
          {comments.map(comment => renderComment(comment))}
          {hasMore && (
            <div style={{ textAlign: 'center', padding: 8 }}>
              <Button type="link" loading={loading} onClick={() => fetchComments(page + 1)}>
                加载更多评论
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
