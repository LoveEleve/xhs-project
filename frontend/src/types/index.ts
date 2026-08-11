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
  id: string;
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

export interface UserPublicInfoResponse {
  id: string;
  username: string;
  nickname: string;
  avatar: string;
  gender: number;
  signature: string;
  createdAt: string;
}

export interface AddressVO {
  id: string;
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
  receiverName: string;
  receiverPhone: string;
  province: string;
  city: string;
  district: string;
  detailAddress: string;
  isDefault?: boolean;
}

// --- 笔记 ---
export interface NoteDetailVO {
  id: string;
  userId: string;
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
  noteId: string;
  title: string;
  coverUrl: string;
  noteType: number;
  authorId: string;
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

// 我的笔记列表项
export interface NoteItemVO {
  id: string;
  userId: string;
  title: string;
  coverUrl?: string;
  firstImage?: string;
  noteType: number;
  status: number;
  createdAt: string;
}

// 关注/粉丝
export interface FollowVO {
  userId: string;
  nickname?: string;
  avatar?: string;
  followedAt?: string;
  isFollowBack: boolean;
}

export interface FollowListData {
  total: number;
  list: FollowVO[];
}

export interface NoteDetailAggVO {
  noteId: string;
  title: string;
  content: string;
  images: string[];
  videoUrl: string;
  coverUrl: string;
  noteType: number;
  tags: string[];
  createdAt: string;
  authorId: string;
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
  videoUrl?: string;
  topicIds?: number[];
  tags?: string[];
}

// --- 评论 ---
export interface CommentVO {
  id: string;
  noteId: string;
  userId: string;
  parentId: string;
  replyToId: string;
  content: string;
  likeCount: number;
  createdAt: string;
  children: CommentVO[];
  childCount: number;
}

// --- 商品 ---
export interface SkuVO {
  id: string;
  spuId: string;
  name: string;
  price: number;
  originalPrice: number;
  specs: string;
  status: number;
}

export interface SpuDetailVO {
  id: string;
  name: string;
  categoryId: string;
  categoryName: string;
  brandId: string;
  description: string;
  images: string[];
  status: number;
  skuList: SkuVO[];
  createdAt: string;
  updatedAt: string;
}

export interface SpuItemVO {
  id: string;
  name: string;
  categoryId: string;
  images: string[];
  status: number;
}

export interface SkuWithStockVO {
  skuId: string;
  skuName: string;
  price: number;
  image: string;
  specValues: Record<string, string>;
  availableStock: number;
  hasStock: boolean;
}

export interface ProductDetailAggVO {
  spuId: string;
  name: string;
  description: string;
  images: string[];
  categoryId: string;
  categoryName: string;
  status: number;
  skuList: SkuWithStockVO[];
  collectCount: number;
  viewCount: number;
  relatedNotes: NoteCardVO[];
}

export interface CategoryTreeVO {
  id: string;
  name: string;
  parentId: string;
  level: number;
  sort: number;
  icon?: string;
  children?: CategoryTreeVO[];
}

// --- 购物车 ---
export interface CartItemVO {
  skuId: string;
  spuId: string;
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
  skuId: string;
  spuId: string;
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
  skuId: string;
  skuName: string;
  skuImage: string;
  price: number;
  quantity: number;
  totalAmount: number;
}

export interface OrderVO {
  orderId: string;
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
  skuItems: { skuId: string; quantity: number }[];
  couponId?: string;
  addressId: string;
  /** 幂等键（前端每次下单生成一个，防重复提交） */
  bizIdentifier: string;
}

// --- 优惠券 ---
export interface CouponTemplateVO {
  id: string;
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
  id: string;
  couponId: string;
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
  id: string;
  type: number;
  title: string;
  content: string;
  senderId: string;
  senderName: string;
  senderAvatar: string;
  targetId: string;
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
  peerId: string;
  peerName: string;
  peerAvatar: string;
  lastContent: string;
  lastMsgType: number;
  unreadCount: number;
  updatedAt: string;
}

export interface ImMessageVO {
  id: string;
  senderId: string;
  receiverId: string;
  content: string;
  msgType: number;
  createdAt: string;
}

// --- 用户主页 ---
export interface UserProfileAggVO {
  userId: string;
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
  nextCursor?: string;
  hasMore: boolean;
  unreadCount?: number;
  score?: number;
}

// --- 推荐 ---
export interface RecommendFeedVO {
  noteId: string;
  score: number;
  source?: string;
  category?: string;
  reason?: string;
}

// --- 搜索 ---
export interface SearchResultVO<T> {
  items: T[];
  total: number;
  searchAfter?: string;
  hasMore: boolean;
  took?: number;
}

export interface NoteSearchVO {
  noteId: string;
  userId: string;
  title: string;
  content: string;
  coverImage: string;
  likeCount: number;
  collectCount: number;
  commentCount: number;
  createdAt: string;
  highlightTitle?: string;
  highlightContent?: string;
}

export interface ProductSearchVO {
  spuId: string;
  skuId: string;
  name: string;
  categoryId: string;
  categoryName: string;
  brandName: string;
  price: number;
  image: string;
  sales: number;
  createdAt: string;
  highlightName?: string;
}

export interface HotSearchVO {
  rank: number;
  keyword: string;
  score: number;
  pinned?: boolean;
  tag?: string;
}
