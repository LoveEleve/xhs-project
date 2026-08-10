import client from './client';
import type { ApiResponse, SpuDetailVO, SpuItemVO, ProductDetailAggVO, PageData } from '../types';

export const getSpuDetail = (spuId: number) => client.get<ApiResponse<SpuDetailVO>>(`/product/spu/${spuId}`);
export const getSpuList = (params: { page?: number; size?: number; categoryId?: number }) => client.get<ApiResponse<PageData<SpuItemVO>>>('/product/spu/list', { params });
export const getCategoryTree = () => client.get<ApiResponse<unknown>>('/product/category/tree');
export const getSkuDetail = (skuId: number) => client.get<ApiResponse<unknown>>(`/product/sku/${skuId}`);
export const batchGetSku = (ids: number[]) => client.get<ApiResponse<unknown>>('/product/sku/batch', { params: { ids: ids.join(',') } });
export const getSkuList = (spuId: number) => client.get<ApiResponse<unknown>>(`/product/sku/list/${spuId}`);
export const getProductDetailAgg = (spuId: number) => client.get<ApiResponse<ProductDetailAggVO>>(`/home/product/${spuId}`);
