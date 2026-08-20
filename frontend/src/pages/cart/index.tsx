import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Button, Checkbox, Empty, Spin, Space, InputNumber, Divider, message, Modal, Alert } from 'antd';
import { getCartAgg, checkItem, checkAll, updateQuantity, removeFromCart, clearCart } from '../../api/cart';
import { useCartStore } from '../../store/cartStore';
import { formatPrice } from '../../utils';
import type { CartAggVO } from '../../types';

export default function CartPage() {
  const navigate = useNavigate();
  const fetchCount = useCartStore((s) => s.fetchCount);

  const [cart, setCart] = useState<CartAggVO | null>(null);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    try {
      const resp = await getCartAgg();
      setCart(resp.data.data);
    } catch (e: any) {
      message.error(e.response?.data?.message || '加载购物车失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const toggleItem = async (skuId: string | number, checked: boolean) => {
    try {
      await checkItem({ skuId, checked });
      load();
    } catch { message.error('操作失败'); }
  };

  const toggleAll = async (checked: boolean) => {
    try {
      await checkAll(checked);
      load();
    } catch { message.error('操作失败'); }
  };

  const changeQty = async (skuId: string | number, quantity: number) => {
    if (quantity < 1) return;
    try {
      await updateQuantity({ skuId, quantity });
      load();
    } catch (e: any) { message.error(e.response?.data?.message || '修改数量失败'); }
  };

  const handleRemove = (skuId: string | number) => {
    Modal.confirm({
      title: '确认从购物车删除？',
      onOk: async () => {
        try {
          await removeFromCart(skuId);
          fetchCount();
          load();
        } catch { message.error('删除失败'); }
      },
    });
  };

  const handleClear = () => {
    Modal.confirm({
      title: '确认清空购物车？',
      onOk: async () => {
        try {
          await clearCart();
          fetchCount();
          load();
        } catch { message.error('清空失败'); }
      },
    });
  };

  const handleCheckout = () => {
    if (!cart || cart.checkedCount === 0) {
      message.warning('请先勾选商品');
      return;
    }
    navigate('/order/create', { state: { source: 'cart' } });
  };

  if (loading) return <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" /></div>;
  if (!cart || cart.items.length === 0) {
    return (
      <Empty description="购物车是空的" style={{ padding: 80 }}>
        <Button type="primary" onClick={() => navigate('/product')}>去逛逛</Button>
      </Empty>
    );
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>购物车 ({cart.totalCount})</h2>
        <Button type="link" danger onClick={handleClear}>清空</Button>
      </div>

      {cart.availableCouponCount > 0 && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          title={`${cart.availableCouponCount} 张优惠券可用，去结算时可使用`}
        />
      )}

      <Card>
        <Checkbox
          checked={cart.allChecked}
          onChange={(e) => toggleAll(e.target.checked)}
          style={{ marginBottom: 8 }}
        >
          全选
        </Checkbox>
        <Divider style={{ margin: '8px 0' }} />

        {cart.items.map((item) => (
          <div
            key={item.skuId}
            style={{
              display: 'flex', alignItems: 'center', gap: 16, padding: '12px 0',
              borderBottom: '1px solid #f5f5f5',
            }}
          >
            <Checkbox
              checked={item.checked}
              onChange={(e) => toggleItem(item.skuId, e.target.checked)}
            />
            <img
              src={item.skuImage}
              alt=""
              style={{ width: 72, height: 72, objectFit: 'cover', borderRadius: 8, background: '#f0f0f0' }}
              onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }}
            />
            <div style={{ flex: 1 }}>
              <div style={{ fontWeight: 500 }}>{item.skuName}</div>
              {!item.hasStock && <div style={{ color: '#ff4d4f', fontSize: 12 }}>库存不足</div>}
            </div>
            <Space size={16}>
              <InputNumber
                min={1}
                max={item.availableStock}
                value={item.quantity}
                size="small"
                onChange={(v) => changeQty(item.skuId, v || 1)}
              />
              <span style={{ color: '#ff4d4f', fontWeight: 600 }}>{formatPrice(item.totalAmount)}</span>
              <Button type="link" danger size="small" onClick={() => handleRemove(item.skuId)}>删除</Button>
            </Space>
          </div>
        ))}

        <Divider />
        <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 24 }}>
          <span>
            已选 <b>{cart.checkedCount}</b> 件
          </span>
          <span>
            合计：
            <span style={{ color: '#ff4d4f', fontSize: 22, fontWeight: 700 }}>{formatPrice(cart.checkedAmount)}</span>
          </span>
          <Button type="primary" size="large" disabled={cart.checkedCount === 0} onClick={handleCheckout}>
            去结算 ({cart.checkedCount})
          </Button>
        </div>
      </Card>
    </div>
  );
}
