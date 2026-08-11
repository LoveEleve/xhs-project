import client from './client';
import type { ApiResponse, SpuDetailVO, SpuItemVO, ProductDetailAggVO, CategoryTreeVO, PageData } from '../types';

export const getSpuDetail = (spuId: string | number) => client.get<ApiResponse<SpuDetailVO>>(`/product/spu/${spuId}`);
export const getSpuList = (params: { pageNum?: number; pageSize?: number; categoryId?: string | number }) => client.get<ApiResponse<PageData<SpuItemVO>>>('/product/spu/list', { params });
export const getCategoryTree = () => client.get<ApiResponse<CategoryTreeVO[]>>('/product/category/tree');
export const getSkuDetail = (skuId: string | number) => client.get<ApiResponse<unknown>>(`/product/sku/${skuId}`);
export const getSkuList = (spuId: string | number) => client.get<ApiResponse<unknown>>(`/product/sku/list/${spuId}`);
export const getProductDetailAgg = (spuId: string | number) => client.get<ApiResponse<ProductDetailAggVO>>(`/home/product/${spuId}`);
