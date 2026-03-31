import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { useEffect, useState } from 'react';
import Layout from './components/Layout';
import TraceList from './pages/traces/TraceList';
import TraceDetail from './pages/traces/TraceDetail';
import PromptList from './pages/prompts/PromptList';
import PromptEditor from './pages/prompts/PromptEditor';
import Dashboard from './pages/dashboard/Dashboard';
import Chat from './pages/chat/Chat';
import Generations from './pages/generations/Generations';
import Sessions from './pages/sessions/Sessions';
import { CapabilitiesContext, fetchCapabilities, defaultCapabilities } from './api/capabilities';
import type { Capabilities } from './api/capabilities';

export default function App() {
  const [caps, setCaps] = useState<Capabilities>(defaultCapabilities);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetchCapabilities().then(c => {
      setCaps(c);
      setLoading(false);
    });
  }, []);

  if (loading) return null;

  const defaultPath = caps.chat ? '/chat' : '/';

  return (
    <CapabilitiesContext.Provider value={caps}>
      <BrowserRouter>
        <Routes>
          <Route element={<Layout />}>
            {caps.chat && <Route path="/chat" element={<Chat />} />}
            {caps.traces && <Route path="/" element={<TraceList />} />}
            {caps.traces && <Route path="/traces/:id" element={<TraceDetail />} />}
            {caps.traces && <Route path="/generations" element={<Generations />} />}
            {caps.traces && <Route path="/sessions" element={<Sessions />} />}
            {caps.prompts && <Route path="/prompts" element={<PromptList />} />}
            {caps.prompts && <Route path="/prompts/:id" element={<PromptEditor />} />}
            {caps.dashboard && <Route path="/dashboard" element={<Dashboard />} />}
            <Route path="*" element={<Navigate to={defaultPath} replace />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </CapabilitiesContext.Provider>
  );
}
