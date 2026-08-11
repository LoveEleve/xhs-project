import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Tabs, Card, Empty, Spin, Button, Space, Tag, Modal, message } from 'antd';
import { getOrderList, getOrder, cancelOrder, createPayment } from '../../api/order';
import { formatPrice, formatDate, ORDER_STATUS_MAP } from '../../utils';
import type { OrderVO } from '../../types';

const TABS = [
  { key: 'all', label: '全部' },
  { key: '0', label: '待支付' },
  { key: '1', label: '已支付' },
  { key: '2', label: '已发货' },
  { key: '3', label: '已完成' },
];

export default function OrderListPage() {
  const navigate = useNavigate();
  const [status, setStatus] = useState<string>('all');
  const [orders, setOrders] = useState<OrderVO[]>([]);
  const [loading, setLoading] = useState(true);

  const load = async (st: string) => {
    setLoading(true);
    try {
      const resp = await getOrderList(st === 'all' ? {} : { status: Number(st) });
      setOrders(resp.data.data || []);
    } catch (e: any) {
      message.error(e.response?.data?.message || '加载订单失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(status); }, [status]);

  const handlePay = async (orderId: string | number) => {
    try {
      const resp = await createPayment(orderId, 1);
      if (resp.data.code === 200) {
        message.success('支付成功');
        // 异步支付：轮询订单状态等待回调完成，再刷新列表
        await pollOrderPaid(orderId, 6);
        load(status);
      } else {
        message.error(resp.data.message || '支付失败');
        load(status);
      }
    } catch (e: any) {
      message.error(e.response?.data?.message || '支付失败');
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

  const handleCancel = (orderId: string | number) => {
    Modal.confirm({
      title: '确认取消该订单？',
      onOk: async () => {
        try {
          await cancelOrder(orderId);
          message.success('已取消');
          load(status);
        } catch (e: any) { message.error(e.response?.data?.message || '取消失败'); }
      },
    });
  };

  const renderActions = (order: OrderVO) => {
    if (order.status === 0) {
      return (
        <Space>
          <Button type="primary" size="small" onClick={() => handlePay(order.orderId)}>去支付</Button>
          <Button size="small" onClick={() => handleCancel(order.orderId)}>取消订单</Button>
        </Space>
      );
    }
    if (order.status === 2) {
      return <Button type="primary" size="small" onClick={() => navigate(`/order/${order.orderId}`)}>确认收货</Button>;
    }
    return <Button size="small" onClick={() => navigate(`/order/${order.orderId}`)}>查看详情</Button>;
  };

  return (
    <div>
      <Tabs activeKey={status} onChange={setStatus} items={TABS} />
      {loading ? (
        <div style={{ textAlign: 'center', padding: 60 }}><Spin size="large" /></div>
      ) : orders.length === 0 ? (
        <Empty description="暂无订单" style={{ padding: 80 }} />
      ) : (
        orders.map(order => (
          <Card
            key={order.orderId}
            style={{ marginBottom: 12 }}
            title={
              <Space>
                <span>订单号：{order.orderNo}</span>
                <Tag color={ORDER_STATUS_MAP[order.status]?.color}>{order.statusDesc || ORDER_STATUS_MAP[order.status]?.label}</Tag>
              </Space>
            }
            extra={<span style={{ color: '#999', fontSize: 13 }}>{formatDate(order.createdAt, true)}</span>}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div style={{ flex: 1 }}>
                {order.items?.map((it) => (
                  <div key={it.skuId} style={{ display: 'flex', gap: 12, alignItems: 'center', padding: '4px 0' }}>
                    <img src={it.skuImage} alt="" style={{ width: 48, height: 48, objectFit: 'cover', borderRadius: 6, background: '#f0f0f0' }}
                      onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }} />
                    <div>
                      <div>{it.skuName}</div>
                      <div style={{ color: '#999', fontSize: 12 }}>{formatPrice(it.price)} × {it.quantity}</div>
                    </div>
                  </div>
                ))}
              </div>
              <div style={{ textAlign: 'right', marginLeft: 24 }}>
                <div style={{ color: '#ff4d4f', fontWeight: 600, marginBottom: 8 }}>
                  实付 {formatPrice(order.payAmount)}
                </div>
                {renderActions(order)}
              </div>
            </div>
          </Card>
        ))
      )}
    </div>
  );
}
