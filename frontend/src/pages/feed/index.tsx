import { useState, useEffect, useRef } from 'react';
import { Spin, Alert, Empty, Tabs, message } from 'antd';
import { getFeed } from '../../api/home';
import { getRecommendFeed } from '../../api/recommend';
import { batchLikeStatus } from '../../api/social';
import { reportBehavior } from '../../api/recommend';
import type { NoteCardVO } from '../../types';
import NoteCard from '../../components/NoteCard';

export default function FeedPage() {
  const [tab, setTab] = useState<string>('all');
  const [notes, setNotes] = useState<NoteCardVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastScore, setLastScore] = useState<number | undefined>();
  const [hasMore, setHasMore] = useState(true);
  const loadMoreRef = useRef<HTMLDivElement>(null);

  const fetchBatchLikeStatus = async (noteList: NoteCardVO[]) => {
    if (noteList.length === 0) return;
    try {
      const ids = noteList.map(n => n.noteId);
      const resp = await batchLikeStatus(1, ids);
      const statusMap = resp.data.data;
      setNotes(prev => prev.map(n => ({
        ...n,
        isLiked: statusMap[n.noteId] ?? n.isLiked,
      })));
    } catch { /* 非关键 */ }
  };

  const reportImpressions = (noteList: NoteCardVO[]) => {
    noteList.forEach(n => {
      reportBehavior({ targetId: n.noteId, targetType: 1, action: 'impression' }).catch(() => {});
    });
  };

  const loadNotes = async (reset = false) => {
    try {
      setLoadingMore(true);
      setError(null);
      let resp;
      if (tab === 'recommend') {
        resp = await getRecommendFeed(20);
      } else {
        resp = await getFeed(reset ? undefined : lastScore, 20);
      }
      const data = resp.data.data;
      const newNotes = data.notes || [];
      if (reset) {
        setNotes(newNotes);
      } else {
        setNotes(prev => [...prev, ...newNotes]);
      }
      setLastScore(newNotes.length > 0 ? newNotes[newNotes.length - 1].score : lastScore);
      setHasMore(data.hasMore);
      fetchBatchLikeStatus(newNotes);
      reportImpressions(newNotes);
    } catch (e: any) {
      if (reset) setError(e.response?.data?.message || '加载失败');
      else message.error('加载更多失败');
    } finally {
      setLoadingMore(false);
      setLoading(false);
    }
  };

  useEffect(() => {
    setLoading(true);
    setNotes([]);
    setLastScore(undefined);
    setHasMore(true);
    setError(null);
    loadNotes(true);
  }, [tab]);

  useEffect(() => {
    const obs = new IntersectionObserver(([entry]) => {
      if (entry.isIntersecting && hasMore && !loadingMore) loadNotes();
    }, { rootMargin: '200px' });
    const current = loadMoreRef.current;
    if (current) obs.observe(current);
    return () => obs.disconnect();
  }, [hasMore, loadingMore, lastScore, tab]);

  const handleNoteClick = (note: NoteCardVO) => {
    reportBehavior({ targetId: note.noteId, targetType: 1, action: 'click' }).catch(() => {});
  };

  const tabItems = [
    { key: 'all', label: '全部' },
    { key: 'follow', label: '关注' },
    { key: 'recommend', label: '推荐' },
  ];

  return (
    <div>
      <Tabs activeKey={tab} onChange={setTab} items={tabItems} style={{ marginBottom: 16 }} />
      {loading ? (
        <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" /></div>
      ) : error ? (
        <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />
      ) : notes.length === 0 ? (
        <Empty description={
          tab === 'follow' ? '还没有关注任何人，去看看推荐吧' : '暂无笔记'
        } />
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 16 }}>
          {notes.map(note => (
            <NoteCard
              key={note.noteId}
              {...note}
              onClick={() => handleNoteClick(note)}
            />
          ))}
        </div>
      )}
      {hasMore && !loading && (
        <div ref={loadMoreRef} style={{ textAlign: 'center', padding: 16 }}>
          {loadingMore ? <Spin /> : null}
        </div>
      )}
    </div>
  );
}
