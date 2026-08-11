import client from './client';
import type { ApiResponse, OrderVO, CreateOrderRequest } from '../types';

export const createOrder = (data: CreateOrderRequest) => client.post<ApiResponse<OrderVO>>('/order/create', data);
export const getOrder = (orderId: string | number) => client.get<ApiResponse<OrderVO>>(`/order/${orderId}`);
export const getOrderList = (params: { status?: number }) => client.get<ApiResponse<OrderVO[]>>('/order/list', { params });
export const cancelOrder = (orderId: string | number) => client.post<ApiResponse<null>>('/order/cancel', null, { params: { orderId } });
export const confirmOrder = (orderId: string | number) => client.post<ApiResponse<null>>('/order/confirm', null, { params: { orderId } });
export const createPayment = (orderId: string | number, payType: number) => client.post<ApiResponse<{ payUrl?: string }>>('/order/pay/create', { orderId, payType });
export const getPayStatus = (orderId: string | number) => client.get<ApiResponse<{ status: number }>>(`/order/pay/status/${orderId}`);
