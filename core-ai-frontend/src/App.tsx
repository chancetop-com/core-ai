import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { useEffect, useState, useCallback } from 'react';
import Layout from './components/Layout';
import TraceList from './pages/traces/TraceList';
import TraceDetail from './pages/traces/TraceDetail';
import PromptList from './pages/prompts/PromptList';
import PromptEditor from './pages/prompts/PromptEditor';
import Dashboard from './pages/dashboard/Dashboard';
import AgentList from './pages/agents/AgentList';
import AgentEditor from './pages/agents/AgentEditor';
import RunDetail from './pages/agents/RunDetail';
import Chat from './pages/chat/Chat';

import SystemPromptList from './pages/system-prompts/SystemPromptList';
import SystemPromptEditor from './pages/system-prompts/SystemPromptEditor';
import Login from './pages/login/Login';
import Register from './pages/login/Register';
import UserManagement from './pages/users/UserManagement';
import SettingsPage from './pages/settings/Settings';
import Scheduler from './pages/scheduler/Scheduler';
import Tasks from './pages/tasks/Tasks';
import Mcp from './pages/mcp/Mcp';
import Tools from './pages/tools/Tools';
import ApiTools from './pages/api-tools/ApiTools';
import ApiToolDetail from './pages/api-tools/ApiToolDetail';
import SkillList from './pages/skills/SkillList';
import SkillEditor from './pages/skills/SkillEditor';
import { CapabilitiesContext, fetchCapabilities } from './api/capabilities';
import type { Capabilities } from './api/capabilities';
import { AuthContext, getStoredUser, storeUser, clearUser } from './api/auth';
import type { AuthUser } from './api/auth';

export default function App() {
  const [caps, setCaps] = useState<Capabilities | null>(null);
  const [loading, setLoading] = useState(true);
  const [user, setUser] = useState<AuthUser | null>(null);

  useEffect(() => {
    fetchCapabilities().then(c => {
      setCaps(c);
      // Skip auth for local modes (cli serve / local server)
      if (!c.authRequired) {
        // Always use local user when auth is not required
        storeUser('local', 'local', 'Local');
        setUser({ apiKey: 'local', userId: 'local', name: 'Local' });
      } else {
        // Auth required - check stored user
        const stored = getStoredUser();
        if (stored) setUser(stored);
      }
      setLoading(false);
    });
  }, []);

  const login = useCallback((apiKey: string, userId: string, name: string, role?: string) => {
    storeUser(apiKey, userId, name, role);
    setUser({ apiKey, userId, name, role });
  }, []);

  const logout = useCallback(() => {
    clearUser();
    setUser(null);
  }, []);

  if (loading || caps === null) return null;

  const authRequired = caps.authRequired;
  const defaultPath = caps.dashboard ? '/dashboard' : caps.chat ? '/chat' : '/agents';

  return (
    <AuthContext.Provider value={{ user, login, logout }}>
      <CapabilitiesContext.Provider value={caps}>
        <BrowserRouter>
          <Routes>
            {authRequired && (
              <>
                <Route path="/login" element={user ? <Navigate to={defaultPath} replace /> : <Login />} />
                <Route path="/register" element={<Register />} />
              </>
            )}
            {!user && authRequired ? (
              <Route path="*" element={<Navigate to="/login" replace />} />
            ) : (
              <Route element={<Layout />}>
                {caps.chat && <Route path="/chat" element={<Chat />} />}
                {caps.traces && <Route path="/" element={<TraceList />} />}
                {caps.traces && <Route path="/traces/:id" element={<TraceDetail />} />}
                {/* Sessions page removed — session list now in Chat sidebar */}
                {caps.prompts && <Route path="/prompts" element={<PromptList />} />}
                {caps.prompts && <Route path="/prompts/:id" element={<PromptEditor />} />}
                <Route path="/agents" element={<AgentList />} />
                <Route path="/agents/:id" element={<AgentEditor />} />
                <Route path="/runs/:id" element={<RunDetail />} />
                {caps.systemPrompts && <Route path="/system-prompts" element={<SystemPromptList />} />}
                {caps.systemPrompts && <Route path="/system-prompts/:promptId" element={<SystemPromptEditor />} />}
                {caps.dashboard && <Route path="/dashboard" element={<Dashboard />} />}
                <Route path="/scheduler" element={<Scheduler />} />
                <Route path="/tasks" element={<Tasks />} />
                <Route path="/mcp" element={<Mcp />} />
                <Route path="/tools" element={<Tools />} />
                <Route path="/api-tools" element={<ApiTools />} />
                <Route path="/api-tools/:id" element={<ApiToolDetail />} />
                <Route path="/skills" element={<SkillList />} />
                <Route path="/skills/:id/edit" element={<SkillEditor />} />
                <Route path="/settings" element={<SettingsPage />}>
                  {user?.role === 'admin' && <Route path="users" element={<UserManagement />} />}
                </Route>
                <Route path="*" element={<Navigate to={defaultPath} replace />} />
              </Route>
            )}
          </Routes>
        </BrowserRouter>
      </CapabilitiesContext.Provider>
    </AuthContext.Provider>
  );
}
