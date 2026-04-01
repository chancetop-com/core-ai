import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bot, LogIn } from 'lucide-react';
import { authApi } from '../../api/client';
import { useAuth } from '../../api/auth';

export default function Login() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const { login } = useAuth();
  const navigate = useNavigate();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email.trim() || !password.trim()) return;
    setError('');
    setLoading(true);
    try {
      const res = await authApi.login(email, password);
      login(res.api_key, res.user_id, res.name);
      navigate('/');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Login failed');
    } finally {
      setLoading(false);
    }
  };

  const inputStyle = {
    background: 'var(--color-bg-tertiary)',
    borderColor: 'var(--color-border)',
    color: 'var(--color-text)',
  };

  return (
    <div className="min-h-screen flex items-center justify-center" style={{ background: 'var(--color-bg)' }}>
      <div className="w-full max-w-sm">
        <div className="text-center mb-8">
          <div className="w-14 h-14 rounded-2xl flex items-center justify-center mx-auto mb-4"
            style={{ background: 'var(--color-primary)', opacity: 0.9 }}>
            <Bot size={28} color="white" />
          </div>
          <h1 className="text-2xl font-bold">Core AI</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>Sign in to continue</p>
        </div>

        <form onSubmit={handleSubmit}
          className="rounded-xl border p-6 space-y-4"
          style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>

          {error && (
            <div className="px-3 py-2 rounded-lg text-sm" style={{ background: '#ef444420', color: 'var(--color-error)' }}>
              {error}
            </div>
          )}

          <div>
            <label className="block text-sm font-medium mb-1">Email</label>
            <input type="email" value={email} onChange={e => setEmail(e.target.value)}
              className="w-full px-3 py-2.5 rounded-lg border text-sm outline-none"
              style={inputStyle}
              placeholder="you@example.com"
              autoFocus />
          </div>

          <div>
            <label className="block text-sm font-medium mb-1">Password</label>
            <input type="password" value={password} onChange={e => setPassword(e.target.value)}
              className="w-full px-3 py-2.5 rounded-lg border text-sm outline-none"
              style={inputStyle}
              placeholder="Enter your password" />
          </div>

          <button type="submit" disabled={loading || !email.trim() || !password.trim()}
            className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-50"
            style={{ background: 'var(--color-primary)' }}>
            <LogIn size={16} />
            {loading ? 'Signing in...' : 'Sign In'}
          </button>
        </form>
      </div>
    </div>
  );
}
