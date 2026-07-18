import { createContext, useContext, useState, useCallback } from 'react'

const ThemeContext = createContext()

const THEMES = {
  cozy: { id: 'cozy', name: '温馨小屋', icon: '🏠' },
  minimal: { id: 'minimal', name: '极简模式', icon: '✨' },
  desktop: { id: 'desktop', name: '桌面空间', icon: '🖥️' },
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
