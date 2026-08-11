import client from './client';
import type { ApiResponse, FollowListData } from '../types';

export const like = (bizId: string | number, bizType: number) => client.post<ApiResponse<null>>('/social/like', { bizId, bizType });
export const unlike = (bizId: string | number, bizType: number) => client.delete<ApiResponse<null>>('/social/like', { data: { bizId, bizType } });
export const getLikeStatus = (bizId: string | number, bizType: number) => client.get<ApiResponse<boolean>>('/social/like/status', { params: { bizId, bizType } });
export const batchLikeStatus = (bizType: number, bizIds: (string | number)[]) => client.get<ApiResponse<Record<string, boolean>>>('/social/like/batch-status', { params: { bizType, bizIds: bizIds.join(',') } });
export const favorite = (noteId: string | number) => client.post<ApiResponse<null>>('/social/favorite', { noteId });
export const unfavorite = (noteId: string | number) => client.delete<ApiResponse<null>>('/social/favorite', { data: { noteId } });
export const getFavoriteStatus = (noteId: string | number) => client.get<ApiResponse<boolean>>('/social/favorite/status', { params: { noteId } });
export const getFavoriteList = (page = 1, size = 20) => client.get<ApiResponse<{ total: number; list: number[] }>>('/social/favorite/list', { params: { page, size } });
export const follow = (targetUserId: string | number) => client.post<ApiResponse<null>>(`/social/follow/${targetUserId}`);
export const unfollow = (targetUserId: string | number) => client.delete<ApiResponse<null>>(`/social/follow/${targetUserId}`);
export const getFollowing = (userId: string | number, page = 1, size = 20) => client.get<ApiResponse<FollowListData>>(`/social/following/${userId}`, { params: { page, size } });
export const getFollowers = (userId: string | number, page = 1, size = 20) => client.get<ApiResponse<FollowListData>>(`/social/follower/${userId}`, { params: { page, size } });
export const getCommonFollow = (targetUserId: string | number) => client.get<ApiResponse<number[]>>(`/social/common/${targetUserId}`);
export const getRelation = (targetUserId: string | number) => client.get<ApiResponse<{ isFollowing: boolean; isFollowBack: boolean; isMutual: boolean }>>(`/social/relation/${targetUserId}`);
