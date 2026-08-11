import client from './client';
import type { ApiResponse, ConversationVO, ImMessageVO } from '../types';

export const getWsTicket = () => client.post<ApiResponse<{ ticket: string }>>('/im/ws/ticket');
export const getImConversations = (page = 1, size = 20) => client.get<ApiResponse<{ records: ConversationVO[]; total: number; page: number }>>('/im/conversations', { params: { page, size } });
export const getImMessages = (peerId: string | number, page = 1, size = 50) => client.get<ApiResponse<{ records: ImMessageVO[]; total: number; page: number }>>(`/im/messages/${peerId}`, { params: { page, size } });
export const markImRead = (peerId: string | number) => client.post<ApiResponse<null>>(`/im/read/${peerId}`);
export const getImUnreadCount = () => client.get<ApiResponse<{ total: number }>>('/im/unread-count');
