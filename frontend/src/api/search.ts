import client from './client';
import type { ApiResponse, NoteCardVO, SpuItemVO, PageData } from '../types';

export const searchNotes = (params: { keyword: string; page?: number; size?: number; sort?: string }) => client.get<ApiResponse<PageData<NoteCardVO>>>('/search/note', { params });
export const searchProducts = (params: { keyword?: string; categoryId?: number; minPrice?: number; maxPrice?: number; sort?: string; page?: number; size?: number }) => client.get<ApiResponse<PageData<SpuItemVO>>>('/search/product', { params });
export const getSuggestions = (prefix: string) => client.get<ApiResponse<string[]>>('/search/suggest', { params: { prefix } });
export const getSearchHistory = () => client.get<ApiResponse<string[]>>('/search/history');
export const deleteSearchHistory = (keyword?: string) => keyword ? client.delete<ApiResponse<null>>(`/search/history/${encodeURIComponent(keyword)}`) : client.delete<ApiResponse<null>>('/search/history');
export const clearSearchHistory = () => client.delete<ApiResponse<null>>('/search/history');
export const getHotSearch = () => client.get<ApiResponse<{ keyword: string; score: number }[]>>('/search/hot');
export const recordHotKeyword = (keyword: string) => client.post<ApiResponse<null>>('/search/hot/record', { keyword });
export const getHotSnapshot = (date: string) => client.get<ApiResponse<{ keyword: string; score: number }[]>>('/search/hot/snapshot', { params: { date } });
