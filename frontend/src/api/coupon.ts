import client from './client';
import type { ApiResponse, CouponTemplateVO, UserCouponVO } from '../types';

export const getCouponTemplate = (id: string | number) => client.get<ApiResponse<CouponTemplateVO>>(`/coupon/template/${id}`);
export const getCouponTemplateList = () => client.get<ApiResponse<CouponTemplateVO[]>>('/coupon/template/list');
export const claimCoupon = (templateId: string | number) => client.post<ApiResponse<null>>('/coupon/claim', { templateId });
export const getUserCouponList = (status?: number) => client.get<ApiResponse<UserCouponVO[]>>('/coupon/user/list', { params: { status } });
export const getAvailableCoupons = () => client.get<ApiResponse<UserCouponVO[]>>('/coupon/user/available');
