import { useNavigate } from 'react-router-dom';
import { Button } from 'antd';

export default function NotFoundPage() {
  const navigate = useNavigate();
  return (
    <div style={{ textAlign: 'center', paddingTop: 120 }}>
      <h1 style={{ fontSize: 72, color: '#d9d9d9', marginBottom: 16 }}>404</h1>
      <p style={{ fontSize: 16, color: '#999', marginBottom: 32 }}>页面不存在</p>
      <Button type="primary" onClick={() => navigate('/feed')}>返回首页</Button>
    </div>
  );
}
