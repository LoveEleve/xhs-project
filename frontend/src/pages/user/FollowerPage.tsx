import { useParams } from 'react-router-dom';
import FollowList from '../../components/FollowList';

export default function FollowerPage() {
  const { userId } = useParams<{ userId: string }>();
  return (
    <div style={{ maxWidth: 700, margin: '0 auto' }}>
      <h2 style={{ marginBottom: 16 }}>粉丝</h2>
      {userId ? <FollowList mode="follower" userId={userId} /> : null}
    </div>
  );
}
