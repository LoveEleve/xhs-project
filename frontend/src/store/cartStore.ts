import { create } from 'zustand';
import { getCartCount } from '../api/cart';

interface CartState {
  count: number;
  fetchCount: () => Promise<void>;
}

export const useCartStore = create<CartState>((set) => ({
  count: 0,
  fetchCount: async () => {
    try {
      const resp = await getCartCount();
      set({ count: resp.data.data.count });
    } catch { /* 静默 */ }
  },
}));
