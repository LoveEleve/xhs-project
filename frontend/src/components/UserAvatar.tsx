import { Avatar } from 'antd';
import { UserOutlined } from '@ant-design/icons';

interface UserAvatarProps {
  src?: string;
  name: string;
  size?: number;
  onClick?: () => void;
}

export default function UserAvatar({ src, name, size = 40, onClick }: UserAvatarProps) {
  return (
    <span
      onClick={onClick}
      style={{
        display: 'inline-flex', alignItems: 'center', gap: 8,
        cursor: onClick ? 'pointer' : 'default',
      }}
    >
      <Avatar src={src} size={size} icon={<UserOutlined />}>
        {name?.[0]}
      </Avatar>
      <span style={{ fontWeight: 500 }}>{name}</span>
    </span>
  );
}
