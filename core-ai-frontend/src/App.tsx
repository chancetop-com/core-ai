import { BrowserRouter, Routes, Route } from 'react-router-dom';
import Layout from './components/Layout';
import TraceList from './pages/traces/TraceList';
import TraceDetail from './pages/traces/TraceDetail';
import PromptList from './pages/prompts/PromptList';
import PromptEditor from './pages/prompts/PromptEditor';
import Dashboard from './pages/dashboard/Dashboard';

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<Layout />}>
          <Route path="/" element={<TraceList />} />
          <Route path="/traces/:id" element={<TraceDetail />} />
          <Route path="/prompts" element={<PromptList />} />
          <Route path="/prompts/:id" element={<PromptEditor />} />
          <Route path="/dashboard" element={<Dashboard />} />
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
