import { Component } from 'react';
import type { ErrorInfo, ReactNode } from 'react';

interface ErrorBoundaryProps {
  children: ReactNode;
}

interface ErrorBoundaryState {
  error: Error | null;
}

/**
 * Global safety net: a single render error in any page should not blank the whole app.
 */
export default class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Unhandled render error:', error, info.componentStack);
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <div className="flex flex-col items-center justify-center gap-4 h-screen px-6 text-center">
        <p className="text-lg font-medium" style={{ color: 'var(--color-text)' }}>
          Something went wrong rendering this page.
        </p>
        <p className="text-sm max-w-xl break-words" style={{ color: 'var(--color-text-secondary)' }}>
          {this.state.error.message}
        </p>
        <button
          type="button"
          onClick={() => window.location.reload()}
          className="px-4 py-2 rounded-lg text-sm cursor-pointer"
          style={{ background: 'var(--color-primary)', color: '#fff' }}
        >
          Reload page
        </button>
      </div>
    );
  }
}
