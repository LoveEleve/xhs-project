import client from './client';
import type { ApiResponse, NoteDetailVO, NoteCardVO, PublishNoteRequest, CommentVO, PageData } from '../types';

export const publishNote = (data: PublishNoteRequest) => client.post<ApiResponse<{ id: number }>>('/note/publish', data);
export const saveDraft = (data: PublishNoteRequest) => client.post<ApiResponse<{ id: number }>>('/note/draft', data);
export const updateNote = (id: number, data: PublishNoteRequest) => client.put<ApiResponse<null>>(`/note/${id}`, data);
export const deleteNote = (id: number) => client.delete<ApiResponse<null>>(`/note/${id}`);
export const getNoteDetail = (id: number) => client.get<ApiResponse<NoteDetailVO>>(`/note/detail/${id}`);
export const getUserNotes = (userId: number, page = 1, size = 20) => client.get<ApiResponse<PageData<NoteCardVO>>>(`/note/user/${userId}`, { params: { page, size } });
export const getMyNotes = (page = 1, size = 20) => client.get<ApiResponse<PageData<NoteCardVO>>>('/note/my', { params: { page, size } });
export const publishDraft = (id: number) => client.post<ApiResponse<null>>(`/note/${id}/publish`);
export const uploadImage = (formData: FormData) => client.post<ApiResponse<{ url: string }>>('/note/upload/image', formData, { headers: { 'Content-Type': 'multipart/form-data' } });
export const shareNote = (id: number) => client.post<ApiResponse<{ shareUrl: string }>>(`/note/${id}/share`);
export const getComments = (noteId: number, page = 1, size = 20) => client.get<ApiResponse<PageData<CommentVO>>>(`/comment/page/${noteId}`, { params: { page, size } });
export const getChildComments = (parentId: number) => client.get<ApiResponse<CommentVO[]>>(`/comment/children/${parentId}`);
export const postComment = (data: { noteId: number; content: string; parentId?: number; replyToId?: number }) => client.post<ApiResponse<CommentVO>>('/comment', data);
export const deleteComment = (id: number) => client.delete<ApiResponse<null>>(`/comment/${id}`);
