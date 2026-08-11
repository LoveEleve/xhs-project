import { useState, useEffect, useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Card, Row, Col, Spin, Empty, Pagination, Space, Typography, message } from 'antd';
import { getSpuList, getCategoryTree } from '../../api/product';
import type { SpuItemVO, CategoryTreeVO } from '../../types';

const PAGE_SIZE = 12;

export default function ProductListPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [categories, setCategories] = useState<CategoryTreeVO[]>([]);
  const [categoryId, setCategoryId] = useState<string | undefined>(
    searchParams.get('categoryId') || undefined
  );
  const [list, setList] = useState<SpuItemVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [pageNum, setPageNum] = useState(1);
  const [total, setTotal] = useState(0);

  const fetchCategories = useCallback(async () => {
    try {
      const resp = await getCategoryTree();
      setCategories(resp.data.data || []);
    } catch { /* 非关键 */ }
  }, []);

  const fetchList = useCallback(async (page: number, catId?: string | number) => {
    setLoading(true);
    try {
      const resp = await getSpuList({ pageNum: page, pageSize: PAGE_SIZE, categoryId: catId });
      const data = resp.data.data;
      setList(data.records || []);
      setTotal(data.total || 0);
      setPageNum(page);
    } catch (e: any) {
      message.error(e.response?.data?.message || '加载商品失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchCategories();
  }, [fetchCategories]);

  useEffect(() => {
    fetchList(1, categoryId);
  }, [categoryId, fetchList]);

  const flatCategories = categories; // 顶层分类

  return (
    <div>
      <Space wrap style={{ marginBottom: 16 }}>
        <Typography.Text type="secondary">分类：</Typography.Text>
        <a
          key="all"
          onClick={() => setCategoryId(undefined)}
          style={{
            fontWeight: categoryId === undefined ? 600 : 400,
            color: categoryId === undefined ? '#ff4d4f' : undefined,
          }}
        >
          全部
        </a>
        {flatCategories.map(c => (
          <a
            key={c.id}
            onClick={() => setCategoryId(c.id)}
            style={{
              fontWeight: categoryId === c.id ? 600 : 400,
              color: categoryId === c.id ? '#ff4d4f' : undefined,
            }}
          >
            {c.name}
          </a>
        ))}
      </Space>

      {loading ? (
        <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" /></div>
      ) : list.length === 0 ? (
        <Empty description="暂无商品" style={{ padding: 60 }} />
      ) : (
        <>
          <Row gutter={[16, 16]}>
            {list.map(spu => (
              <Col key={spu.id} xs={12} sm={8} md={6}>
                <Card
                  hoverable
                  onClick={() => navigate(`/product/${spu.id}`)}
                  style={{ overflow: 'hidden', borderRadius: 8 }}
                  cover={
                    <div style={{ height: 160, overflow: 'hidden', background: '#f0f0f0' }}>
                      <img
                        alt={spu.name}
                        src={spu.images?.[0]}
                        style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                        onError={(e) => {
                          const img = e.target as HTMLImageElement;
                          img.style.display = 'none';
                          if (img.parentElement) img.parentElement.textContent = '暂无图片';
                        }}
                      />
                    </div>
                  }
                >
                  <Typography.Paragraph ellipsis={{ rows: 2 }} style={{ marginBottom: 8, fontSize: 13, minHeight: 40 }}>
                    {spu.name}
                  </Typography.Paragraph>
                  <Typography.Text strong style={{ color: '#ff4d4f', fontSize: 16 }}>
                    ¥
                  </Typography.Text>
                </Card>
              </Col>
            ))}
          </Row>
          <div style={{ textAlign: 'center', marginTop: 24 }}>
            <Pagination
              current={pageNum}
              total={total}
              pageSize={PAGE_SIZE}
              showSizeChanger={false}
              onChange={(page) => fetchList(page, categoryId)}
            />
          </div>
        </>
      )}
    </div>
  );
}
