import { Navigate, Outlet } from 'react-router-dom';

export default function Tools() {
  const isRoot = window.location.pathname === '/tools';
  if (isRoot) {
    return <Navigate to="/tools/builtin" replace />;
  }
  return <Outlet />;
}
