import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:19000',
        changeOrigin: true,
        ws: true,  // WebSocket 代理 (IM 聊天)
      },
      '/ai-api': {
        // AI 诊断台（M8-4 薄壳）：直达 my-xhs-ai-app，不经过网关。
        // 与 SPA 路由 /ai 分离，避免硬刷新 /ai 被代理到后端
        target: 'http://localhost:19020',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/ai-api/, ''),
      },
    },
  },
});
