import { create } from 'zustand';
import { getUnreadCount, getSseTicket } from '../api/notification';

let esInstance: EventSource | null = null;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let renewalTimer: ReturnType<typeof setInterval> | null = null;
let stopped = true; // 显式断开标记
let reconnectAttempt = 0;

// ticket 有效期 30 秒，提前 5 秒重建连接，保证连接不因 ticket 过期而静默断开
const TICKET_RENEW_MS = 25000;
const RECONNECT_BASE_MS = 3000;

interface NotificationState {
  unreadCount: number;
  sseConnected: boolean;
  fetchUnread: () => Promise<void>;
  connectSSE: () => Promise<void>;
  disconnectSSE: () => void;
  openConnection: () => Promise<void>;
  scheduleReconnect: () => void;
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
    stopped = false;

    // 已有一个健康连接，避免重复建连
    if (esInstance && esInstance.readyState !== EventSource.CLOSED) return;

    await get().openConnection();

    // 定期续期：ticket 30s 过期，每 25s 重建一次，避免静默断开
    if (renewalTimer) clearInterval(renewalTimer);
    renewalTimer = setInterval(() => {
      if (!stopped) get().openConnection();
    }, TICKET_RENEW_MS);
  },

  openConnection: async () => {
    if (esInstance) { esInstance.close(); esInstance = null; }
    try {
      const ticketResp = await getSseTicket();
      const ticket = ticketResp.data.data.ticket;
      esInstance = new EventSource(`/api/notification/sse?ticket=${ticket}`);
      esInstance.onmessage = () => { get().fetchUnread(); };
      esInstance.onopen = () => { set({ sseConnected: true }); reconnectAttempt = 0; };
      esInstance.onerror = () => {
        esInstance?.close();
        esInstance = null;
        set({ sseConnected: false });
        get().scheduleReconnect();
      };
    } catch {
      set({ sseConnected: false });
      get().scheduleReconnect();
    }
  },

  scheduleReconnect: () => {
    if (stopped) return;
    if (reconnectTimer) clearTimeout(reconnectTimer);
    const delay = Math.min(RECONNECT_BASE_MS * Math.pow(2, reconnectAttempt), 30000);
    reconnectAttempt++;
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      if (!stopped) get().openConnection();
    }, delay);
  },

  disconnectSSE: () => {
    stopped = true;
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
    if (renewalTimer) { clearInterval(renewalTimer); renewalTimer = null; }
    if (esInstance) { esInstance.close(); esInstance = null; }
    set({ sseConnected: false });
  },
}));
