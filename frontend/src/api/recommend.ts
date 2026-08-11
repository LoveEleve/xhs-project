import client from './client';
import type { ApiResponse, RecommendFeedVO } from '../types';

export const getRecommendFeed = (size = 20) => client.get<ApiResponse<RecommendFeedVO[]>>('/recommend/feed', { params: { size } });
export const getSimilarNotes = (noteId: string | number, size = 20) => client.get<ApiResponse<RecommendFeedVO[]>>(`/recommend/similar/${noteId}`, { params: { size } });
export const reportBehavior = (data: { targetId: string | number; targetType: number; action: string }) => client.post<ApiResponse<null>>('/recommend/behavior', data);
