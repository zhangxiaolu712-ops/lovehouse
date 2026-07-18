import { createHashRouter } from 'react-router-dom'
import AppShell from '../shared/AppShell'
import Home from '../shared/Home'
import DiaryPage from '../modules/diary/DiaryPage'
import MemoryPage from '../modules/memory/MemoryPage'
import QuotesPage from '../modules/quotes/QuotesPage'
import TodoPage from '../modules/todo/TodoPage'
import MoodPage from '../modules/mood/MoodPage'
import StreamPage from '../modules/stream/StreamPage'
import ChangelogPage from '../modules/changelog/ChangelogPage'
import ThemePage from '../modules/space/ThemePage'
import NotesPage from '../modules/notes/NotesPage'
import PlaceholderPage from '../modules/placeholder/PlaceholderPage'

// 规划中的抽屉统一用占位页，做好一个换一个
const PLACEHOLDER_PATHS = [
  'space/layout',
  'memory/tags',
  'memory/search',
  'ai/config',
  'ai/tasks',
  'ai/app',
  'ai/api',
  'device/toy',
  'device/band',
  'device/smart',
  'project/updates',
  'project/handover',
  'settings',
]

export const router = createHashRouter([
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <Home /> },
      { path: 'diary', element: <DiaryPage /> },
      { path: 'memory', element: <MemoryPage /> },
      { path: 'quotes', element: <QuotesPage /> },
      { path: 'todo', element: <TodoPage /> },
      { path: 'mood', element: <MoodPage /> },
      { path: 'stream', element: <StreamPage /> },
      { path: 'changelog', element: <ChangelogPage /> },
      { path: 'space/theme', element: <ThemePage /> },
      { path: 'space/notes', element: <NotesPage /> },
      ...PLACEHOLDER_PATHS.map(path => ({ path, element: <PlaceholderPage /> })),
    ],
  },
])
