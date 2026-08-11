import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Form, Input, Button, message } from 'antd';
import { changePassword } from '../../api/auth';
import { useAuthStore } from '../../store/authStore';

export default function PasswordPage() {
  const navigate = useNavigate();
  const { logout } = useAuthStore();
  const [loading, setLoading] = useState(false);

  const onFinish = async (values: { oldPwd: string; newPwd: string }) => {
    setLoading(true);
    try {
      await changePassword({ oldPwd: values.oldPwd, newPwd: values.newPwd });
      message.success('密码修改成功，请重新登录');
      await logout();
      navigate('/login');
    } catch (e: any) {
      message.error(e.response?.data?.message || '修改失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ maxWidth: 480, margin: '0 auto' }}>
      <Card title="修改密码">
        <Form onFinish={onFinish} layout="vertical">
          <Form.Item name="oldPwd" label="旧密码" rules={[{ required: true, message: '请输入旧密码' }]}>
            <Input.Password placeholder="旧密码" />
          </Form.Item>
          <Form.Item name="newPwd" label="新密码" rules={[
            { required: true, message: '请输入新密码' },
            { min: 6, max: 64, message: '密码长度6~64位' },
          ]}>
            <Input.Password placeholder="新密码（6~64位）" />
          </Form.Item>
          <Form.Item name="confirm" label="确认新密码" dependencies={['newPwd']} rules={[
            { required: true, message: '请确认新密码' },
            ({ getFieldValue }) => ({
              validator(_, value) {
                if (!value || getFieldValue('newPwd') === value) return Promise.resolve();
                return Promise.reject(new Error('两次密码不一致'));
              },
            }),
          ]}>
            <Input.Password placeholder="再次输入新密码" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block loading={loading}>确认修改</Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
}
