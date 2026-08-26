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
import ProfilePage from '../modules/profile/ProfilePage'
import BrainPage from '../modules/brain/BrainPage'
import StatsPage from '../modules/stats/StatsPage'
import StatusPage from '../modules/settings/StatusPage'
import LivingroomPage from '../modules/livingroom/LivingroomPage'
import AutobiographyPage from '../modules/autobiography/AutobiographyPage'
import CodexUnifiedChatPage from '../modules/unified-chat/CodexUnifiedChatPage'
import ClaudeUnifiedChatPage from '../modules/unified-chat/ClaudeUnifiedChatPage'
import GptMemoryPage from '../modules/gpt-memory/GptMemoryPage'
import ProjectChecklistPage from '../modules/project/ProjectChecklistPage'

const PLACEHOLDER_PATHS = ['moments','interact','all','space/layout','space/games','memory/inbox','ai/api','ai/config','ai/apps','device/band','device/smart','project/updates','settings/backup']

export const router=createHashRouter([{path:'/',element:<AppShell/>,children:[
 {index:true,element:<Home/>},
 {path:'codex-chat-v1',element:<CodexUnifiedChatPage/>},
 {path:'claude-chat-v1',element:<ClaudeUnifiedChatPage/>},
 {path:'profile',element:<ProfilePage/>},{path:'diary',element:<DiaryPage/>},{path:'memory',element:<MemoryPage/>},{path:'memory/gpt',element:<GptMemoryPage/>},{path:'quotes',element:<QuotesPage/>},{path:'todo',element:<TodoPage/>},{path:'mood',element:<MoodPage/>},{path:'stream',element:<StreamPage/>},{path:'changelog',element:<ChangelogPage/>},{path:'space/theme',element:<ThemePage/>},{path:'space/notes',element:<NotesPage/>},{path:'memory/search',element:<SearchPage/>},{path:'brain',element:<BrainPage/>},{path:'space/clawd',element:<ClawdPage/>},{path:'device/toy',element:<ToyPage/>},{path:'stats',element:<StatsPage/>},{path:'settings',element:<ThemePage/>},{path:'settings/status',element:<StatusPage/>},{path:'livingroom',element:<LivingroomPage/>},{path:'autobiography',element:<AutobiographyPage/>},{path:'project/checklist',element:<ProjectChecklistPage/>},
 ...PLACEHOLDER_PATHS.map(path=>({path,element:<PlaceholderPage/>}))
]}])
