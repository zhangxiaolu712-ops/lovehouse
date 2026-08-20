import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import https from 'node:https'

export default defineConfig({
  plugins: [react()],
  // 相对路径让同一份构建同时适配 GitHub Pages 子目录和 Cloudflare 根域名。
  base: './',
  // 本地真实联调仍请求相对 /api；正式构建不包含该代理。
  server: {
    proxy: {
      '/api/codex': {
        target: {
          protocol: 'https:',
          host: 'tingtunehouse.duckdns.org',
          hostname: 'tingtunehouse.duckdns.org',
          port: 443,
          secureProtocol: 'TLSv1_2_method',
        },
        changeOrigin: true,
        // 当前 Node 开发环境经该 HTTPS 路径的 TLS 1.3 握手会 reset，
        // TLS 1.2 实测 200。仅约束本地代理；正式构建与业务请求不变。
        agent: new https.Agent({ keepAlive: true }),
      },
    },
  },
})
