import client from './client';
import type { ApiResponse, CouponTemplateVO, UserCouponVO, PageData } from '../types';

export const getCouponTemplate = (id: number) => client.get<ApiResponse<CouponTemplateVO>>(`/coupon/template/${id}`);
export const claimCoupon = (templateId: number) => client.post<ApiResponse<null>>('/coupon/claim', { templateId });
export const getUserCouponList = (status?: number) => client.get<ApiResponse<PageData<UserCouponVO>>>('/coupon/user/list', { params: { status } });
export const getAvailableCoupons = () => client.get<ApiResponse<UserCouponVO[]>>('/coupon/user/available');
