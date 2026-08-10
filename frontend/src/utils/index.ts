export const formatPrice = (price: number): string => `¥${price.toFixed(2)}`;

export const formatDate = (dateStr: string, withTime = false): string => {
  if (!dateStr) return '';
  const d = new Date(dateStr);
  const pad = (n: number) => String(n).padStart(2, '0');
  const date = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  if (withTime) return `${date} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  return date;
};

export const formatRelativeTime = (dateStr: string): string => {
  if (!dateStr) return '';
  const now = Date.now();
  const then = new Date(dateStr).getTime();
  const diff = now - then;
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return '刚刚';
  if (mins < 60) return `${mins}分钟前`;
  const hours = Math.floor(mins / 60);
  const today = new Date(now);
  const thenDate = new Date(then);
  if (hours < 24 && thenDate.getDate() === today.getDate()) return `${hours}小时前`;
  if (hours < 48 && thenDate.getDate() === today.getDate() - 1) {
    const pad = (n: number) => String(n).padStart(2, '0');
    return `昨天 ${pad(thenDate.getHours())}:${pad(thenDate.getMinutes())}`;
  }
  if (thenDate.getFullYear() === today.getFullYear()) {
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${pad(thenDate.getMonth() + 1)}-${pad(thenDate.getDate())} ${pad(thenDate.getHours())}:${pad(thenDate.getMinutes())}`;
  }
  return formatDate(dateStr);
};

export const ORDER_STATUS_MAP: Record<number, { label: string; color: string }> = {
  0: { label: '待支付', color: '#ff4d4f' },
  1: { label: '已支付', color: '#1677ff' },
  2: { label: '已发货', color: '#722ed1' },
  3: { label: '已完成', color: '#52c41a' },
  4: { label: '已取消', color: '#999' },
  5: { label: '已退款', color: '#999' },
};

export const COUPON_TYPE_MAP: Record<number, string> = {
  1: '满减券',
  2: '折扣券',
  3: '无门槛券',
};

export const parseSpecs = (specs: string): Record<string, string> => {
  try { return JSON.parse(specs); } catch { return {}; }
};
