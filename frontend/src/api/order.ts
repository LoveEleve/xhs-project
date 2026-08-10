import client from './client';
import type { ApiResponse, OrderVO, CreateOrderRequest, PageData } from '../types';

export const createOrder = (data: CreateOrderRequest) => client.post<ApiResponse<{ orderId: number }>>('/order/create', data);
export const getOrder = (orderId: number) => client.get<ApiResponse<OrderVO>>(`/order/${orderId}`);
export const getOrderList = (params: { status?: number; page?: number; size?: number }) => client.get<ApiResponse<PageData<OrderVO>>>('/order/list', { params });
export const cancelOrder = (orderId: number) => client.post<ApiResponse<null>>('/order/cancel', null, { params: { orderId } });
export const confirmOrder = (orderId: number) => client.post<ApiResponse<null>>('/order/confirm', null, { params: { orderId } });
export const createPayment = (orderId: number, payType: number) => client.post<ApiResponse<{ payUrl?: string }>>('/order/pay/create', { orderId, payType });
export const getPayStatus = (orderId: number) => client.get<ApiResponse<{ status: number }>>(`/order/pay/status/${orderId}`);
