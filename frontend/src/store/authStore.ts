import { create } from 'zustand';
import { login as loginApi, register as registerApi, logout as logoutApi, getUserInfo, updateUserInfo } from '../api/auth';
import { mergeCart } from '../api/cart';
import { setHmacSecret } from '../utils/hmac';
import type { UserInfoResponse } from '../types';

interface AuthState {
  token: string | null;
  userId: string | null;
  user: UserInfoResponse | null;
  loading: boolean;
  login: (username: string, password: string, captchaKey: string, captchaCode: string) => Promise<void>;
  register: (username: string, password: string, captchaKey: string, captchaCode: string, phone?: string) => Promise<void>;
  logout: () => Promise<void>;
  fetchUser: () => Promise<void>;
  updateProfile: (data: Partial<UserInfoResponse>) => Promise<void>;
  restore: () => void;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  token: null,
  userId: null,
  user: null,
  loading: false,

  login: async (username, password, captchaKey, captchaCode) => {
    const resp = await loginApi({ username, password, captchaKey, captchaCode });
    const { accessToken, hmacSecret } = resp.data.data;
    localStorage.setItem('token', accessToken);
    setHmacSecret(hmacSecret); // 保存 per-session HMAC 密钥（写请求签名用）
    set({ token: accessToken, userId: null });
    await get().fetchUser();  // fetchUser 会更新 userId
    // 登录后合并游客购物车
    try {
      const guestCart = localStorage.getItem('guestCart');
      if (guestCart) {
        const items = JSON.parse(guestCart);
        if (items.length > 0) {
          await mergeCart(items);
          localStorage.removeItem('guestCart');
        }
      }
    } catch { /* 非关键 */ }
  },

  register: async (username, password, captchaKey, captchaCode, phone) => {
    await registerApi({ username, password, captchaKey, captchaCode, phone });
    await get().login(username, password, captchaKey, captchaCode);
  },

  logout: async () => {
    try { await logoutApi(); } catch { /* 忽略 */ }
    localStorage.removeItem('token');
    localStorage.removeItem('userId');
    setHmacSecret(null);
    set({ token: null, userId: null, user: null });
  },

  fetchUser: async () => {
    set({ loading: true });
    try {
      const resp = await getUserInfo();
      set({ user: resp.data.data, userId: resp.data.data.id, loading: false });
    } catch {
      set({ loading: false });
    }
  },

  updateProfile: async (data) => {
    await updateUserInfo(data);
    await get().fetchUser();
  },

  restore: () => {
    const token = localStorage.getItem('token');
    if (token) {
      set({ token });
      get().fetchUser();
    }
  },
}));
