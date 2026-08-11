import client from './client';
import type { ApiResponse, FeedResponse, NoteDetailAggVO, UserProfileAggVO } from '../types';

export const getFeed = (lastScore?: number, size = 20) => client.get<ApiResponse<FeedResponse>>('/home/feed', { params: { lastScore, size } });
export const getNoteDetail = (noteId: string | number) => client.get<ApiResponse<NoteDetailAggVO>>(`/home/note/${noteId}`);
export const getUserProfile = (targetUserId: string | number) => client.get<ApiResponse<UserProfileAggVO>>(`/home/user/${targetUserId}`);
