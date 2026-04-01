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
import Sessions from './pages/sessions/Sessions';
import SystemPromptList from './pages/system-prompts/SystemPromptList';
import SystemPromptEditor from './pages/system-prompts/SystemPromptEditor';
import Login from './pages/login/Login';
import { CapabilitiesContext, fetchCapabilities, defaultCapabilities } from './api/capabilities';
import type { Capabilities } from './api/capabilities';
import { AuthContext, getStoredUser, storeUser, clearUser } from './api/auth';
import type { AuthUser } from './api/auth';

export default function App() {
  const [caps, setCaps] = useState<Capabilities>(defaultCapabilities);
  const [loading, setLoading] = useState(true);
  const [user, setUser] = useState<AuthUser | null>(getStoredUser);

  useEffect(() => {
    fetchCapabilities().then(c => {
      setCaps(c);
      setLoading(false);
    });
  }, []);

  const login = useCallback((apiKey: string, userId: string, name: string) => {
    storeUser(apiKey, userId, name);
    setUser({ apiKey, userId, name });
  }, []);

  const logout = useCallback(() => {
    clearUser();
    setUser(null);
  }, []);

  if (loading) return null;

  const defaultPath = caps.chat ? '/chat' : '/';

  return (
    <AuthContext.Provider value={{ user, login, logout }}>
      <CapabilitiesContext.Provider value={caps}>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={user ? <Navigate to={defaultPath} replace /> : <Login />} />
            {!user ? (
              <Route path="*" element={<Navigate to="/login" replace />} />
            ) : (
              <Route element={<Layout />}>
                {caps.chat && <Route path="/chat" element={<Chat />} />}
                {caps.traces && <Route path="/" element={<TraceList />} />}
                {caps.traces && <Route path="/traces/:id" element={<TraceDetail />} />}
                {caps.traces && <Route path="/sessions" element={<Sessions />} />}
                {caps.prompts && <Route path="/prompts" element={<PromptList />} />}
                {caps.prompts && <Route path="/prompts/:id" element={<PromptEditor />} />}
                <Route path="/agents" element={<AgentList />} />
                <Route path="/agents/:id" element={<AgentEditor />} />
                <Route path="/runs/:id" element={<RunDetail />} />
                {caps.systemPrompts && <Route path="/system-prompts" element={<SystemPromptList />} />}
                {caps.systemPrompts && <Route path="/system-prompts/:promptId" element={<SystemPromptEditor />} />}
                {caps.dashboard && <Route path="/dashboard" element={<Dashboard />} />}
                <Route path="*" element={<Navigate to={defaultPath} replace />} />
              </Route>
            )}
          </Routes>
        </BrowserRouter>
      </CapabilitiesContext.Provider>
    </AuthContext.Provider>
  );
}
