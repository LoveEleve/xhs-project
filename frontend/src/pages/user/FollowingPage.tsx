import { useParams } from 'react-router-dom';
import FollowList from '../../components/FollowList';

export default function FollowingPage() {
  const { userId } = useParams<{ userId: string }>();
  return (
    <div style={{ maxWidth: 700, margin: '0 auto' }}>
      <h2 style={{ marginBottom: 16 }}>关注</h2>
      {userId ? <FollowList mode="following" userId={userId} /> : null}
    </div>
  );
}
