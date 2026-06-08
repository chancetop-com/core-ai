import { useSearchParams, useNavigate } from 'react-router-dom';
import { Shield, ArrowRight, X } from 'lucide-react';
import { useAuth } from '../../api/auth';

export default function Authorize() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { user } = useAuth();
  const callback = params.get('callback');

  if (!callback) {
    return (
      <div className="min-h-screen flex items-center justify-center" style={{ background: 'var(--color-bg)' }}>
        <p style={{ color: 'var(--color-text-secondary)' }}>Missing callback parameter.</p>
      </div>
    );
  }

  if (!user) {
    // Not logged in — redirect to login preserving callback
    navigate(`/login?callback=${encodeURIComponent(callback)}`, { replace: true });
    return null;
  }

  const handleAuthorize = () => {
    const apiKey = localStorage.getItem('apiKey');
    if (!apiKey) {
      navigate('/login', { replace: true });
      return;
    }
    window.location.href = `${callback}?api_key=${encodeURIComponent(apiKey)}`;
  };

  const handleDeny = () => {
    window.location.href = `${callback}?error=denied`;
  };

  return (
    <div className="min-h-screen flex items-center justify-center" style={{ background: 'var(--color-bg)' }}>
      <div className="w-full max-w-md">
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-16 h-16 rounded-2xl mb-4"
               style={{ background: 'var(--color-primary)' }}>
            <Shield size={28} className="text-white" />
          </div>
          <h1 className="text-xl font-semibold mb-1" style={{ color: 'var(--color-text)' }}>
            Authorize core-ai-cli
          </h1>
          <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            Terminal application requesting access to your account
          </p>
        </div>

        <div className="rounded-xl border p-6 space-y-4"
             style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>

          <div className="flex items-center gap-3 p-3 rounded-lg"
               style={{ background: 'var(--color-bg-tertiary)' }}>
            <div className="w-8 h-8 rounded-full flex items-center justify-center text-sm font-medium"
                 style={{ background: 'var(--color-primary)', color: '#fff' }}>
              {user.name?.charAt(0).toUpperCase() || '?'}
            </div>
            <div>
              <div className="text-sm font-medium" style={{ color: 'var(--color-text)' }}>{user.name}</div>
              <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>Signed in</div>
            </div>
          </div>

          <p className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
            This will grant the CLI running on your machine access to use your account.
            Your API key will be sent back via a local callback.
          </p>

          <div className="flex gap-3 pt-2">
            <button onClick={handleDeny}
              className="flex-1 flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium cursor-pointer border"
              style={{
                color: 'var(--color-text-secondary)',
                borderColor: 'var(--color-border)',
                background: 'transparent',
              }}>
              <X size={16} />
              Deny
            </button>
            <button onClick={handleAuthorize}
              className="flex-1 flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium text-white cursor-pointer"
              style={{ background: 'var(--color-primary)' }}>
              <ArrowRight size={16} />
              Authorize
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
