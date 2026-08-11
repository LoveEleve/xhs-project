import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Spin, Empty, Button, message, Pagination } from 'antd';
import { getFavoriteList } from '../../api/social';
import { getNoteDetail } from '../../api/home';
import NoteCard from '../../components/NoteCard';
import type { NoteCardVO } from '../../types';

const PAGE_SIZE = 12;

export default function FavoritesPage() {
  const navigate = useNavigate();
  const [notes, setNotes] = useState<NoteCardVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [pageNum, setPageNum] = useState(1);
  const [total, setTotal] = useState(0);

  const load = async (page: number) => {
    setLoading(true);
    try {
      const resp = await getFavoriteList(page, PAGE_SIZE);
      const ids = resp.data.data.list || [];
      setTotal(resp.data.data.total || 0);
      const cards = await Promise.all(ids.map(async (id) => {
        try {
          const d = (await getNoteDetail(id)).data.data;
          return {
            noteId: d.noteId, title: d.title, coverUrl: d.coverUrl || d.images?.[0] || '',
            noteType: d.noteType, authorId: d.authorId, authorNickname: d.authorNickname, authorAvatar: d.authorAvatar,
            likeCount: d.likeCount, collectCount: d.collectCount, commentCount: d.commentCount,
            isLiked: d.isLiked, isCollected: d.isCollected, isFollowed: d.isFollowed,
            createdAt: d.createdAt, score: 0,
          } as NoteCardVO;
        } catch { return null; }
      }));
      setNotes(cards.filter((c): c is NoteCardVO => c !== null));
    } catch (e: any) {
      message.error(e.response?.data?.message || '加载收藏失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(1); }, []);

  return (
    <div style={{ maxWidth: 1100, margin: '0 auto' }}>
      <h2 style={{ marginBottom: 16 }}>我的收藏 ({total})</h2>
      {loading ? (
        <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" /></div>
      ) : notes.length === 0 ? (
        <Empty description="还没有收藏任何笔记" style={{ padding: 60 }}>
          <Button type="primary" onClick={() => navigate('/feed')}>去逛逛</Button>
        </Empty>
      ) : (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 16 }}>
            {notes.map(n => <NoteCard key={n.noteId} {...n} />)}
          </div>
          {total > PAGE_SIZE && (
            <div style={{ textAlign: 'center', marginTop: 16 }}>
              <Pagination current={pageNum} total={total} pageSize={PAGE_SIZE} showSizeChanger={false}
                onChange={(page) => { setPageNum(page); load(page); }} />
            </div>
          )}
        </>
      )}
    </div>
  );
}
