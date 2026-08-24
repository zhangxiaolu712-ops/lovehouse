import { createHashRouter } from 'react-router'
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
import SearchPage from '../modules/memory/SearchPage'
import PlaceholderPage from '../modules/placeholder/PlaceholderPage'
import ToyPage from '../modules/device/ToyPage'
import ClawdPage from '../modules/space/ClawdPage'
import ChatPage from '../modules/chat/ChatPage'
import ProfilePage from '../modules/profile/ProfilePage'
import BrainPage from '../modules/brain/BrainPage'
import StatsPage from '../modules/stats/StatsPage'
import StatusPage from '../modules/settings/StatusPage'
import LivingroomPage from '../modules/livingroom/LivingroomPage'
import AutobiographyPage from '../modules/autobiography/AutobiographyPage'
import CodexChatV1Page from '../modules/codex-chat-v1/CodexChatV1Page'

// 规划中的抽屉统一用占位页，做好一个换一个
const PLACEHOLDER_PATHS = [
  'moments',
  'interact',
  'all',
  'space/layout',
  'space/games',
  'memory/inbox',
  'ai/api',
  'ai/config',
  'ai/apps',
  'device/band',
  'device/smart',
  'project/updates',
  'settings/backup',
]

export const router = createHashRouter([
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <Home /> },
      { path: 'chat', element: <ChatPage /> },
      { path: 'profile', element: <ProfilePage /> },
      { path: 'diary', element: <DiaryPage /> },
      { path: 'memory', element: <MemoryPage /> },
      { path: 'quotes', element: <QuotesPage /> },
      { path: 'todo', element: <TodoPage /> },
      { path: 'mood', element: <MoodPage /> },
      { path: 'stream', element: <StreamPage /> },
      { path: 'changelog', element: <ChangelogPage /> },
      { path: 'space/theme', element: <ThemePage /> },
      { path: 'space/notes', element: <NotesPage /> },
      { path: 'memory/search', element: <SearchPage /> },
      { path: 'brain', element: <BrainPage /> },
      { path: 'space/clawd', element: <ClawdPage /> },
      { path: 'device/toy', element: <ToyPage /> },
      { path: 'stats', element: <StatsPage /> },
      { path: 'settings', element: <ThemePage /> },
      { path: 'settings/status', element: <StatusPage /> },
      { path: 'livingroom', element: <LivingroomPage /> },
      { path: 'autobiography', element: <AutobiographyPage /> },
      { path: 'codex-chat-v1', element: <CodexChatV1Page /> },
      ...PLACEHOLDER_PATHS.map(path => ({ path, element: <PlaceholderPage /> })),
    ],
  },
])
