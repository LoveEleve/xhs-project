import { Navigate, Outlet } from 'react-router-dom';

export default function AuthGuard() {
  const token = localStorage.getItem('token');
  if (!token) return <Navigate to="/login" replace />;
  return <Outlet />;
}
