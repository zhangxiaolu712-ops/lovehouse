import { createContext, useContext, useState, useCallback } from 'react'

const ThemeContext = createContext()

const THEMES = {
  classic: { id: 'classic', name: '恋爱小屋', icon: '🌸' },
  cozy: { id: 'cozy', name: '浪漫蓝', icon: '💙' },
  vintage: { id: 'vintage', name: '复古手账', icon: '📜' },
  desktop: { id: 'desktop', name: '夜空紫', icon: '🌙' },
}

export function ThemeProvider({ children }) {
  const [themeId, setThemeId] = useState(() => {
    return localStorage.getItem('lovehouse-theme') || 'cozy'
  })

  const switchTheme = useCallback((id) => {
    setThemeId(id)
    localStorage.setItem('lovehouse-theme', id)
  }, [])

  const value = {
    themeId,
    theme: THEMES[themeId] || THEMES.cozy,
    themes: THEMES,
    switchTheme,
  }

  return (
    <ThemeContext.Provider value={value}>
      <div data-theme={themeId}>{children}</div>
    </ThemeContext.Provider>
  )
}

export function useTheme() {
  return useContext(ThemeContext)
}
