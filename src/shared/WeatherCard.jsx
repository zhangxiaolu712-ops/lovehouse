import { useState, useEffect } from 'react'

// 广州坐标兜底（定位被拒绝或超时时使用）
const FALLBACK = { lat: 23.13, lon: 113.26, label: '广州' }

const WEATHER_MAP = [
  { codes: [0], icon: '☀️', label: '晴' },
  { codes: [1, 2], icon: '🌤', label: '多云' },
  { codes: [3], icon: '☁️', label: '阴' },
  { codes: [45, 48], icon: '🌫', label: '雾' },
  { codes: [51, 53, 55, 56, 57], icon: '🌦', label: '毛毛雨' },
  { codes: [61, 63, 65, 66, 67], icon: '🌧', label: '下雨' },
  { codes: [71, 73, 75, 77, 85, 86], icon: '❄️', label: '下雪' },
  { codes: [80, 81, 82], icon: '🌦', label: '阵雨' },
  { codes: [95, 96, 99], icon: '⛈', label: '雷雨' },
]

function describeWeather(code) {
  const found = WEATHER_MAP.find(w => w.codes.includes(code))
  return found || { icon: '🌈', label: '' }
}

function getGreeting() {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 9) return '早上好'
  if (h < 12) return '上午好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  if (h < 21) return '傍晚好'
  return '晚上好'
}

function getPosition() {
  return new Promise(resolve => {
    if (!navigator.geolocation) return resolve(null)
    navigator.geolocation.getCurrentPosition(
      pos => resolve({ lat: pos.coords.latitude, lon: pos.coords.longitude, label: '我的位置' }),
      () => resolve(null),
      { timeout: 5000, maximumAge: 30 * 60 * 1000 },
    )
  })
}

export default function WeatherCard() {
  const [weather, setWeather] = useState(null)
  const [place, setPlace] = useState(null)

  useEffect(() => {
    let cancelled = false
    async function loadWeather() {
      const pos = (await getPosition()) || FALLBACK
      if (cancelled) return
      setPlace(pos.label)
      try {
        const url = `https://api.open-meteo.com/v1/forecast?latitude=${pos.lat}&longitude=${pos.lon}&current=temperature_2m,weather_code&timezone=auto`
        const resp = await fetch(url)
        const data = await resp.json()
        if (!cancelled && data.current) {
          setWeather({ temp: Math.round(data.current.temperature_2m), code: data.current.weather_code })
        }
      } catch {
        // 拿不到天气就只显示日历，不报错打扰
      }
    }
    loadWeather()
    return () => { cancelled = true }
  }, [])

  const today = new Date()
  const dateStr = `${today.getMonth() + 1}月${today.getDate()}日`
  const weekday = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'][today.getDay()]
  const w = weather ? describeWeather(weather.code) : null

  return (
    <div className="info-card weather-card">
      <div className="info-label">📅 今日 {place && weather && <span className="weather-place">📍{place}</span>}</div>
      <div className="info-content">
        <div className="weather-row">
          <div>
            <div>{dateStr} {weekday}</div>
            <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 4 }}>{getGreeting()}</div>
          </div>
          {weather && w && (
            <div className="weather-now">
              <span className="weather-icon">{w.icon}</span>
              <span className="weather-temp">{weather.temp}°</span>
              <span className="weather-label">{w.label}</span>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
