import { useState, useEffect, useRef } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { Card, Button, Radio, Spin, Empty, Space, message } from 'antd';
import { getAddressList } from '../../api/auth';
import { getAvailableCoupons } from '../../api/coupon';
import { getCartAgg } from '../../api/cart';
import { createOrder } from '../../api/order';
import { formatPrice } from '../../utils';
import type { AddressVO, UserCouponVO } from '../../types';

interface SkuInfo {
  skuId: string;
  skuName: string;
  skuImage: string;
  price: number;
  quantity: number;
}

interface CreateState {
  source?: 'cart';
  skuItems?: { skuId: string; quantity: number }[];
  skuInfo?: SkuInfo[];
}

export default function OrderCreatePage() {
  const navigate = useNavigate();
  const location = useLocation();
  const state = (location.state || {}) as CreateState;

  const [items, setItems] = useState<SkuInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [addresses, setAddresses] = useState<AddressVO[]>([]);
  const [addressId, setAddressId] = useState<string | undefined>();
  const [coupons, setCoupons] = useState<UserCouponVO[]>([]);
  const [couponId, setCouponId] = useState<string | undefined>();
  const [submitting, setSubmitting] = useState(false);

  // 幂等键：一次下单流程用同一个（防双击重复下单），页面卸载/重进重新生成
  const bizIdentifierRef = useRef(
    typeof crypto !== 'undefined' && crypto.randomUUID
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`
  );

  useEffect(() => {
    const init = async () => {
      try {
        let skuInfo: SkuInfo[] = [];
        if (state.source === 'cart') {
          const cartResp = await getCartAgg();
          const checked = (cartResp.data.data?.items || []).filter(i => i.checked);
          skuInfo = checked.map(i => ({
            skuId: i.skuId,
            skuName: i.skuName,
            skuImage: i.skuImage,
            price: Number(i.price),
            quantity: i.quantity,
          }));
        } else if (state.skuInfo && state.skuInfo.length) {
          skuInfo = state.skuInfo;
        }
        if (skuInfo.length === 0) {
          message.warning('没有可结算的商品');
        }
        setItems(skuInfo);

        const [addrResp, couponResp] = await Promise.all([
          getAddressList(),
          getAvailableCoupons(),
        ]);
        const addrList = addrResp.data.data || [];
        setAddresses(addrList);
        const def = addrList.find(a => a.isDefault === 1) || addrList[0];
        setAddressId(def?.id);
        setCoupons(couponResp.data.data || []);
      } catch (e: any) {
        message.error(e.response?.data?.message || '加载结算信息失败');
      } finally {
        setLoading(false);
      }
    };
    init();
  }, []);

  const subtotal = items.reduce((s, it) => s + it.price * it.quantity, 0);
  const selectedCoupon = coupons.find(c => c.id === couponId);
  // 对齐后端 CouponService.calculateDiscount：1=满减(减discountValue) 2=折扣(售价×discountValue/10) 3=无门槛(减discountValue)
  let discount = 0;
  if (selectedCoupon && subtotal >= selectedCoupon.minAmount) {
    if (selectedCoupon.type === 1 || selectedCoupon.type === 3) {
      discount = selectedCoupon.discountValue;
    } else if (selectedCoupon.type === 2) {
      discount = Math.round((subtotal - subtotal * selectedCoupon.discountValue / 10) * 100) / 100;
    }
  }
  discount = Math.min(discount, subtotal); // 减免不能超过订单金额
  const payAmount = Math.max(0, subtotal - discount);

  const handleSubmit = async () => {
    if (items.length === 0) return;
    if (!addressId) { message.warning('请选择收货地址'); return; }
    setSubmitting(true);
    try {
      const resp = await createOrder({
        skuItems: items.map(it => ({ skuId: it.skuId, quantity: it.quantity })),
        addressId,
        couponId,
        bizIdentifier: bizIdentifierRef.current,
      });
      message.success('下单成功');
      navigate(`/order/${resp.data.data.orderId}`);
    } catch (e: any) {
      message.error(e.response?.data?.message || '下单失败');
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) return <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" /></div>;

  if (items.length === 0) {
    return (
      <Empty description="没有可结算的商品" style={{ padding: 80 }}>
        <Button type="primary" onClick={() => navigate('/cart')}>返回购物车</Button>
      </Empty>
    );
  }

  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>确认订单</h2>

      <Card title="收货地址" style={{ marginBottom: 16 }}>
        {addresses.length === 0 ? (
          <Button type="link" onClick={() => navigate('/me/address')}>去添加收货地址</Button>
        ) : (
          <Radio.Group value={addressId} onChange={(e) => setAddressId(e.target.value)} style={{ width: '100%' }}>
            <Space direction="vertical">
              {addresses.map(a => (
                <Radio key={a.id} value={a.id}>
                  {a.receiverName} {a.receiverPhone} · {a.province}{a.city}{a.district}{a.detailAddress}
                  {a.isDefault === 1 && ' (默认)'}
                </Radio>
              ))}
            </Space>
          </Radio.Group>
        )}
      </Card>

      <Card title="商品清单" style={{ marginBottom: 16 }}>
        {items.map((it) => (
          <div key={it.skuId} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '8px 0', borderBottom: '1px solid #f5f5f5' }}>
            <img src={it.skuImage} alt="" style={{ width: 56, height: 56, objectFit: 'cover', borderRadius: 8, background: '#f0f0f0' }}
              onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }} />
            <div style={{ flex: 1 }}>{it.skuName}</div>
            <span>{formatPrice(it.price)} × {it.quantity}</span>
          </div>
        ))}
      </Card>

      <Card title="优惠券" style={{ marginBottom: 16 }}>
        {coupons.length === 0 ? (
          <div style={{ color: '#999' }}>暂无可用优惠券</div>
        ) : (
          <Radio.Group value={couponId ?? 0} onChange={(e) => setCouponId(e.target.value === 0 ? undefined : e.target.value)}>
            <Space direction="vertical">
              <Radio value={0}>不使用优惠券</Radio>
              {coupons.map(c => (
                <Radio key={c.id} value={c.id}>
                  {c.name}（{c.type === 2 ? `满${c.minAmount}打${c.discountValue}折` : `满${c.minAmount}减${c.discountValue}`}）
                </Radio>
              ))}
            </Space>
          </Radio.Group>
        )}
      </Card>

      <Card>
        <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 32 }}>
          <div style={{ textAlign: 'right', lineHeight: '28px' }}>
            <div>商品金额：{formatPrice(subtotal)}</div>
            {discount > 0 && <div style={{ color: '#ff4d4f' }}>优惠券：-{formatPrice(discount)}</div>}
            <div style={{ fontSize: 18 }}>
              应付：
              <span style={{ color: '#ff4d4f', fontSize: 24, fontWeight: 700 }}>{formatPrice(payAmount)}</span>
            </div>
          </div>
          <Button type="primary" size="large" loading={submitting} onClick={handleSubmit}>
            提交订单
          </Button>
        </div>
      </Card>
    </div>
  );
}
