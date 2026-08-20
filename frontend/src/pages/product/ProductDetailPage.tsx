import { useState, useEffect, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Row, Col, Spin, Button, Space, Tag, Divider, InputNumber, Image, Radio, Alert, message } from 'antd';
import { ShoppingCartOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { getProductDetailAgg } from '../../api/product';
import { addToCart } from '../../api/cart';
import { useCartStore } from '../../store/cartStore';
import { formatPrice } from '../../utils';
import NoteCard from '../../components/NoteCard';
import type { ProductDetailAggVO, SkuWithStockVO } from '../../types';

export default function ProductDetailPage() {
  const { spuId } = useParams<{ spuId: string }>();
  const navigate = useNavigate();
  const fetchCount = useCartStore((s) => s.fetchCount);

  const [product, setProduct] = useState<ProductDetailAggVO | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<Record<string, string>>({});
  const [quantity, setQuantity] = useState(1);

  useEffect(() => {
    if (!spuId) return;
    setLoading(true);
    getProductDetailAgg(spuId)
      .then((resp) => {
        setProduct(resp.data.data);
        setLoading(false);
      })
      .catch((e) => {
        setError(e.response?.data?.message || '加载失败');
        setLoading(false);
      });
  }, [spuId]);

  // 动态规格组：从所有 SKU 的 specValues 聚合 key -> 可选值
  const specGroups = useMemo(() => {
    const groups: Record<string, string[]> = {};
    (product?.skuList || []).forEach((sku) => {
      Object.entries(sku.specValues || {}).forEach(([k, v]) => {
        if (!groups[k]) groups[k] = [];
        if (!groups[k].includes(v)) groups[k].push(v);
      });
    });
    return groups;
  }, [product]);

  const selectedSku = useMemo<SkuWithStockVO | undefined>(() => {
    const keys = Object.keys(selected);
    if (keys.length === 0) return undefined;
    return (product?.skuList || []).find((sku) =>
      keys.every((k) => sku.specValues?.[k] === selected[k])
    );
  }, [product, selected]);

  const minPrice = useMemo(() => {
    const prices = (product?.skuList || []).map(s => Number(s.price)).filter(Boolean);
    return prices.length ? Math.min(...prices) : 0;
  }, [product]);

  if (loading) return <div style={{ textAlign: 'center', padding: 80 }}><Spin size="large" /></div>;
  if (error) return <Alert type="error" message={error} style={{ margin: 24 }} />;
  if (!product) return <Alert type="error" message="商品不存在" style={{ margin: 24 }} />;

  const currentPrice = selectedSku ? Number(selectedSku.price) : minPrice;
  const currentSkuId = selectedSku?.skuId;

  const handleAddToCart = async () => {
    if (!currentSkuId) { message.warning('请先选择规格'); return; }
    try {
      await addToCart({ skuId: currentSkuId, quantity });
      fetchCount();
      message.success('已加入购物车');
    } catch (e: any) {
      message.error(e.response?.data?.message || '加入失败');
    }
  };

  const handleBuyNow = () => {
    if (!currentSkuId || !selectedSku) { message.warning('请先选择规格'); return; }
    navigate('/order/create', {
      state: {
        skuItems: [{ skuId: currentSkuId, quantity }],
        skuInfo: [{
          skuId: currentSkuId,
          skuName: selectedSku.skuName,
          skuImage: selectedSku.image,
          price: Number(selectedSku.price),
          quantity,
        }],
      },
    });
  };

  const selectValue = (key: string, value: string) => {
    setSelected((prev) => ({ ...prev, [key]: value }));
  };

  return (
    <div>
      <Row gutter={32}>
        <Col span={10}>
          <Image.PreviewGroup>
            {(product.images?.length ? product.images : ['']).map((src, i) => (
              <Image key={i} src={src} width="100%" style={{ borderRadius: 8 }} fallback="" />
            ))}
          </Image.PreviewGroup>
        </Col>
        <Col span={14}>
          <h2 style={{ fontSize: 24, marginBottom: 8 }}>{product.name}</h2>
          {product.categoryName && (
            <Tag color="blue" style={{ marginBottom: 12 }}>{product.categoryName}</Tag>
          )}
          <div style={{
            background: '#fff1f0', borderRadius: 8, padding: '16px 20px', marginBottom: 20,
          }}>
            <div style={{ color: '#999', fontSize: 13, marginBottom: 4 }}>价格</div>
            <span style={{ color: '#ff4d4f', fontSize: 28, fontWeight: 700 }}>
              {formatPrice(currentPrice)}
            </span>
            {product.viewCount > 0 && (
              <span style={{ marginLeft: 16, color: '#999', fontSize: 13 }}>
                已售 {product.viewCount} · 收藏 {product.collectCount}
              </span>
            )}
          </div>

          {Object.keys(specGroups).map((key) => (
            <div key={key} style={{ marginBottom: 20 }}>
              <div style={{ fontWeight: 500, marginBottom: 8 }}>{key}</div>
              <Radio.Group value={selected[key]} onChange={(e) => selectValue(key, e.target.value)}>
                <Space wrap>
                  {specGroups[key].map((v) => (
                    <Radio.Button key={v} value={v}>{v}</Radio.Button>
                  ))}
                </Space>
              </Radio.Group>
            </div>
          ))}

          <div style={{ marginBottom: 20 }}>
            <span style={{ fontWeight: 500, marginRight: 12 }}>数量</span>
            <InputNumber min={1} max={selectedSku?.availableStock || 99} value={quantity} onChange={(v) => setQuantity(v || 1)} />
            {selectedSku && (
              <span style={{ marginLeft: 12, color: '#999', fontSize: 13 }}>
                库存 {selectedSku.availableStock}
              </span>
            )}
          </div>

          <Space size={16}>
            <Button type="primary" icon={<ShoppingCartOutlined />} size="large" onClick={handleAddToCart}>
              加入购物车
            </Button>
            <Button type="primary" danger icon={<ThunderboltOutlined />} size="large" onClick={handleBuyNow}>
              立即购买
            </Button>
          </Space>
        </Col>
      </Row>

      {product.description && (
        <>
          <Divider>商品详情</Divider>
          <div style={{ lineHeight: '24px', whiteSpace: 'pre-wrap', color: '#333' }}>{product.description}</div>
        </>
      )}

      {product.relatedNotes?.length > 0 && (
        <>
          <Divider>相关笔记</Divider>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 16 }}>
            {product.relatedNotes.map((n) => <NoteCard key={n.noteId} {...n} />)}
          </div>
        </>
      )}
    </div>
  );
}
