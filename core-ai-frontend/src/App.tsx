import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { lazy, Suspense, useEffect, useState, useCallback } from 'react';
import Layout from './components/Layout';
import ErrorBoundary from './components/ErrorBoundary';
import { CapabilitiesContext, fetchCapabilities } from './api/capabilities';
import type { Capabilities } from './api/capabilities';
import { AuthContext, getStoredUser, storeUser, clearUser } from './api/auth';
import type { AuthUser } from './api/auth';

const TraceList = lazy(() => import('./pages/traces/TraceList'));
const TraceDetail = lazy(() => import('./pages/traces/TraceDetail'));
const PromptList = lazy(() => import('./pages/prompts/PromptList'));
const PromptEditor = lazy(() => import('./pages/prompts/PromptEditor'));
const Dashboard = lazy(() => import('./pages/dashboard/Dashboard'));
const AgentList = lazy(() => import('./pages/agents/AgentList'));
const AgentEditor = lazy(() => import('./pages/agents/AgentEditor'));
const RunDetail = lazy(() => import('./pages/agents/RunDetail'));
const Chat = lazy(() => import('./pages/chat/Chat'));
const SystemPromptList = lazy(() => import('./pages/system-prompts/SystemPromptList'));
const SystemPromptEditor = lazy(() => import('./pages/system-prompts/SystemPromptEditor'));
const Login = lazy(() => import('./pages/login/Login'));
const Register = lazy(() => import('./pages/login/Register'));
const UserManagement = lazy(() => import('./pages/users/UserManagement'));
const SettingsPage = lazy(() => import('./pages/settings/Settings'));
const Scheduler = lazy(() => import('./pages/scheduler/Scheduler'));
const Tasks = lazy(() => import('./pages/tasks/Tasks'));
const Mcp = lazy(() => import('./pages/mcp/Mcp'));
const McpDetail = lazy(() => import('./pages/mcp/McpDetail'));
const BuiltinTools = lazy(() => import('./pages/tools/BuiltinTools'));
const ApiTools = lazy(() => import('./pages/api-tools/ApiTools'));
const ApiToolDetail = lazy(() => import('./pages/api-tools/ApiToolDetail'));
const SkillList = lazy(() => import('./pages/skills/SkillList'));
const SkillEditor = lazy(() => import('./pages/skills/SkillEditor'));
const DatasetList = lazy(() => import('./pages/datasets/DatasetList'));
const DatasetEditor = lazy(() => import('./pages/datasets/DatasetEditor'));
const DatasetRecords = lazy(() => import('./pages/datasets/DatasetRecords'));
const TriggersWebhook = lazy(() => import('./pages/triggers/TriggersWebhook'));
const ForYou = lazy(() => import('./pages/for-you/ForYou'));

function PageFallback() {
  return (
    <div className="flex h-full items-center justify-center text-sm" style={{ color: 'var(--color-text-secondary)' }}>
      Loading...
    </div>
  );
}

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
  const defaultPath = '/for-you';

  return (
    <AuthContext.Provider value={{ user, login, logout }}>
      <CapabilitiesContext.Provider value={caps}>
        <BrowserRouter>
          <ErrorBoundary>
            <Suspense fallback={<PageFallback />}>
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
                    <Route path="/for-you" element={<ForYou />} />
                    {caps.chat && <Route path="/chat" element={<Chat />} />}
                    {caps.traces && <Route path="/traces" element={<TraceList />} />}
                    {caps.traces && <Route path="/traces/:id" element={<TraceDetail />} />}
                    {/* Sessions page removed - session list now in Chat sidebar */}
                    {caps.prompts && <Route path="/prompts" element={<PromptList />} />}
                    {caps.prompts && <Route path="/prompts/:id" element={<PromptEditor />} />}
                    <Route path="/agents" element={<AgentList />} />
                    <Route path="/agents/:id" element={<AgentEditor />} />
                    <Route path="/runs/:id" element={<RunDetail />} />
                    {caps.systemPrompts && <Route path="/system-prompts" element={<SystemPromptList />} />}
                    {caps.systemPrompts && <Route path="/system-prompts/:promptId" element={<SystemPromptEditor />} />}
                    <Route path="/triggers/webhook" element={<TriggersWebhook />} />
                    <Route path="/triggers/schedule" element={<Scheduler />} />
                    <Route path="/triggers" element={<Navigate to="/triggers/webhook" replace />} />
                    {/* Backward compat: old /scheduler redirects to /triggers/schedule */}
                    <Route path="/scheduler" element={<Navigate to="/triggers/schedule" replace />} />
                    <Route path="/tasks" element={<Tasks />} />
                    <Route path="/mcp" element={<Mcp />} />
                    <Route path="/mcp/:id" element={<McpDetail />} />
                    <Route path="/tools" element={<Navigate to="/tools/builtin" replace />} />
                    <Route path="/tools/builtin" element={<BuiltinTools />} />
                    <Route path="/api-tools" element={<ApiTools />} />
                    <Route path="/api-tools/:id" element={<ApiToolDetail />} />
                    <Route path="/skills" element={<SkillList />} />
                    <Route path="/skills/:id/edit" element={<SkillEditor />} />
                    <Route path="/datasets" element={<DatasetList />} />
                    <Route path="/datasets/:id" element={<DatasetEditor />} />
                    <Route path="/datasets/:id/records" element={<DatasetRecords />} />
                    <Route path="/settings" element={<SettingsPage />}>
                      {user?.role === 'admin' && <Route index element={<Dashboard />} />}
                      {user?.role === 'admin' && <Route path="users" element={<UserManagement />} />}
                    </Route>
                    <Route path="*" element={<Navigate to={defaultPath} replace />} />
                  </Route>
                )}
              </Routes>
            </Suspense>
          </ErrorBoundary>
        </BrowserRouter>
      </CapabilitiesContext.Provider>
    </AuthContext.Provider>
  );
}
