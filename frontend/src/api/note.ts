import client from './client';
import type { ApiResponse, NoteDetailVO, NoteItemVO, PublishNoteRequest, CommentVO, PageData } from '../types';

export const publishNote = (data: PublishNoteRequest) => client.post<ApiResponse<{ noteId: string | number }>>('/note/publish', data);
export const saveDraft = (data: PublishNoteRequest) => client.post<ApiResponse<{ noteId: string | number }>>('/note/draft', data);
export const updateNote = (id: string | number, data: PublishNoteRequest) => client.put<ApiResponse<null>>(`/note/${id}`, data);
export const deleteNote = (id: string | number) => client.delete<ApiResponse<null>>(`/note/${id}`);
export const getNoteRawDetail = (id: string | number) => client.get<ApiResponse<NoteDetailVO>>(`/note/detail/${id}`);
export const getUserNotes = (userId: string | number, pageNum = 1, pageSize = 20) => client.get<ApiResponse<PageData<NoteItemVO>>>(`/note/user/${userId}`, { params: { pageNum, pageSize } });
export const getMyNotes = (pageNum = 1, pageSize = 20, status?: number) => client.get<ApiResponse<PageData<NoteItemVO>>>('/note/my', { params: { pageNum, pageSize, status } });
export const publishDraft = (id: string | number) => client.post<ApiResponse<null>>(`/note/${id}/publish`);
export const uploadImage = (formData: FormData) => client.post<ApiResponse<{ url: string }>>('/note/upload/image', formData, { headers: { 'Content-Type': 'multipart/form-data' } });
export const shareNote = (id: string | number) => client.post<ApiResponse<{ shareUrl: string }>>(`/note/${id}/share`);
export const getComments = (noteId: string | number, pageNum = 1, pageSize = 20) => client.get<ApiResponse<PageData<CommentVO>>>(`/comment/page/${noteId}`, { params: { pageNum, pageSize } });
export const getChildComments = (parentId: string | number) => client.get<ApiResponse<CommentVO[]>>(`/comment/children/${parentId}`);
export const postComment = (data: { noteId: string | number; content: string; parentId?: string | number; replyToId?: string | number }) => client.post<ApiResponse<CommentVO>>('/comment', data);
export const deleteComment = (id: string | number) => client.delete<ApiResponse<null>>(`/comment/${id}`);
