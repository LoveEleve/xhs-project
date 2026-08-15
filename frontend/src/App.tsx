import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { ConfigProvider, App as AntApp } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { useEffect } from 'react';
import AppLayout from './components/AppLayout';
import AuthGuard from './components/AuthGuard';
import NotFoundPage from './components/NotFoundPage';
import { useAuthStore } from './store/authStore';

// 页面导入
import LoginPage from './pages/login';
import RegisterPage from './pages/register';
import FeedPage from './pages/feed';
import NoteDetailPage from './pages/note/NoteDetailPage';
import NotePublishPage from './pages/note/NotePublishPage';
import ProductListPage from './pages/product/ProductListPage';
import ProductDetailPage from './pages/product/ProductDetailPage';
import CartPage from './pages/cart';
import OrderCreatePage from './pages/order/OrderCreatePage';
import OrderListPage from './pages/order/OrderListPage';
import OrderDetailPage from './pages/order/OrderDetailPage';
import MePage from './pages/user/MePage';
import AddressPage from './pages/user/AddressPage';
import UserProfilePage from './pages/user/UserProfilePage';
import MyNotesPage from './pages/user/MyNotesPage';
import FavoritesPage from './pages/user/FavoritesPage';
import BlockPage from './pages/user/BlockPage';
import PasswordPage from './pages/user/PasswordPage';
import FollowingPage from './pages/user/FollowingPage';
import FollowerPage from './pages/user/FollowerPage';
import CouponPage from './pages/coupon';
import CouponCenterPage from './pages/coupon/CouponCenterPage';
import SearchPage from './pages/search';
import NotificationPage from './pages/notification';
import ImConversationsPage from './pages/im/ImConversationsPage';
import ImChatPage from './pages/im/ImChatPage';
import AgentConsolePage from './pages/ai/AgentConsolePage';

function AuthRestore() {
  const restore = useAuthStore((s) => s.restore);
  useEffect(() => { restore(); }, []);
  return null;
}

export default function App() {
  return (
    <ConfigProvider locale={zhCN} theme={{ token: { colorPrimary: '#ff4d4f' } }}>
      <AntApp>
        <BrowserRouter>
          <AuthRestore />
          <Routes>
            <Route path="/" element={<Navigate to="/feed" replace />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage />} />

            <Route element={<AppLayout />}>
              {/* 无需鉴权 */}
              <Route path="/feed" element={<FeedPage />} />
              <Route path="/note/:id" element={<NoteDetailPage />} />
              <Route path="/search" element={<SearchPage />} />
              <Route path="/product" element={<ProductListPage />} />
              <Route path="/product/:spuId" element={<ProductDetailPage />} />
              <Route path="/ai" element={<AgentConsolePage />} />
              <Route path="/user/:userId" element={<UserProfilePage />} />

              {/* 需要鉴权 */}
              <Route element={<AuthGuard />}>
                <Route path="/note/publish" element={<NotePublishPage />} />
                <Route path="/cart" element={<CartPage />} />
                <Route path="/order/create" element={<OrderCreatePage />} />
                <Route path="/order/list" element={<OrderListPage />} />
                <Route path="/order/:id" element={<OrderDetailPage />} />
                <Route path="/notification" element={<NotificationPage />} />
                <Route path="/im" element={<ImConversationsPage />} />
                <Route path="/im/:peerId" element={<ImChatPage />} />

                <Route path="/me" element={<MePage />} />
                <Route path="/me/address" element={<AddressPage />} />
                <Route path="/me/coupon" element={<CouponPage />} />
                <Route path="/me/notes" element={<MyNotesPage />} />
                <Route path="/me/favorites" element={<FavoritesPage />} />
                <Route path="/me/block" element={<BlockPage />} />
                <Route path="/me/password" element={<PasswordPage />} />
                <Route path="/coupon" element={<CouponCenterPage />} />
                <Route path="/user/:userId/following" element={<FollowingPage />} />
                <Route path="/user/:userId/follower" element={<FollowerPage />} />
              </Route>
              <Route path="*" element={<NotFoundPage />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </AntApp>
    </ConfigProvider>
  );
}
