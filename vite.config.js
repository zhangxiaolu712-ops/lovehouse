import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  // 相对路径让同一份构建同时适配 GitHub Pages 子目录和 Cloudflare 根域名。
  base: './',
})
