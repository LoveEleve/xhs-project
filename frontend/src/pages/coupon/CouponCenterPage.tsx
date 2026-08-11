import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Button, Empty, Spin, Tag, Space, message } from 'antd';
import { getCouponTemplateList, claimCoupon } from '../../api/coupon';
import { COUPON_TYPE_MAP, formatDate } from '../../utils';
import type { CouponTemplateVO } from '../../types';

export default function CouponCenterPage() {
  const navigate = useNavigate();
  const [list, setList] = useState<CouponTemplateVO[]>([]);
  const [loading, setLoading] = useState(true);
  const [claimingId, setClaimingId] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      const resp = await getCouponTemplateList();
      setList(resp.data.data || []);
    } catch (e: any) {
      message.error(e.response?.data?.message || '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const handleClaim = async (templateId: string | number) => {
    setClaimingId(String(templateId));
    try {
      await claimCoupon(templateId);
      message.success('领取成功');
      load();
    } catch (e: any) {
      message.error(e.response?.data?.message || '领取失败');
    } finally {
      setClaimingId(null);
    }
  };

  const renderDiscount = (c: CouponTemplateVO) => {
    if (c.type === 2) return `${c.discountValue}折`;
    return `¥${c.discountValue}`;
  };

  const renderRule = (c: CouponTemplateVO) => {
    if (c.type === 1) return `满${c.minAmount}可用`;
    if (c.type === 2) return `满${c.minAmount}可用`;
    return '无门槛';
  };

  if (loading) return <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" /></div>;
  if (list.length === 0) return <Empty description="暂无可领取的优惠券" style={{ padding: 60 }} />;

  return (
    <div style={{ maxWidth: 700, margin: '0 auto' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>领券中心</h2>
        <Button type="link" onClick={() => navigate('/me/coupon')}>我的优惠券</Button>
      </div>
      {list.map(c => (
        <Card key={c.id} style={{ marginBottom: 12 }}
          styles={{ body: { display: 'flex', justifyContent: 'space-between', alignItems: 'center' } }}>
          <Space size={16} align="center">
            <div style={{ color: '#ff4d4f', fontSize: 24, fontWeight: 700, minWidth: 70, textAlign: 'center' }}>
              {renderDiscount(c)}
            </div>
            <div>
              <div style={{ fontWeight: 600 }}>{c.name} <Tag color="blue">{COUPON_TYPE_MAP[c.type] || '优惠券'}</Tag></div>
              <div style={{ color: '#999', fontSize: 12 }}>
                {renderRule(c)} · 有效期至 {formatDate(c.validEnd)}
              </div>
              {c.remainCount <= 5 && (
                <div style={{ color: '#ff4d4f', fontSize: 12 }}>仅剩 {c.remainCount} 张</div>
              )}
            </div>
          </Space>
          <Button
            type="primary"
            loading={claimingId === c.id}
            disabled={c.remainCount <= 0 || c.status !== 1}
            onClick={() => handleClaim(c.id)}
          >
            {c.remainCount <= 0 ? '已抢光' : '领取'}
          </Button>
        </Card>
      ))}
    </div>
  );
}
