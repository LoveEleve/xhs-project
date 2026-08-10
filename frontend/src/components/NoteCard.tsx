import { useNavigate } from 'react-router-dom';
import { Card, Typography } from 'antd';
import { HeartOutlined, StarOutlined, PlayCircleOutlined } from '@ant-design/icons';
import type { NoteCardVO } from '../types';

type NoteCardProps = NoteCardVO & { onClick?: () => void };

export default function NoteCard({ noteId, coverUrl, title, noteType, authorNickname, authorAvatar, likeCount, collectCount, onClick }: NoteCardProps) {
  const navigate = useNavigate();

  return (
    <Card
      hoverable
      style={{ overflow: 'hidden', borderRadius: 8 }}
      cover={
        <div style={{ position: 'relative', height: 180, overflow: 'hidden', background: '#f0f0f0' }}>
          <img
            alt={title}
            src={coverUrl}
            style={{ width: '100%', height: '100%', objectFit: 'cover' }}
            onError={(e) => {
              (e.target as HTMLImageElement).style.display = 'none';
              if (e.target.parentElement) {
                e.target.parentElement.style.display = 'flex';
                e.target.parentElement.style.alignItems = 'center';
                e.target.parentElement.style.justifyContent = 'center';
                e.target.parentElement.style.color = '#999';
                e.target.parentElement.textContent = '图片加载失败';
              }
            }}
          />
          {noteType === 1 && (
            <PlayCircleOutlined style={{
              position: 'absolute', bottom: 8, right: 8,
              fontSize: 24, color: 'rgba(255,255,255,0.9)',
              filter: 'drop-shadow(0 1px 2px rgba(0,0,0,0.3))',
            }} />
          )}
        </div>
      }
      bodyStyle={{ padding: '8px 12px' }}
      onClick={() => onClick ? onClick() : navigate(`/note/${noteId}`)}
    >
      <div style={{ display: 'flex', gap: 8, marginBottom: 8 }}>
        <div style={{
          width: 20, height: 20, borderRadius: '50%', overflow: 'hidden',
          background: '#ffd8bf', display: 'flex', alignItems: 'center', 
          justifyContent: 'center', fontSize: 10, color: '#ff4d4f', 
          fontWeight: 700, flexShrink: 0, position: 'relative',
        }}>
          <img
            src={authorAvatar}
            alt=""
            style={{ width: '100%', height: '100%', objectFit: 'cover' }}
            onError={(e) => {
              (e.target as HTMLImageElement).style.display = 'none';
            }}
          />
          <span style={{ position: 'absolute' }}>{authorNickname?.[0] || '?'}</span>
        </div>
        <Typography.Text ellipsis style={{ fontSize: 12, color: '#666', lineHeight: '20px' }}>
          {authorNickname}
        </Typography.Text>
      </div>
      <Typography.Paragraph
        ellipsis={{ rows: 2 }}
        style={{ marginBottom: 8, fontSize: 13, lineHeight: '20px', minHeight: 40 }}
      >
        {title}
      </Typography.Paragraph>
      <div style={{ display: 'flex', gap: 12, fontSize: 12, color: '#999' }}>
        <span><HeartOutlined /> {likeCount || 0}</span>
        <span><StarOutlined /> {collectCount || 0}</span>
      </div>
    </Card>
  );
}
