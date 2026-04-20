import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Bot, UserPlus } from 'lucide-react';
import { authApi } from '../../api/client';

export default function Register() {
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!email.trim() || !password.trim()) return;
    if (password !== confirmPassword) {
      setError('Passwords do not match');
      return;
    }
    if (password.length < 6) {
      setError('Password must be at least 6 characters');
      return;
    }
    setError('');
    setLoading(true);
    try {
      await authApi.register(email, password, name || undefined);
      navigate('/login', { state: { registered: true } });
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Registration failed');
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
          <h1 className="text-2xl font-bold">Create Account</h1>
          <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>Sign up to get started</p>
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
            <label className="block text-sm font-medium mb-1">Name (optional)</label>
            <input type="text" value={name} onChange={e => setName(e.target.value)}
              className="w-full px-3 py-2.5 rounded-lg border text-sm outline-none"
              style={inputStyle}
              placeholder="Your name" />
          </div>

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
              placeholder="At least 6 characters" />
          </div>

          <div>
            <label className="block text-sm font-medium mb-1">Confirm Password</label>
            <input type="password" value={confirmPassword} onChange={e => setConfirmPassword(e.target.value)}
              className="w-full px-3 py-2.5 rounded-lg border text-sm outline-none"
              style={inputStyle}
              placeholder="Re-enter your password" />
          </div>

          <button type="submit" disabled={loading || !email.trim() || !password.trim() || !confirmPassword.trim()}
            className="w-full flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-50"
            style={{ background: 'var(--color-primary)' }}>
            <UserPlus size={16} />
            {loading ? 'Creating account...' : 'Create Account'}
          </button>

          <div className="text-center text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            Already have an account?{' '}
            <Link to="/login" className="font-medium cursor-pointer" style={{ color: 'var(--color-primary)' }}>
              Sign in
            </Link>
          </div>
        </form>

        <p className="text-xs text-center mt-4" style={{ color: 'var(--color-text-secondary)' }}>
          After registration, your account requires admin approval to activate.
        </p>
      </div>
    </div>
  );
}