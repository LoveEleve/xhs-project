import client from './client';
import type { ApiResponse, NoteCardVO, FeedResponse } from '../types';

export const getRecommendFeed = (size = 20) => client.get<ApiResponse<FeedResponse>>('/recommend/feed', { params: { size } });
export const getSimilarNotes = (noteId: number, size = 20) => client.get<ApiResponse<NoteCardVO[]>>(`/recommend/similar/${noteId}`, { params: { size } });
export const reportBehavior = (data: { targetId: number; targetType: number; action: string }) => client.post<ApiResponse<null>>('/recommend/behavior', data);
