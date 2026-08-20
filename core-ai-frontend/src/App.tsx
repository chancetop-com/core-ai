import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { lazy, Suspense, useEffect, useState, useCallback } from 'react';
import Layout from './components/Layout';
import ErrorBoundary from './components/ErrorBoundary';
import RequirePermission from './components/RequirePermission';
import { CapabilitiesContext, fetchCapabilities } from './api/capabilities';
import type { Capabilities } from './api/capabilities';
import { AuthContext, getStoredUser, storeUser, clearUser } from './api/auth';
import type { AuthUser } from './api/auth';
import { authApi } from './api/client';

const TraceList = lazy(() => import('./pages/traces/TraceList'));
const TraceDetail = lazy(() => import('./pages/traces/TraceDetail'));
const PromptList = lazy(() => import('./pages/prompts/PromptList'));
const PromptEditor = lazy(() => import('./pages/prompts/PromptEditor'));
const Dashboard = lazy(() => import('./pages/dashboard/Dashboard'));
const AgentList = lazy(() => import('./pages/agents/AgentList'));
const AgentEditor = lazy(() => import('./pages/agents/AgentEditor'));
const WorkflowList = lazy(() => import('./pages/workflows/WorkflowList'));
const WorkflowExplore = lazy(() => import('./pages/workflows/WorkflowExplore'));
const WorkflowEditor = lazy(() => import('./pages/workflows/WorkflowEditor'));
const WorkflowRuns = lazy(() => import('./pages/workflows/WorkflowRuns'));
const AgentMemory = lazy(() => import('./pages/agents/AgentMemory'));
const RunDetail = lazy(() => import('./pages/agents/RunDetail'));
const Chat = lazy(() => import('./pages/chat/Chat'));
const SystemPromptList = lazy(() => import('./pages/system-prompts/SystemPromptList'));
const SystemPromptEditor = lazy(() => import('./pages/system-prompts/SystemPromptEditor'));
const Login = lazy(() => import('./pages/login/Login'));
const Register = lazy(() => import('./pages/login/Register'));
const Authorize = lazy(() => import('./pages/login/Authorize'));
const UserManagement = lazy(() => import('./pages/users/UserManagement'));
const SettingsPage = lazy(() => import('./pages/settings/Settings'));
const ApiKeysPage = lazy(() => import('./pages/settings/ApiKeys'));
const GatewayProvidersPage = lazy(() => import('./pages/settings/GatewayProviders'));
const SystemSettingsPage = lazy(() => import('./pages/settings/SystemSettings'));
const Scheduler = lazy(() => import('./pages/scheduler/Scheduler'));
const Tasks = lazy(() => import('./pages/tasks/Tasks'));
const Mcp = lazy(() => import('./pages/mcp/Mcp'));
const McpDetail = lazy(() => import('./pages/mcp/McpDetail'));
const BuiltinTools = lazy(() => import('./pages/tools/BuiltinTools'));
const ApiTools = lazy(() => import('./pages/api-tools/ApiTools'));
const ApiToolDetail = lazy(() => import('./pages/api-tools/ApiToolDetail'));
const SkillList = lazy(() => import('./pages/skills/SkillList'));
const SkillEditor = lazy(() => import('./pages/skills/SkillEditor'));
const MarketplaceRepoDetail = lazy(() => import('./pages/skills/MarketplaceRepoDetail'));
const DatasetList = lazy(() => import('./pages/datasets/DatasetList'));
const DatasetEditor = lazy(() => import('./pages/datasets/DatasetEditor'));
const DatasetRecords = lazy(() => import('./pages/datasets/DatasetRecords'));
const ProjectList = lazy(() => import('./pages/projects/ProjectList'));
const ProjectDetail = lazy(() => import('./pages/projects/ProjectDetail'));
const SubjectDetail = lazy(() => import('./pages/projects/SubjectDetail'));
const PlaybookPage = lazy(() => import('./pages/projects/PlaybookPage'));
const TriggersWebhook = lazy(() => import('./pages/triggers/TriggersWebhook'));
const Channels = lazy(() => import('./pages/channels/Channels'));
const CostAlerts = lazy(() => import('./pages/cost-alerts/CostAlerts'));
const OpenClaw = lazy(() => import('./pages/openclaw/OpenClaw'));
const ForYou = lazy(() => import('./pages/for-you/ForYou'));
const ArtifactList = lazy(() => import('./pages/artifacts/ArtifactList'));
const SharedArtifact = lazy(() => import('./pages/shared/SharedArtifact'));
const NotificationsPage = lazy(() => import('./pages/notifications/Notifications'));
const MemoryExperiment = lazy(() => import('./pages/experiments/MemoryExperimentList'));
const MemoryExperimentRunDetail = lazy(() => import('./pages/experiments/MemoryExperimentRunDetail'));
const MemoryExperimentConfigDetail = lazy(() => import('./pages/experiments/MemoryExperimentConfigDetail'));

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
        storeUser('local', 'local', 'Local', undefined, ['*']);
        setUser({ apiKey: 'local', userId: 'local', name: 'Local', permissions: ['*'] });
      } else {
        // Auth required - check stored user
        const stored = getStoredUser();
        if (stored) {
          setUser(stored);
          // Refresh profile/permissions from the session-backed /api/auth/me
          authApi.me().then(profile => {
            if (!profile) return;
            const refreshed: AuthUser = {
              apiKey: stored.apiKey,
              userId: profile.user_id || stored.userId,
              name: profile.name || stored.name,
              role: profile.role || stored.role,
              permissions: profile.permissions || stored.permissions,
            };
            storeUser(refreshed.apiKey, refreshed.userId, refreshed.name, refreshed.role, refreshed.permissions);
            setUser(refreshed);
          }).catch(() => { /* keep stored user on network errors */ });
        }
      }
      setLoading(false);
    });
  }, []);

  const login = useCallback((apiKey: string, userId: string, name: string, role?: string, permissions?: string[]) => {
    storeUser(apiKey, userId, name, role, permissions);
    setUser({ apiKey, userId, name, role, permissions });
  }, []);

  const logout = useCallback(() => {
    // Invalidate the server session (cookie) so the browser can't resume it
    authApi.logout().catch(() => {});
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
                <Route path="/shared/artifacts/:token" element={<SharedArtifact />} />
                {authRequired && (
                  <>
                    <Route path="/login" element={
                      user
                        ? (() => {
                            const cb = new URLSearchParams(window.location.search).get('callback');
                            return cb
                              ? <Navigate to={`/authorize?callback=${encodeURIComponent(cb)}`} replace />
                              : <Navigate to={defaultPath} replace />;
                          })()
                        : <Login />
                    } />
                    <Route path="/register" element={<Register />} />
                    <Route path="/authorize" element={user ? <Authorize /> : <Navigate to="/login" replace />} />
                  </>
                )}
                {!user && authRequired ? (
                  <Route path="*" element={<Navigate to="/login" replace />} />
                ) : (
                  <Route element={<Layout />}>
                    <Route path="/for-you" element={<RequirePermission permission="dashboard.view"><ForYou /></RequirePermission>} />
                    <Route path="/for-you/artifacts" element={<RequirePermission permission="dashboard.view"><ArtifactList /></RequirePermission>} />
                    {caps.chat && <Route path="/chat" element={<RequirePermission permission="chat.use"><Chat /></RequirePermission>} />}
                    {caps.traces && <Route path="/traces" element={<RequirePermission permission="trace.view"><TraceList /></RequirePermission>} />}
                    {caps.traces && <Route path="/traces/:id" element={<RequirePermission permission="trace.view"><TraceDetail /></RequirePermission>} />}
                    {/* Sessions page removed - session list now in Chat sidebar */}
                    {caps.prompts && <Route path="/prompts" element={<RequirePermission permission="prompt.view"><PromptList /></RequirePermission>} />}
                    {caps.prompts && <Route path="/prompts/:id" element={<RequirePermission permission="prompt.view"><PromptEditor /></RequirePermission>} />}
                    <Route path="/agents" element={<RequirePermission permission="agent.view"><AgentList /></RequirePermission>} />
                    <Route path="/agents/:id" element={<RequirePermission permission="agent.view"><AgentEditor /></RequirePermission>} />
                    <Route path="/agents/:id/memories" element={<RequirePermission permission="agent.view"><AgentMemory /></RequirePermission>} />
                    <Route path="/runs/:id" element={<RequirePermission permission="agent.view"><RunDetail /></RequirePermission>} />
                    <Route path="/experiments/memory" element={<RequirePermission permission="experiment.view"><MemoryExperiment /></RequirePermission>} />
                    <Route path="/experiments/memory/runs/:id" element={<RequirePermission permission="experiment.view"><MemoryExperimentRunDetail /></RequirePermission>} />
                    <Route path="/experiments/memory/configs/:id" element={<RequirePermission permission="experiment.view"><MemoryExperimentConfigDetail /></RequirePermission>} />
                    <Route path="/workflows/explore" element={<RequirePermission permission="workflow.view"><WorkflowExplore /></RequirePermission>} />
                    <Route path="/workflows" element={<RequirePermission permission="workflow.view"><WorkflowList /></RequirePermission>} />
                    <Route path="/workflows/:id/runs" element={<RequirePermission permission="workflow.view"><WorkflowRuns /></RequirePermission>} />
                    <Route path="/workflows/:id" element={<RequirePermission permission="workflow.view"><WorkflowEditor /></RequirePermission>} />
                    {caps.systemPrompts && <Route path="/system-prompts" element={<RequirePermission permission="prompt.view"><SystemPromptList /></RequirePermission>} />}
                    {caps.systemPrompts && <Route path="/system-prompts/:promptId" element={<RequirePermission permission="prompt.view"><SystemPromptEditor /></RequirePermission>} />}
                    <Route path="/triggers/webhook" element={<RequirePermission permission="trigger.view"><TriggersWebhook /></RequirePermission>} />
                    <Route path="/triggers/channels" element={<RequirePermission permission="trigger.view"><Channels /></RequirePermission>} />
                    <Route path="/triggers/openclaw" element={<RequirePermission permission="trigger.view"><OpenClaw /></RequirePermission>} />
                    <Route path="/triggers/schedule" element={<RequirePermission permission="trigger.view"><Scheduler /></RequirePermission>} />
                    <Route path="/triggers" element={<Navigate to="/triggers/webhook" replace />} />
                    {/* Backward compat: old /scheduler redirects to /triggers/schedule */}
                    <Route path="/scheduler" element={<Navigate to="/triggers/schedule" replace />} />
                    <Route path="/tasks" element={<RequirePermission permission="task.view"><Tasks /></RequirePermission>} />
                    <Route path="/mcp" element={<RequirePermission permission="mcp.view"><Mcp /></RequirePermission>} />
                    <Route path="/mcp/:id" element={<RequirePermission permission="mcp.view"><McpDetail /></RequirePermission>} />
                    <Route path="/tools" element={<Navigate to="/tools/builtin" replace />} />
                    <Route path="/tools/builtin" element={<RequirePermission permission="tool.view"><BuiltinTools /></RequirePermission>} />
                    <Route path="/api-tools" element={<RequirePermission permission="apitool.view"><ApiTools /></RequirePermission>} />
                    <Route path="/api-tools/:id" element={<RequirePermission permission="apitool.view"><ApiToolDetail /></RequirePermission>} />
                    <Route path="/skills" element={<RequirePermission permission="skill.view"><SkillList /></RequirePermission>} />
                    <Route path="/skills/marketplace/:repoId" element={<RequirePermission permission="skill.view"><MarketplaceRepoDetail /></RequirePermission>} />
                    <Route path="/skills/:id/edit" element={<RequirePermission permission="skill.view"><SkillEditor /></RequirePermission>} />
                    <Route path="/datasets" element={<RequirePermission permission="dataset.view"><DatasetList /></RequirePermission>} />
                    <Route path="/datasets/:id" element={<RequirePermission permission="dataset.view"><DatasetEditor /></RequirePermission>} />
                    <Route path="/datasets/:id/records" element={<RequirePermission permission="dataset.view"><DatasetRecords /></RequirePermission>} />
                    <Route path="/projects" element={<RequirePermission permission="project.view"><ProjectList /></RequirePermission>} />
                    <Route path="/projects/:id/subjects/:subjectId" element={<RequirePermission permission="project.view"><SubjectDetail /></RequirePermission>} />
                    <Route path="/projects/:id/playbook" element={<RequirePermission permission="project.view"><PlaybookPage /></RequirePermission>} />
                    <Route path="/projects/:id" element={<RequirePermission permission="project.view"><ProjectDetail /></RequirePermission>} />
                    <Route path="/settings" element={<SettingsPage />}>
                      <Route path="api-keys" element={<ApiKeysPage />} />
                      <Route path="gateway" element={<RequirePermission permission="gateway.manage"><GatewayProvidersPage /></RequirePermission>} />
                      <Route path="system" element={<RequirePermission permission="system.manage"><SystemSettingsPage /></RequirePermission>} />
                      <Route path="cost-alerts" element={<RequirePermission permission="costalert.manage"><CostAlerts /></RequirePermission>} />
                      <Route path="api-users" element={<RequirePermission permission="user.manage"><Navigate to="/settings/users" replace /></RequirePermission>} />
                      {user?.role === 'admin' && <Route index element={<Dashboard />} />}
                      <Route path="users" element={<RequirePermission permission="user.manage"><UserManagement /></RequirePermission>} />
                      <Route path="tasks" element={<RequirePermission permission="task.view"><Tasks /></RequirePermission>} />
                      {user?.role !== 'admin' && <Route index element={<Navigate to="/settings/api-keys" replace />} />}
                    </Route>
                    <Route path="/notifications" element={<RequirePermission permission="notification.view"><NotificationsPage /></RequirePermission>} />
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
