import ApiKeys from './ApiKeys';
import Workspaces from './Workspaces';

export default function Settings() {
  return (
    <div className="h-full overflow-auto">
      <div className="px-6 py-4 border-b" style={{ borderColor: 'var(--color-border)' }}>
        <h1 className="text-lg font-semibold">Settings</h1>
      </div>
      <ApiKeys />
      <div className="px-6 pb-8">
        <Workspaces />
      </div>
    </div>
  );
}
