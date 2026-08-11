import { useState, useEffect, useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {AutoComplete, Input, Tabs, Empty, Spin, Button, Tag, message, Space} from 'antd';
import { searchNotes, searchProducts, getSuggestions, getSearchHistory, clearSearchHistory, getHotSearch } from '../../api/search';
import { formatPrice, formatRelativeTime } from '../../utils';
import type { NoteSearchVO, ProductSearchVO, HotSearchVO } from '../../types';

const PAGE_SIZE = 20;

export default function SearchPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [keyword, setKeyword] = useState(searchParams.get('keyword') || '');
  const [tab, setTab] = useState<'note' | 'product'>('note');

  const [noteItems, setNoteItems] = useState<NoteSearchVO[]>([]);
  const [productItems, setProductItems] = useState<ProductSearchVO[]>([]);
  const [noteAfter, setNoteAfter] = useState<string | undefined>();
  const [productAfter, setProductAfter] = useState<string | undefined>();
  const [noteTotal, setNoteTotal] = useState(0);
  const [productTotal, setProductTotal] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);

  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [history, setHistory] = useState<string[]>([]);
  const [hot, setHot] = useState<HotSearchVO[]>([]);

  // 无关键词时展示历史/热搜
  useEffect(() => {
    if (keyword.trim()) return;
    getSearchHistory().then(r => setHistory(r.data.data || [])).catch(() => {});
    getHotSearch().then(r => setHot(r.data.data || [])).catch(() => {});
  }, [keyword]);

  const doSearch = useCallback(async (kw: string, reset: boolean, currentTab = tab) => {
    if (!kw.trim()) { setNoteItems([]); setProductItems([]); setHasMore(false); return; }
    setLoading(true);
    try {
      if (currentTab === 'note') {
        const resp = await searchNotes({
          keyword: kw, size: PAGE_SIZE,
          searchAfter: reset ? undefined : noteAfter,
        });
        const data = resp.data.data;
        setNoteItems(reset ? data.items : prev => [...prev, ...data.items]);
        setNoteAfter(data.searchAfter);
        setNoteTotal(data.total);
        setHasMore(data.hasMore);
      } else {
        const resp = await searchProducts({
          keyword: kw, size: PAGE_SIZE,
          searchAfter: reset ? undefined : productAfter,
        });
        const data = resp.data.data;
        setProductItems(reset ? data.items : prev => [...prev, ...data.items]);
        setProductAfter(data.searchAfter);
        setProductTotal(data.total);
        setHasMore(data.hasMore);
      }
    } catch (e: any) {
      message.error(e.response?.data?.message || '搜索失败');
    } finally {
      setLoading(false);
    }
  }, [tab, noteAfter, productAfter]);

  // 关键词变化 → 重置搜索
  useEffect(() => {
    if (keyword.trim()) {
      setNoteAfter(undefined);
      setProductAfter(undefined);
      doSearch(keyword, true);
    }
  }, [keyword, tab]);

  const onSelectKeyword = (kw: string) => {
    setKeyword(kw);
    navigate(`/search?keyword=${encodeURIComponent(kw)}`);
  };

  const onSuggest = async (value: string) => {
    if (!value.trim()) { setSuggestions([]); return; }
    try {
      const r = await getSuggestions(value);
      setSuggestions(r.data.data || []);
    } catch { setSuggestions([]); }
  };

  const renderHighlight = (html?: string) =>
    html ? <span dangerouslySetInnerHTML={{ __html: html }} /> : null;

  return (
    <div style={{ maxWidth: 1100, margin: '0 auto' }}>
      <AutoComplete
        style={{ width: '100%', marginBottom: 16 }}
        options={suggestions.map(s => ({ value: s }))}
        onSearch={onSuggest}
        onSelect={onSelectKeyword}
      >
        <Input.Search
          size="large"
          placeholder="搜索笔记 / 商品..."
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onSearch={(v) => onSelectKeyword(v)}
          enterButton
        />
      </AutoComplete>

      {keyword.trim() ? (
        <Tabs
          activeKey={tab}
          onChange={(k) => setTab(k as 'note' | 'product')}
          items={[
            { key: 'note', label: `笔记 (${noteTotal})` },
            { key: 'product', label: `商品 (${productTotal})` },
          ]}
        />
      ) : (
        <div style={{ marginBottom: 16 }}>
          {history.length > 0 && (
            <div style={{ marginBottom: 24 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                <b>搜索历史</b>
                <Button type="link" size="small" onClick={() => clearSearchHistory().then(() => setHistory([]))}>清空</Button>
              </div>
              <Space wrap>
                {history.map(h => <Tag key={h} style={{ cursor: 'pointer' }} onClick={() => onSelectKeyword(h)}>{h}</Tag>)}
              </Space>
            </div>
          )}
          {hot.length > 0 && (
            <div>
              <b>热搜榜</b>
              <div>
                {hot.map((item, idx) => (
                  <div key={idx} style={{ cursor: 'pointer', padding: '8px 0', borderBottom: '1px solid #f5f5f5' }} onClick={() => onSelectKeyword(item.keyword)}>
                    <Space>
                      <span style={{ color: idx < 3 ? '#ff4d4f' : '#999', fontWeight: idx < 3 ? 700 : 400 }}>{idx + 1}</span>
                      {item.pinned && <Tag color="gold">置顶</Tag>}
                      <span>{item.keyword}</span>
                    </Space>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {keyword.trim() && loading ? (
        <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" /></div>
      ) : keyword.trim() && ((tab === 'note' && noteItems.length === 0) || (tab === 'product' && productItems.length === 0)) ? (
        <Empty description="没有找到相关内容" style={{ padding: 60 }} />
      ) : tab === 'note' ? (
        <div>
          {noteItems.map(n => (
            <div key={n.noteId} style={{ display: 'flex', gap: 12, padding: '12px 0', borderBottom: '1px solid #f0f0f0', cursor: 'pointer' }} onClick={() => navigate(`/note/${n.noteId}`)}>
              <img src={n.coverImage} alt="" style={{ width: 80, height: 80, objectFit: 'cover', borderRadius: 8, background: '#f0f0f0' }}
                onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }} />
              <div>
                <div>{renderHighlight(n.highlightTitle) || n.title}</div>
                <div style={{ color: '#666' }}>{renderHighlight(n.highlightContent) || n.content}</div>
                <Space size={16} style={{ color: '#999', fontSize: 12 }}>
                  <span>♥ {n.likeCount}</span><span>☆ {n.collectCount}</span><span>💬 {n.commentCount}</span>
                  <span>{formatRelativeTime(n.createdAt)}</span>
                </Space>
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 16 }}>
          {productItems.map(p => (
            <div key={p.skuId} style={{ cursor: 'pointer', border: '1px solid #f0f0f0', borderRadius: 8, overflow: 'hidden' }}
              onClick={() => navigate(`/product/${p.spuId}`)}>
              <img src={p.image} alt="" style={{ width: '100%', height: 150, objectFit: 'cover', background: '#f0f0f0' }}
                onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }} />
              <div style={{ padding: 8 }}>
                <div style={{ fontSize: 13 }}>{p.name}</div>
                <div style={{ color: '#ff4d4f', fontWeight: 600 }}>{formatPrice(p.price)}</div>
              </div>
            </div>
          ))}
        </div>
      )}

      {keyword.trim() && hasMore && (
        <div style={{ textAlign: 'center', padding: 16 }}>
          <Button loading={loading} onClick={() => doSearch(keyword, false)}>加载更多</Button>
        </div>
      )}
    </div>
  );
}
