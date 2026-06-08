import { useState, useEffect } from 'react';
import { Link, useNavigate, useLocation, useSearchParams } from 'react-router-dom';
import { LogIn } from 'lucide-react';
import { authApi } from '../../api/client';
import { useAuth } from '../../api/auth';
import { useTheme } from '../../hooks/useTheme';

export default function Login() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [loading, setLoading] = useState(false);
  const { login } = useAuth();
  const { dark } = useTheme();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const callback = searchParams.get('callback');

  useEffect(() => {
    if (location.state?.registered) {
      setSuccess('Account created! Please wait for admin approval.');
      navigate('/login', { replace: true });
    }
  }, [location.state, navigate]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email.trim() || !password.trim()) return;
    setError('');
    setLoading(true);
    try {
      const res = await authApi.login(email, password);
      login(res.api_key, res.user_id, res.name, res.role);
      if (callback) {
        navigate(`/authorize?callback=${encodeURIComponent(callback)}`);
      } else {
        navigate('/');
      }
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
          <img
            src={dark ? '/logo-lockup-dark.svg' : '/logo-lockup.svg'}
            alt="core-ai"
            className="h-14 mx-auto mb-3"
          />
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

          {success && (
            <div className="px-3 py-2 rounded-lg text-sm" style={{ background: '#22c55e20', color: '#22c55e' }}>
              {success}
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

          <div className="text-center text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            Don't have an account?{' '}
            <Link to="/register" className="font-medium cursor-pointer" style={{ color: 'var(--color-primary)' }}>
              Sign up
            </Link>
          </div>
        </form>
      </div>
    </div>
  );
}
