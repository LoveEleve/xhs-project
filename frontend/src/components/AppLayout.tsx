import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { Layout, Input, Badge, Dropdown, Avatar, Space, Button } from 'antd';
import { BellOutlined, ShoppingCartOutlined, UserOutlined, PlusOutlined, MessageOutlined } from '@ant-design/icons';
import { useEffect } from 'react';
import { useAuthStore } from '../store/authStore';
import { useNotificationStore } from '../store/notificationStore';
import { useCartStore } from '../store/cartStore';
import { recordHotKeyword } from '../api/search';

const { Header, Content } = Layout;

export default function AppLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, logout, token } = useAuthStore();
  const { unreadCount, fetchUnread, connectSSE, disconnectSSE } = useNotificationStore();
  const { count: cartCount, fetchCount } = useCartStore();

  useEffect(() => {
    if (!token) {
      disconnectSSE();
      return;
    }
    fetchUnread();
    fetchCount();
    connectSSE();
    const interval = setInterval(() => {
      fetchUnread();
      fetchCount();
    }, 30000);
    return () => {
      clearInterval(interval);
      disconnectSSE();
    };
  }, [token, fetchUnread, fetchCount, connectSSE, disconnectSSE]);

  const handleSearch = (keyword: string) => {
    if (!keyword.trim()) return;
    recordHotKeyword(keyword.trim()).catch(() => {});
    navigate(`/search?keyword=${encodeURIComponent(keyword.trim())}`);
  };

  const fullScreenPaths = ['/login', '/register'];
  if (fullScreenPaths.includes(location.pathname)) {
    return (
      <Layout style={{ minHeight: '100vh', background: '#fff' }}>
        <Content>
          <Outlet />
        </Content>
      </Layout>
    );
  }

  const userMenuItems = [
    { key: 'profile', label: '我的主页', onClick: () => navigate('/me') },
    { key: 'notes', label: '我的笔记', onClick: () => navigate('/me/notes') },
    { key: 'favorites', label: '我的收藏', onClick: () => navigate('/me/favorites') },
    { key: 'orders', label: '我的订单', onClick: () => navigate('/order/list') },
    { key: 'address', label: '收货地址', onClick: () => navigate('/me/address') },
    { key: 'coupon', label: '我的优惠券', onClick: () => navigate('/me/coupon') },
    { key: 'block', label: '拉黑列表', onClick: () => navigate('/me/block') },
    { type: 'divider' as const },
    { key: 'logout', label: '退出登录', onClick: () => { logout(); navigate('/login'); } },
  ];

  return (
    <Layout style={{ minHeight: '100vh', background: '#f5f5f5' }}>
      <Header style={{
        position: 'fixed', top: 0, zIndex: 1000, width: '100%',
        background: '#fff', padding: '0 24px', display: 'flex',
        alignItems: 'center', justifyContent: 'space-between',
        height: 56, borderBottom: '1px solid #f0f0f0',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 20 }}>
          <span onClick={() => navigate('/feed')} style={{
            fontSize: 20, fontWeight: 700, color: '#ff4d4f', cursor: 'pointer',
          }}>
            my-xhs
          </span>
          <a onClick={() => navigate('/feed')} style={{
            color: location.pathname.startsWith('/feed') ? '#ff4d4f' : '#333', fontWeight: 500,
          }}>首页</a>
          <a onClick={() => navigate('/product')} style={{
            color: location.pathname.startsWith('/product') ? '#ff4d4f' : '#333', fontWeight: 500,
          }}>商品</a>
          <a onClick={() => navigate('/ai')} style={{
            color: location.pathname.startsWith('/ai') ? '#ff4d4f' : '#333', fontWeight: 500,
          }}>AI 诊断</a>
        </div>
        <div style={{ flex: 1, display: 'flex', justifyContent: 'center', padding: '0 32px' }}>
          <Input.Search
            placeholder="搜索笔记/商品..."
            onSearch={handleSearch}
            style={{ maxWidth: 400 }}
            allowClear
          />
        </div>
        <Space size={20}>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/note/publish')}>
            发布
          </Button>
          <Badge count={cartCount} size="small" offset={[4, -4]}>
            <ShoppingCartOutlined style={{ fontSize: 20, cursor: 'pointer' }} onClick={() => navigate('/cart')} />
          </Badge>
          <Badge count={unreadCount} size="small" offset={[4, -4]}>
            <BellOutlined style={{ fontSize: 20, cursor: 'pointer' }} onClick={() => navigate('/notification')} />
          </Badge>
          <MessageOutlined style={{ fontSize: 20, cursor: 'pointer' }} onClick={() => navigate('/im')} />
          <Dropdown menu={{ items: userMenuItems }} placement="bottomRight">
            <Space style={{ cursor: 'pointer' }}>
              <Avatar src={user?.avatar} size={32} icon={<UserOutlined />} />
              <span style={{ maxWidth: 80, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                {user?.nickname || '用户'}
              </span>
            </Space>
          </Dropdown>
        </Space>
      </Header>
      <Layout.Content style={{
        padding: '80px 16px 24px',
        maxWidth: 1100,
        margin: '0 auto',
        width: '100%',
      }}>
        <Outlet />
      </Layout.Content>
    </Layout>
  );
}
