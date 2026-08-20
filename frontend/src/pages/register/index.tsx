import { useState, useEffect } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { Card, Form, Input, Button, Image, message } from 'antd';
import { getCaptcha } from '../../api/auth';
import { useAuthStore } from '../../store/authStore';

export default function RegisterPage() {
  const navigate = useNavigate();
  const { register: storeRegister } = useAuthStore();
  const [loading, setLoading] = useState(false);
  const [captchaImage, setCaptchaImage] = useState('');
  const [captchaKey, setCaptchaKey] = useState('');

  const fetchCaptcha = () => {
    getCaptcha().then(resp => {
      setCaptchaImage(resp.data.data.captchaImage);
      setCaptchaKey(resp.data.data.captchaKey);
    }).catch(() => message.error('获取验证码失败'));
  };

  useEffect(() => { fetchCaptcha(); }, []);

  const handleRegister = async (values: { username: string; password: string; phone?: string; captchaCode: string }) => {
    setLoading(true);
    try {
      await storeRegister(values.username, values.password, captchaKey, values.captchaCode, values.phone);
      message.success('注册成功，请登录');
      navigate('/login');
    } catch (e: any) {
      message.error(e.response?.data?.message || '注册失败');
      fetchCaptcha();
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ background: '#f5f5f5', minHeight: '100vh', display: 'flex', justifyContent: 'center', alignItems: 'center' }}>
      <Card style={{ width: 400 }}>
        <h2 style={{ textAlign: 'center', color: '#ff4d4f', marginBottom: 32 }}>my-xhs</h2>
        <Form onFinish={handleRegister} size="large">
          <Form.Item name="username" rules={[
            { required: true, message: '请输入用户名' },
            { min: 4, max: 32, message: '用户名长度4~32位' },
            { pattern: /^[a-zA-Z0-9_]+$/, message: '用户名只能包含字母、数字和下划线' },
          ]}>
            <Input placeholder="用户名（4~32位，字母数字下划线）" />
          </Form.Item>
          <Form.Item name="phone" rules={[
            { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确' },
          ]}>
            <Input placeholder="手机号（选填）" />
          </Form.Item>
          <Form.Item name="password" rules={[
            { required: true, message: '请输入密码' },
            { min: 6, message: '密码至少6位' },
          ]}>
            <Input.Password placeholder="密码" />
          </Form.Item>
          <Form.Item name="confirm" dependencies={['password']} rules={[
            { required: true, message: '请确认密码' },
            ({ getFieldValue }) => ({
              validator(_, value) {
                if (!value || getFieldValue('password') === value) return Promise.resolve();
                return Promise.reject(new Error('两次密码不一致'));
              },
            }),
          ]}>
            <Input.Password placeholder="确认密码" />
          </Form.Item>
          <Form.Item name="captchaCode" rules={[
            { required: true, message: '请输入验证码' },
            { len: 4, message: '验证码为4位' },
          ]}>
            <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
              <Input placeholder="图形验证码" style={{ flex: 1 }} />
              {captchaImage && (
                <Image
                  src={captchaImage}
                  width={100}
                  height={40}
                  preview={false}
                  onClick={fetchCaptcha}
                  style={{ cursor: 'pointer', border: '1px solid #d9d9d9', borderRadius: 4 }}
                />
              )}
            </div>
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block loading={loading}>
              注册
            </Button>
          </Form.Item>
        </Form>
        <div style={{ textAlign: 'center', marginTop: 16 }}>
          <Link to="/login">已有账号？去登录</Link>
        </div>
      </Card>
    </div>
  );
}
