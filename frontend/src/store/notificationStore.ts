import { create } from 'zustand';
import { getUnreadCount, getSseTicket } from '../api/notification';

let esInstance: EventSource | null = null;

interface NotificationState {
  unreadCount: number;
  sseConnected: boolean;
  fetchUnread: () => Promise<void>;
  connectSSE: () => Promise<void>;
  disconnectSSE: () => void;
}

export const useNotificationStore = create<NotificationState>((set, get) => ({
  unreadCount: 0,
  sseConnected: false,

  fetchUnread: async () => {
    try {
      const resp = await getUnreadCount();
      set({ unreadCount: resp.data.data.total });
    } catch { /* 静默 */ }
  },

  connectSSE: async () => {
    const token = localStorage.getItem('token');
    if (!token) return;
    try {
      const ticketResp = await getSseTicket();
      const ticket = ticketResp.data.data.ticket;
      esInstance = new EventSource(`/api/notification/sse?ticket=${ticket}`);
      esInstance.onmessage = () => {
        get().fetchUnread();
      };
      esInstance.onerror = () => {
        esInstance?.close();
        set({ sseConnected: false });
      };
      esInstance.onopen = () => {
        set({ sseConnected: true });
      };
    } catch { /* 静默 */ }
  },

  disconnectSSE: () => {
    esInstance?.close();
    esInstance = null;
    set({ sseConnected: false });
  },
}));
