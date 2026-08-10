import client from './client';
import type { ApiResponse, ConversationVO, ImMessageVO } from '../types';

export const getWsTicket = () => client.post<ApiResponse<{ ticket: string }>>('/im/ws/ticket');
export const getImConversations = () => client.get<ApiResponse<ConversationVO[]>>('/im/conversations');
export const getImMessages = (peerId: number) => client.get<ApiResponse<ImMessageVO[]>>(`/im/messages/${peerId}`);
export const markImRead = (peerId: number) => client.post<ApiResponse<null>>(`/im/read/${peerId}`);
export const getImUnreadCount = () => client.get<ApiResponse<{ total: number }>>('/im/unread-count');
export const getImOnlineCount = () => client.get<ApiResponse<{ count: number }>>('/im/online-count');
