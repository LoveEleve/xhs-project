// ============================================================
// my-xhs 前端 TypeScript 类型定义（与后端 VO 一一对应）
// ============================================================

// --- 通用 ---
export interface ApiResponse<T = unknown> {
  code: number;
  message: string;
  data: T;
}

export interface PageData<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
}

// --- 用户 / 鉴权 ---
export interface LoginRequest {
  username: string;
  password: string;
  captchaKey: string;
  captchaCode: string;
}

export interface RegisterRequest {
  username: string;
  password: string;
  phone?: string;
  captchaKey: string;
  captchaCode: string;
}

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  hmacSecret: string;
}

export interface UserInfoResponse {
  id: number;
  username: string;
  nickname: string;
  avatar: string;
  gender: number;
  birthday: string;
  phone: string;
  email: string;
  signature: string;
  status: number;
  createdAt: string;
  followingCount?: number;
  followerCount?: number;
  likeCount?: number;
  noteCount?: number;
}

export interface AddressVO {
  id: number;
  receiverName: string;
  receiverPhone: string;
  province: string;
  city: string;
  district: string;
  detailAddress: string;
  isDefault: number;
  createdAt: string;
  updatedAt: string;
}

export interface AddressRequest {
  name: string;
  phone: string;
  province: string;
  city: string;
  district: string;
  detail: string;
}

// --- 笔记 ---
export interface NoteDetailVO {
  id: number;
  userId: number;
  title: string;
  content: string;
  images: string[];
  videoUrl: string;
  coverUrl: string;
  topicIds: number[];
  tags: string[];
  status: number;
  statusDesc: string;
  noteType: number;
  createdAt: string;
  updatedAt: string;
}

export interface NoteCardVO {
  noteId: number;
  title: string;
  coverUrl: string;
  noteType: number;
  authorId: number;
  authorNickname: string;
  authorAvatar: string;
  likeCount: number;
  collectCount: number;
  commentCount: number;
  isLiked: boolean;
  isCollected: boolean;
  isFollowed: boolean;
  createdAt: string;
  score: number;
}

export interface NoteDetailAggVO {
  noteId: number;
  title: string;
  content: string;
  images: string[];
  videoUrl: string;
  coverUrl: string;
  noteType: number;
  tags: string[];
  createdAt: string;
  authorId: number;
  authorNickname: string;
  authorAvatar: string;
  likeCount: number;
  collectCount: number;
  commentCount: number;
  isLiked: boolean;
  isCollected: boolean;
  isFollowed: boolean;
  hotComments: Record<string, unknown>[];
}

export interface PublishNoteRequest {
  title: string;
  content: string;
  noteType: number;
  coverUrl?: string;
  images?: string[];
  topicIds?: number[];
  tags?: string[];
}

// --- 评论 ---
export interface CommentVO {
  id: number;
  noteId: number;
  userId: number;
  parentId: number;
  replyToId: number;
  content: string;
  likeCount: number;
  createdAt: string;
  children: CommentVO[];
  childCount: number;
}

// --- 商品 ---
export interface SkuVO {
  id: number;
  spuId: number;
  name: string;
  price: number;
  originalPrice: number;
  specs: string;
  status: number;
}

export interface SpuDetailVO {
  id: number;
  name: string;
  categoryId: number;
  categoryName: string;
  brandId: number;
  description: string;
  images: string[];
  status: number;
  skuList: SkuVO[];
  createdAt: string;
  updatedAt: string;
}

export interface SpuItemVO {
  id: number;
  name: string;
  categoryId: number;
  images: string[];
  status: number;
}

export interface SkuWithStockVO {
  skuId: number;
  skuName: string;
  price: number;
  image: string;
  specValues: Record<string, string>;
  availableStock: number;
  hasStock: boolean;
}

export interface ProductDetailAggVO {
  spuId: number;
  name: string;
  description: string;
  images: string[];
  categoryId: number;
  categoryName: string;
  status: number;
  skuList: SkuWithStockVO[];
  collectCount: number;
  viewCount: number;
  relatedNotes: NoteCardVO[];
}

// --- 购物车 ---
export interface CartItemVO {
  skuId: number;
  spuId: number;
  name: string;
  price: number;
  originalPrice: number;
  quantity: number;
  checked: boolean;
  specs: string;
  image: string;
  valid: boolean;
  invalidReason: string;
  addedAt: number;
}

export interface CartItemAggVO {
  skuId: number;
  spuId: number;
  skuName: string;
  skuImage: string;
  price: number;
  quantity: number;
  checked: boolean;
  totalAmount: number;
  availableStock: number;
  hasStock: boolean;
  onSale: boolean;
}

export interface CartAggVO {
  items: CartItemAggVO[];
  checkedCount: number;
  checkedAmount: number;
  totalCount: number;
  allChecked: boolean;
  availableCouponCount: number;
  availableCoupons: Record<string, unknown>[];
}

// --- 订单 ---
export interface OrderItemVO {
  skuId: number;
  skuName: string;
  skuImage: string;
  price: number;
  quantity: number;
  totalAmount: number;
}

export interface OrderVO {
  orderId: number;
  orderNo: string;
  totalAmount: number;
  payAmount: number;
  discountAmount: number;
  status: number;
  statusDesc: string;
  remark: string;
  addressSnapshot: string;
  createdAt: string;
  paidAt: string;
  items: OrderItemVO[];
}

export interface CreateOrderRequest {
  skuItems: { skuId: number; quantity: number }[];
  couponId?: number;
  addressId: number;
}

// --- 优惠券 ---
export interface CouponTemplateVO {
  id: number;
  name: string;
  type: number;
  discountValue: number;
  minAmount: number;
  totalCount: number;
  remainCount: number;
  perUserLimit: number;
  validStart: string;
  validEnd: string;
  status: number;
}

export interface UserCouponVO {
  id: number;
  couponId: number;
  name: string;
  type: number;
  discountValue: number;
  minAmount: number;
  status: number;
  validEnd: string;
  receivedAt: string;
}

// --- 通知 ---
export interface NotificationVO {
  id: number;
  type: number;
  title: string;
  content: string;
  senderId: number;
  senderName: string;
  senderAvatar: string;
  targetId: number;
  targetType: number;
  isRead: number;
  aggregateCount: number;
  createdAt: string;
}

export interface UnreadCountVO {
  total: number;
  details: Record<number, number>;
}

// --- IM ---
export interface ConversationVO {
  peerId: number;
  peerName: string;
  peerAvatar: string;
  lastContent: string;
  lastMsgType: number;
  unreadCount: number;
  updatedAt: string;
}

export interface ImMessageVO {
  id: number;
  senderId: number;
  receiverId: number;
  content: string;
  msgType: number;
  createdAt: string;
}

// --- 用户主页 ---
export interface UserProfileAggVO {
  userId: number;
  nickname: string;
  avatar: string;
  bio: string;
  followingCount: number;
  followerCount: number;
  likeAndCollectCount: number;
  noteCount: number;
  isFollowing: boolean;
  isFollowBack: boolean;
  isMutual: boolean;
  notes: NoteCardVO[];
}

// --- Feed ---
export interface FeedResponse {
  notes: NoteCardVO[];
  hasMore: boolean;
  score?: number;
}
