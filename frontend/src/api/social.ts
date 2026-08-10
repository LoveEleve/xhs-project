import client from './client';
import type { ApiResponse } from '../types';

export const like = (bizId: number, bizType: number) => client.post<ApiResponse<null>>('/social/like', { bizId, bizType });
export const unlike = (bizId: number, bizType: number) => client.delete<ApiResponse<null>>('/social/like', { data: { bizId, bizType } });
export const getLikeStatus = (bizId: number, bizType: number) => client.get<ApiResponse<boolean>>('/social/like/status', { params: { bizId, bizType } });
export const batchLikeStatus = (bizType: number, bizIds: number[]) => client.get<ApiResponse<Record<number, boolean>>>('/social/like/batch-status', { params: { bizType, bizIds: bizIds.join(',') } });
export const favorite = (bizId: number, bizType: number) => client.post<ApiResponse<null>>('/social/favorite', { bizId, bizType });
export const unfavorite = (bizId: number, bizType: number) => client.delete<ApiResponse<null>>('/social/favorite', { data: { bizId, bizType } });
export const getFavoriteStatus = (bizId: number, bizType: number) => client.get<ApiResponse<boolean>>('/social/favorite/status', { params: { bizId, bizType } });
export const getFavoriteList = (page = 1, size = 20) => client.get<ApiResponse<unknown>>('/social/favorite/list', { params: { page, size } });
export const follow = (targetUserId: number) => client.post<ApiResponse<null>>(`/social/follow/${targetUserId}`);
export const unfollow = (targetUserId: number) => client.delete<ApiResponse<null>>(`/social/follow/${targetUserId}`);
export const getFollowing = (userId: number, page = 1, size = 20) => client.get<ApiResponse<unknown>>(`/social/following/${userId}`, { params: { page, size } });
export const getFollowers = (userId: number, page = 1, size = 20) => client.get<ApiResponse<unknown>>(`/social/follower/${userId}`, { params: { page, size } });
export const getCommonFollow = (targetUserId: number) => client.get<ApiResponse<unknown>>(`/social/common/${targetUserId}`);
export const getRelation = (targetUserId: number) => client.get<ApiResponse<unknown>>(`/social/relation/${targetUserId}`);
