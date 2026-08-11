import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Tabs, Empty, Spin, Card, message } from 'antd';
import { getUserCouponList } from '../../api/coupon';
import { COUPON_TYPE_MAP, formatDate } from '../../utils';
import type { UserCouponVO } from '../../types';

const STATUS_TABS = [
  { key: '0', label: '未使用' },
  { key: '1', label: '已使用' },
  { key: '2', label: '已过期' },
];

export default function CouponPage() {
  const navigate = useNavigate();
  const [status, setStatus] = useState('0');
  const [list, setList] = useState<UserCouponVO[]>([]);
  const [loading, setLoading] = useState(true);

  const load = async (st: string) => {
    setLoading(true);
    try {
      const resp = await getUserCouponList(Number(st));
      setList(resp.data.data || []);
    } catch (e: any) {
      message.error(e.response?.data?.message || '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(status); }, [status]);

  return (
    <div style={{ maxWidth: 700, margin: '0 auto' }}>
      <Tabs activeKey={status} onChange={setStatus} items={STATUS_TABS} />
      {loading ? (
        <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" /></div>
      ) : list.length === 0 ? (
        <Empty description="暂无优惠券" style={{ padding: 60 }}>
          <a onClick={() => navigate('/coupon')}>去领券</a>
        </Empty>
      ) : (
        list.map(coupon => (
          <Card key={coupon.id} size="small" style={{ marginBottom: 12 }}
            styles={{ body: { display: 'flex', justifyContent: 'space-between', alignItems: 'center' } }}>
            <div>
              <div style={{ fontWeight: 600 }}>{coupon.name}</div>
              <div style={{ color: '#999', fontSize: 12 }}>
                {COUPON_TYPE_MAP[coupon.type] || '优惠券'} · 有效期至 {formatDate(coupon.validEnd)}
              </div>
            </div>
            <div style={{ color: '#ff4d4f', fontSize: 22, fontWeight: 700 }}>
              {coupon.type === 2 ? `${coupon.discountValue}折` : `¥${coupon.discountValue}`}
            </div>
          </Card>
        ))
      )}
    </div>
  );
}
