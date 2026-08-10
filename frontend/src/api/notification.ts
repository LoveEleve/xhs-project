import client from './client';
import type { ApiResponse, NotificationVO, UnreadCountVO, PageData } from '../types';

export const getSseTicket = () => client.post<ApiResponse<{ ticket: string }>>('/notification/sse/ticket');
export const getNotificationList = (params: { type?: number; page?: number; size?: number }) => client.get<ApiResponse<PageData<NotificationVO>>>('/notification/list', { params });
export const getUnreadCount = () => client.get<ApiResponse<UnreadCountVO>>('/notification/unread-count');
export const markRead = (id: number) => client.post<ApiResponse<null>>(`/notification/read/${id}`);
export const markReadByType = (type: number) => client.post<ApiResponse<null>>(`/notification/read-by-type/${type}`);
export const markAllRead = () => client.post<ApiResponse<null>>('/notification/read-all');
