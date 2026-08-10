import client from './client';
import type { ApiResponse, CartItemVO, CartAggVO } from '../types';

export const addToCart = (data: { skuId: number; quantity: number }) => client.post<ApiResponse<null>>('/cart/add', data);
export const updateQuantity = (data: { skuId: number; quantity: number }) => client.put<ApiResponse<null>>('/cart/quantity', data);
export const removeFromCart = (skuId: number) => client.delete<ApiResponse<null>>(`/cart/${skuId}`);
export const checkItem = (data: { skuId: number; checked: boolean }) => client.put<ApiResponse<null>>('/cart/check', data);
export const checkAll = (checked: boolean) => client.put<ApiResponse<null>>('/cart/check-all', { checked });
export const getCartList = () => client.get<ApiResponse<CartItemVO[]>>('/cart/list');
export const mergeCart = (items: { skuId: number; quantity: number }[]) => client.post<ApiResponse<null>>('/cart/merge', { items });
export const clearCart = () => client.delete<ApiResponse<null>>('/cart/clear');
export const getCartCount = () => client.get<ApiResponse<number>>('/cart/count');
export const getCartAgg = () => client.get<ApiResponse<CartAggVO>>('/home/cart');
