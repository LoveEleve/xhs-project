import { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Spin, Alert, Button, Space, Steps, Descriptions, Divider, Tag, message, Modal } from 'antd';
import { getOrder, cancelOrder, confirmOrder, createPayment } from '../../api/order';
import { formatPrice, formatDate, ORDER_STATUS_MAP } from '../../utils';
import type { OrderVO } from '../../types';

export default function OrderDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [order, setOrder] = useState<OrderVO | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [paying, setPaying] = useState(false);

  const load = () => {
    if (!id) return;
    setLoading(true);
    getOrder(id)
      .then((resp) => { setOrder(resp.data.data); setLoading(false); })
      .catch((e) => { setError(e.response?.data?.message || '加载失败'); setLoading(false); });
  };

  useEffect(() => { load(); }, [id]);

  const handlePay = async () => {
    if (!order) return;
    setPaying(true);
    try {
      const resp = await createPayment(order.orderId, 1);
      if (resp.data.code === 200) {
        message.success('支付成功');
        // 异步支付：轮询订单状态，等待回调把订单转为已付款（1~3s 延迟）
        await pollOrderPaid(order.orderId, 6);
        load();
      } else {
        message.error(resp.data.message || '支付失败');
        load();
      }
    } catch (e: any) {
      message.error(e.response?.data?.message || '支付失败');
      load();
    } finally {
      setPaying(false);
    }
  };

  // 每 1s 查一次订单状态，最多 times 次，直到不再待付款
  const pollOrderPaid = async (orderId: string | number, times: number) => {
    for (let i = 0; i < times; i++) {
      await new Promise(r => setTimeout(r, 1000));
      try {
        const r = await getOrder(orderId);
        if (r.data.data?.status !== 0) return;
      } catch { /* 忽略单次失败 */ }
    }
  };

  const handleCancel = () => {
    if (!order) return;
    Modal.confirm({
      title: '确认取消该订单？',
      onOk: async () => {
        try {
          await cancelOrder(order.orderId);
          message.success('已取消');
          load();
        } catch (e: any) { message.error(e.response?.data?.message || '取消失败'); }
      },
    });
  };

  const handleConfirm = () => {
    if (!order) return;
    Modal.confirm({
      title: '确认已收到货？',
      onOk: async () => {
        try {
          await confirmOrder(order.orderId);
          message.success('已确认收货');
          load();
        } catch (e: any) { message.error(e.response?.data?.message || '操作失败'); }
      },
    });
  };

  if (loading) return <div style={{ textAlign: 'center', padding: 80 }}><Spin size="large" /></div>;
  if (error) return <Alert type="error" message={error} style={{ margin: 24 }} />;
  if (!order) return <Alert type="error" message="订单不存在" style={{ margin: 24 }} />;

  const statusMeta = ORDER_STATUS_MAP[order.status] || { label: '未知', color: '#999' };

  const renderActions = () => {
    if (order.status === 0) {
      return (
        <Space>
          <Button type="primary" loading={paying} onClick={handlePay}>去支付</Button>
          <Button onClick={handleCancel}>取消订单</Button>
        </Space>
      );
    }
    if (order.status === 2) {
      return <Button type="primary" onClick={handleConfirm}>确认收货</Button>;
    }
    return null;
  };

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h2 style={{ margin: 0 }}>
          订单详情 <Tag color={statusMeta.color}>{order.statusDesc || statusMeta.label}</Tag>
        </h2>
        <Button onClick={() => navigate('/order/list')}>返回订单列表</Button>
      </div>

      {order.status === 0 || order.status === 1 ? (
        <Card style={{ marginBottom: 16 }}>
          <Steps
            current={order.status === 0 ? 0 : 1}
            items={[
              { title: '提交订单' },
              { title: '支付完成' },
              { title: '已发货' },
              { title: '已完成' },
            ]}
          />
        </Card>
      ) : (
        <Alert
          style={{ marginBottom: 16 }}
          type="info"
          showIcon
          message={`订单状态：${order.statusDesc || statusMeta.label}`}
        />
      )}

      <Card title="收货信息" style={{ marginBottom: 16 }}>
        <Descriptions column={1} size="small">
          <Descriptions.Item label="收货地址">{order.addressSnapshot || '—'}</Descriptions.Item>
        </Descriptions>
      </Card>

      <Card title="商品清单" style={{ marginBottom: 16 }}>
        {order.items?.map((it) => (
          <div key={it.skuId} style={{ display: 'flex', gap: 12, alignItems: 'center', padding: '8px 0', borderBottom: '1px solid #f5f5f5' }}>
            <img src={it.skuImage} alt="" style={{ width: 56, height: 56, objectFit: 'cover', borderRadius: 8, background: '#f0f0f0' }}
              onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }} />
            <div style={{ flex: 1 }}>{it.skuName}</div>
            <div style={{ color: '#999' }}>{formatPrice(it.price)} × {it.quantity}</div>
            <div style={{ fontWeight: 600, width: 90, textAlign: 'right' }}>{formatPrice(it.totalAmount)}</div>
          </div>
        ))}
      </Card>

      <Card title="订单信息">
        <Descriptions column={1} size="small">
          <Descriptions.Item label="订单编号">{order.orderNo}</Descriptions.Item>
          <Descriptions.Item label="创建时间">{formatDate(order.createdAt, true)}</Descriptions.Item>
          {order.paidAt && <Descriptions.Item label="支付时间">{formatDate(order.paidAt, true)}</Descriptions.Item>}
          <Descriptions.Item label="商品金额">{formatPrice(order.totalAmount)}</Descriptions.Item>
          {order.discountAmount > 0 && (
            <Descriptions.Item label="优惠金额"><span style={{ color: '#ff4d4f' }}>-{formatPrice(order.discountAmount)}</span></Descriptions.Item>
          )}
          <Descriptions.Item label="实付金额">
            <span style={{ color: '#ff4d4f', fontSize: 20, fontWeight: 700 }}>{formatPrice(order.payAmount)}</span>
          </Descriptions.Item>
        </Descriptions>
        <Divider />
        <div style={{ textAlign: 'right' }}>{renderActions()}</div>
      </Card>
    </div>
  );
}
