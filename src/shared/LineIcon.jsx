const ICON_PATHS = {
  home: 'M3 10.5 12 3l9 7.5V21h-6v-6H9v6H3z',
  star: 'M12 3l1.6 5.4L19 10l-5.4 1.6L12 17l-1.6-5.4L5 10l5.4-1.6z',
  heart: 'M12 20S4 15.5 4 9.5A4.5 4.5 0 0 1 12 6a4.5 4.5 0 0 1 8 3.5C20 15.5 12 20 12 20z',
  drop: 'M12 3s6 7 6 11a6 6 0 0 1-12 0c0-4 6-11 6-11z',
  moon: 'M19 15.5A8 8 0 0 1 8.5 5 8 8 0 1 0 19 15.5z',
  layout: 'M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h6v6h-6z',
  theme: 'M12 3a9 9 0 1 0 0 18c1.5 0 2-1 1.4-2.1-.6-1.1.2-2.4 1.5-2.4H17a4 4 0 0 0 0-8h-1 M7.5 9h.01 M10 6.5h.01 M14 6.5h.01',
  mail: 'M3 5h18v14H3z M3 6l9 7 9-7',
  paw: 'M8.2 10.3c-1.8-1.3-3.9.3-3.4 2.4.4 1.6 2 2.1 3.3 1.2 M15.8 10.3c1.8-1.3 3.9.3 3.4 2.4-.4 1.6-2 2.1-3.3 1.2 M8.2 8C6 8 5.5 4.5 7.7 4.1 9.6 3.8 10 6.8 8.2 8z M15.8 8c2.2 0 2.7-3.5.5-3.9-1.9-.3-2.3 2.7-.5 3.9z M12 10c3 0 5.2 3.4 3.8 6-1.1 2.1-2.7 1.4-3.8.8-1.1.6-2.7 1.3-3.8-.8-1.4-2.6.8-6 3.8-6z',
  check: 'M4 12l5 5L20 6',
  game: 'M7 9h10a4 4 0 0 1 3.8 5.2l-1 3.1a2 2 0 0 1-3.2 1l-2.1-1.8h-5l-2.1 1.8a2 2 0 0 1-3.2-1l-1-3.1A4 4 0 0 1 7 9z M8 11v4 M6 13h4 M16.5 12h.01 M18 14h.01',
  memory: 'M12 3l7 7-7 11L5 10z M5 10h14',
  lock: 'M6 10h12v11H6z M8.5 10V7.5a3.5 3.5 0 0 1 7 0V10 M12 14v3',
  book: 'M4 5a4 4 0 0 1 4-2h4v17H8a4 4 0 0 0-4 2z M20 5a4 4 0 0 0-4-2h-4v17h4a4 4 0 0 1 4 2z',
  message: 'M4 4h16v13H8l-4 4z',
  quote: 'M6 8h5v5H7a4 4 0 0 1-4 4 M15 8h5v5h-4a4 4 0 0 1-4 4',
  inbox: 'M4 4h16v16H4z M4 14h4l2 3h4l2-3h4',
  clock: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M12 7v5l3 2',
  calendar: 'M5 4h14v17H5z M8 2v4 M16 2v4 M5 9h14 M8 13h.01 M12 13h.01 M16 13h.01 M8 17h.01 M12 17h.01',
  pin: 'M20 10c0 5-8 11-8 11S4 15 4 10a8 8 0 1 1 16 0z M12 7a3 3 0 1 0 0 6 3 3 0 0 0 0-6z',
  search: 'M10.5 4a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13z M15.5 15.5 21 21',
  link: 'M10 14l4-4 M8.5 17.5l-1 1a3.5 3.5 0 0 1-5-5l4-4a3.5 3.5 0 0 1 5 0 M15.5 6.5l1-1a3.5 3.5 0 0 1 5 5l-4 4a3.5 3.5 0 0 1-5 0',
  shield: 'M12 3l8 3v6c0 5-3.4 8-8 9-4.6-1-8-4-8-9V6z M8.5 12l2.2 2.2 4.8-5',
  apps: 'M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h6v6h-6z',
  device: 'M8 3v5 M16 3v5 M6 8h12v3a6 6 0 0 1-6 6v4 M9 21h6',
  toy: 'M8 8a4 4 0 1 1 8 0v2a6 6 0 1 1-8 0z M8 7 5 5 M16 7l3-2 M9.5 13h.01 M14.5 13h.01',
  watch: 'M9 3h6l1 4H8z M8 7h8v10H8z M8 17h8l-1 4H9z',
  bulb: 'M9 17h6 M10 21h4 M12 3a7 7 0 0 0-4 12c.8.6 1 1.1 1 2h6c0-.9.2-1.4 1-2a7 7 0 0 0-4-12z',
  project: 'M8 4h8 M9 3h6v3H9z M6 5H4v16h16V5h-2 M8 11h8 M8 15h8',
  history: 'M4 12a8 8 0 1 0 2.3-5.7L4 8 M4 4v4h4 M12 8v5l3 2',
  edit: 'M4 20l4.5-1 11-11a2.1 2.1 0 0 0-3-3l-11 11z M14.5 6.5l3 3',
  settings: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z M19 13.5l2 1.2-2 3.4-2.1-1a8 8 0 0 1-2.2 1.3l-.2 2.3h-4l-.2-2.3a8 8 0 0 1-2.2-1.3l-2.1 1-2-3.4 2-1.2a8 8 0 0 1 0-2.6l-2-1.2 2-3.4 2.1 1A8 8 0 0 1 10.3 6l.2-2.3h4l.2 2.3a8 8 0 0 1 2.2 1.3l2.1-1 2 3.4-2 1.2a8 8 0 0 1 0 2.6z',
  backup: 'M12 4v11 M8 11l4 4 4-4 M5 19h14 M5 5h4 M15 5h4',
  mood: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M8.5 10h.01 M15.5 10h.01 M8 14a5 5 0 0 0 8 0',
  stream: 'M4 7h16 M4 12h12 M4 17h8',
  plus: 'M12 5v14 M5 12h14',
  more: 'M5 12h.01 M12 12h.01 M19 12h.01',
  workbench: 'M4 5h16v14H4z M4 9h16 M8 9v10 M12 12h4 M12 15h4',
  chat: 'M4 4h16v12H8l-4 4z M8 9h8 M8 12h5',
  interact: 'M17 8a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M7 22a3 3 0 1 0 0-6 3 3 0 0 0 0 6z M17 8c0 4-3 5-6.5 7.5 M7 16c0-4 3-5 6.5-7.5',
  grid: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  back: 'M15 19l-7-7 7-7',
  send: 'M12 19V5 M5 12l7-7 7 7',
  close: 'M18 6L6 18 M6 6l12 12',
  image: 'M4 4h16v16H4z M4 16l4-4 3 3 4-4 5 5 M14.5 9.5a1.5 1.5 0 1 1-3 0 1.5 1.5 0 0 1 3 0z',
  file: 'M14 3H6v18h12V7z M14 3v4h4 M8 13h8 M8 17h5',
}

export default function LineIcon({ name, size = 20, className = '' }) {
  const path = ICON_PATHS[name] || ICON_PATHS.home
  return (
    <svg
      className={`line-icon ${className}`.trim()}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.55"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <path d={path} vectorEffect="non-scaling-stroke" />
    </svg>
  )
}
