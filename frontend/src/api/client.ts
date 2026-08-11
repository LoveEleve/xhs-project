import axios from 'axios';
import { getHmacSecret, setHmacSecret, signRequest } from '../utils/hmac';

const client = axios.create({
  baseURL: '/api',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
});

// 后端已将 Long 统一序列化为 String（雪花 ID 超 JS 安全整数，防精度丢失）。
// ID 字段保持字符串；其余纯数字字符串（计数/总数/时间戳等）转回 number，保证前端算术/显示正确。
const ID_KEYS = new Set([
  'id', 'noteId', 'userId', 'authorId', 'spuId', 'skuId', 'orderId',
  'targetId', 'parentId', 'replyToId', 'senderId', 'commentId',
  'templateId', 'couponId', 'peerId', 'receiverId', 'addressId',
  'categoryId', 'brandId', 'sellerId', 'conversationId', 'followerUserId',
  'followeeUserId', 'bizId', 'localMsgId', 'orderNo',
]);

function normalizeNumbers(value: unknown, key?: string): unknown {
  if (Array.isArray(value)) {
    return value.map((v) => normalizeNumbers(v, key));
  }
  if (value && typeof value === 'object') {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value)) {
      out[k] = normalizeNumbers(v, k);
    }
    return out;
  }
  // 叶子：纯数字字符串且非 ID 键 → 转 number
  if (typeof value === 'string' && /^-?\d+$/.test(value)) {
    if (key && ID_KEYS.has(key)) return value; // ID 保持字符串
    const n = Number(value);
    return Number.isSafeInteger(n) || !Number.isNaN(n) ? n : value;
  }
  return value;
}

client.interceptors.request.use(async (config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }

  // HMAC 签名：凡是有 hmacSecret（已登录）的请求都签名。
  // 网关对白名单路径跳过校验，对非白名单路径（写操作 + 需登录的读操作）强制要求签名。
  if (getHmacSecret()) {
    const fullPath = (client.defaults.baseURL || '') + (config.url || '');
    const headers = await signRequest(config.method || 'GET', fullPath);
    if (headers) {
      Object.assign(config.headers, headers);
    }
  }
  return config;
});

client.interceptors.response.use(
  (resp) => {
    const body = resp.data;
    // 先把 Long 字符串计数/总数归一化为 number（ID 保持字符串）
    if (body && typeof body === 'object') {
      resp.data = normalizeNumbers(body);
    }
    // 后端业务错误约定：HTTP 200 + body.code != 200（BizException 无 @ResponseStatus）。
    // 统一在此 reject，让各页面 catch 到真实业务错误信息，避免误当成功。
    const b2 = resp.data;
    if (b2 && typeof b2.code === 'number' && b2.code !== 200) {
      const err = new Error(b2.message || '请求失败') as Error & { response?: unknown };
      (err as any).response = { data: b2, status: resp.status };
      return Promise.reject(err);
    }
    return resp;
  },
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('token');
      localStorage.removeItem('userId');
      setHmacSecret(null);
      if (window.location.pathname !== '/login') {
        window.location.href = '/login';
      }
    }
    return Promise.reject(err);
  }
);

export default client;
