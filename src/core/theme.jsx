import { createContext, useContext, useState, useCallback } from 'react'

const ThemeContext = createContext()
const THEME_STORAGE_KEY = 'lovehouse-theme-v2'

const THEMES = {
  prince: { id: 'prince', name: '星球玫瑰', icon: '✦' },
  classic: { id: 'classic', name: '恋爱小屋', icon: '🌸' },
  cozy: { id: 'cozy', name: '浪漫蓝', icon: '💙' },
  vintage: { id: 'vintage', name: '复古手账', icon: '📜' },
  desktop: { id: 'desktop', name: '夜空紫', icon: '🌙' },
}

export function ThemeProvider({ children }) {
  const [themeId, setThemeId] = useState(() => {
    return localStorage.getItem(THEME_STORAGE_KEY) || 'prince'
  })

  const switchTheme = useCallback((id) => {
    setThemeId(id)
    localStorage.setItem(THEME_STORAGE_KEY, id)
  }, [])

  const value = {
    themeId,
    theme: THEMES[themeId] || THEMES.prince,
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
