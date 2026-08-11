import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Spin, Empty, Button, Space, Tabs, Tag, Modal, message, Pagination } from 'antd';
import { getMyNotes, deleteNote, publishDraft } from '../../api/note';
import { formatDate } from '../../utils';
import type { NoteItemVO } from '../../types';

const PAGE_SIZE = 12;

const NOTE_STATUS_MAP: Record<number, { label: string; color: string }> = {
  0: { label: '草稿', color: '#999' },
  1: { label: '审核中', color: '#faad14' },
  2: { label: '已发布', color: '#52c41a' },
  3: { label: '已下架', color: '#999' },
};

export default function MyNotesPage() {
  const navigate = useNavigate();
  const [status, setStatus] = useState<string>('all');
  const [list, setList] = useState<NoteItemVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [pageNum, setPageNum] = useState(1);
  const [total, setTotal] = useState(0);

  const load = async (page: number, st: string) => {
    setLoading(true);
    try {
      const resp = await getMyNotes(page, PAGE_SIZE, st === 'all' ? undefined : Number(st));
      setList(resp.data.data.records || []);
      setTotal(resp.data.data.total || 0);
      setPageNum(page);
    } catch (e: any) {
      message.error(e.response?.data?.message || '加载笔记失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(1, status); }, [status]);

  const handlePublishDraft = (noteId: string | number) => {
    Modal.confirm({
      title: '确认发布这篇草稿？',
      onOk: async () => {
        try {
          await publishDraft(noteId);
          message.success('已发布');
          load(pageNum, status);
        } catch (e: any) { message.error(e.response?.data?.message || '发布失败'); }
      },
    });
  };

  const handleDelete = (noteId: string | number) => {
    Modal.confirm({
      title: '确认删除这篇笔记？',
      okType: 'danger',
      onOk: async () => {
        try {
          await deleteNote(noteId);
          message.success('已删除');
          load(pageNum, status);
        } catch (e: any) { message.error(e.response?.data?.message || '删除失败'); }
      },
    });
  };

  const tabs = [
    { key: 'all', label: '全部' },
    { key: '2', label: '已发布' },
    { key: '0', label: '草稿' },
  ];

  return (
    <div style={{ maxWidth: 900, margin: '0 auto' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0 }}>我的笔记</h2>
        <Button type="primary" onClick={() => navigate('/note/publish')}>发布笔记</Button>
      </div>
      <Tabs activeKey={status} onChange={setStatus} items={tabs} />

      {loading ? (
        <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" /></div>
      ) : list.length === 0 ? (
        <Empty description="暂无笔记" style={{ padding: 60 }} />
      ) : (
        list.map(note => {
          const meta = NOTE_STATUS_MAP[note.status] || { label: '未知', color: '#999' };
          return (
            <Card key={note.id} style={{ marginBottom: 12 }} size="small">
              <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
                <img src={note.firstImage || note.coverUrl || ''} alt=""
                  style={{ width: 72, height: 72, objectFit: 'cover', borderRadius: 8, background: '#f0f0f0' }}
                  onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }} />
                <div style={{ flex: 1 }}>
                  <div style={{ fontWeight: 500 }}>{note.title || '（无标题）'}</div>
                  <div style={{ color: '#999', fontSize: 12, marginTop: 4 }}>
                    <Tag color={meta.color}>{meta.label}</Tag> {formatDate(note.createdAt, true)}
                  </div>
                </div>
                <Space>
                  {note.status === 0 && (
                    <Button size="small" type="primary" onClick={() => handlePublishDraft(note.id)}>发布</Button>
                  )}
                  <Button size="small" onClick={() => navigate(`/note/publish?id=${note.id}`)}>编辑</Button>
                  <Button size="small" danger onClick={() => handleDelete(note.id)}>删除</Button>
                </Space>
              </div>
            </Card>
          );
        })
      )}

      {!loading && total > PAGE_SIZE && (
        <div style={{ textAlign: 'center', marginTop: 16 }}>
          <Pagination current={pageNum} total={total} pageSize={PAGE_SIZE} showSizeChanger={false}
            onChange={(page) => load(page, status)} />
        </div>
      )}
    </div>
  );
}
