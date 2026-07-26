import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router-dom'
import { ThemeProvider } from './core/theme'
import { router } from './core/router'
import AuthGate from './modules/auth/AuthGate'
import './shared/global.css'

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <ThemeProvider>
      <AuthGate>
        <RouterProvider router={router} />
      </AuthGate>
    </ThemeProvider>
  </StrictMode>
)

if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register(`${import.meta.env.BASE_URL}sw.js`).catch(() => {
      // 安装能力失败不应阻断小屋本身的使用。
    })
  })
}
