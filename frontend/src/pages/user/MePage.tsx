import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Avatar, Row, Col, Button, Space, Typography } from 'antd';
import { UserOutlined, FileTextOutlined, StarOutlined, EnvironmentOutlined, TagOutlined, BlockOutlined, KeyOutlined } from '@ant-design/icons';
import { useAuthStore } from '../../store/authStore';

export default function MePage() {
  const navigate = useNavigate();
  const { user, userId, fetchUser } = useAuthStore();

  useEffect(() => {
    if (!user) fetchUser();
  }, []);

  const menuItems = [
    { key: 'notes', label: '我的笔记', icon: <FileTextOutlined />, path: '/me/notes' },
    { key: 'favorites', label: '我的收藏', icon: <StarOutlined />, path: '/me/favorites' },
    { key: 'address', label: '收货地址', icon: <EnvironmentOutlined />, path: '/me/address' },
    { key: 'coupon', label: '我的优惠券', icon: <TagOutlined />, path: '/me/coupon' },
    { key: 'block', label: '拉黑列表', icon: <BlockOutlined />, path: '/me/block' },
    { key: 'password', label: '修改密码', icon: <KeyOutlined />, path: '/me/password' },
  ];

  return (
    <div style={{ maxWidth: 800, margin: '0 auto' }}>
      <Card style={{ marginBottom: 16 }}>
        <Space size={24} align="center">
          <Avatar size={72} src={user?.avatar} icon={<UserOutlined />}>{user?.nickname?.[0]}</Avatar>
          <div>
            <Typography.Title level={4} style={{ margin: 0 }}>{user?.nickname || user?.username || '用户'}</Typography.Title>
            <Typography.Text type="secondary">{user?.signature || '这个人很懒，什么都没写'}</Typography.Text>
          </div>
        </Space>
        <Button type="link" style={{ float: 'right' }} onClick={() => navigate(`/user/${userId}`)}>查看公开主页</Button>
      </Card>

      <Card title="我的功能" style={{ marginBottom: 16 }}>
        <Row gutter={[16, 16]}>
          {menuItems.map(item => (
            <Col key={item.key} span={12}>
              <Button
                block
                size="large"
                icon={item.icon}
                style={{ textAlign: 'left', height: 56 }}
                onClick={() => navigate(item.path)}
              >
                {item.label}
              </Button>
            </Col>
          ))}
        </Row>
      </Card>

      <Card title="账号信息" size="small">
        <Typography.Paragraph style={{ marginBottom: 4 }}>
          <Typography.Text type="secondary">用户名：</Typography.Text> {user?.username}
        </Typography.Paragraph>
        <Typography.Paragraph style={{ marginBottom: 4 }}>
          <Typography.Text type="secondary">手机号：</Typography.Text> {user?.phone || '—'}
        </Typography.Paragraph>
        <Typography.Paragraph style={{ marginBottom: 4 }}>
          <Typography.Text type="secondary">邮箱：</Typography.Text> {user?.email || '—'}
        </Typography.Paragraph>
      </Card>
    </div>
  );
}
