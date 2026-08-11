// HMAC 请求签名工具
// 网关 HmacSignatureFilter 要求所有非白名单路径携带 X-Timestamp / X-Nonce / X-Signature。
// 签名 = Base64( HMAC-SHA256( METHOD + path + timestamp + nonce, hmacSecret ) )
// hmacSecret 为登录响应中的 per-session 密钥。

const HMAC_KEY = 'hmacSecret';

export const getHmacSecret = (): string | null => localStorage.getItem(HMAC_KEY);

export const setHmacSecret = (secret: string | null) => {
  if (secret) localStorage.setItem(HMAC_KEY, secret);
  else localStorage.removeItem(HMAC_KEY);
};

async function hmacSha256(data: string, key: string): Promise<string> {
  const enc = new TextEncoder();
  const cryptoKey = await crypto.subtle.importKey(
    'raw',
    enc.encode(key),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign']
  );
  const sig = await crypto.subtle.sign('HMAC', cryptoKey, enc.encode(data));
  const bytes = new Uint8Array(sig);
  let binary = '';
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
  return btoa(binary);
}

function randomNonce(): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
  return Math.random().toString(36).slice(2) + Date.now().toString(36);
}

/**
 * 计算某次请求的签名头。
 * @param method  HTTP 方法（大写）
 * @param url     请求相对路径，含 /api 前缀、不含 query（如 /api/order/create）
 */
export async function signRequest(method: string, url: string): Promise<Record<string, string> | null> {
  const secret = getHmacSecret();
  if (!secret) return null;

  const path = url.split('?')[0];
  const timestamp = String(Date.now());
  const nonce = randomNonce();
  const signStr = method.toUpperCase() + path + timestamp + nonce;
  const signature = await hmacSha256(signStr, secret);

  return {
    'X-Timestamp': timestamp,
    'X-Nonce': nonce,
    'X-Signature': signature,
  };
}
