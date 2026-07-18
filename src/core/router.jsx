import { createBrowserRouter } from 'react-router-dom'
import AppShell from '../shared/AppShell'
import Home from '../shared/Home'
import DiaryPage from '../modules/diary/DiaryPage'
import MemoryPage from '../modules/memory/MemoryPage'
import QuotesPage from '../modules/quotes/QuotesPage'
import TodoPage from '../modules/todo/TodoPage'
import MoodPage from '../modules/mood/MoodPage'
import StreamPage from '../modules/stream/StreamPage'

export const router = createBrowserRouter([
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
    ],
  },
])
