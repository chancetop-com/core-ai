import { lazy, Suspense, useCallback, useEffect, useState } from 'react';
import {
  ChevronLeft,
  ChevronRight,
  FileText,
  Globe,
  Image as ImageIcon,
  FileCode,
  Search,
  Files,
  Share2,
} from 'lucide-react';
import { api } from '../../api/client';
import type { MyArtifactView, SharedArtifactView } from '../../api/client';
import type { ArtifactSpec } from '../chat/components/artifactTypes';

const ArtifactDrawer = lazy(() => import('../chat/components/ArtifactDrawer'));

type Tab = 'my' | 'shared';

const PAGE_SIZES = [20, 50];

function iconFor(contentType: string | undefined) {
  if (!contentType) return <FileText size={16} style={{ color: 'var(--color-text-secondary)' }} />;
  if (contentType.startsWith('image/')) return <ImageIcon size={16} style={{ color: 'var(--color-primary)' }} />;
  if (contentType.includes('html')) return <Globe size={16} style={{ color: 'var(--color-primary)' }} />;
  if (contentType.includes('javascript') || contentType.includes('json') || contentType.includes('xml'))
    return <FileCode size={16} style={{ color: 'var(--color-primary)' }} />;
  return <FileText size={16} style={{ color: 'var(--color-text-secondary)' }} />;
}

function formatSize(bytes: number): string {
  if (bytes >= 1048576) return `${(bytes / 1048576).toFixed(1)} MB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${bytes} B`;
}

function formatTime(iso: string): string {
  if (!iso) return '-';
  const d = new Date(iso);
  const now = Date.now();
  const diff = now - d.getTime();
  if (diff < 60_000) return 'just now';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
  return d.toLocaleDateString();
}

function formatContentType(ct: string | undefined): string {
  if (!ct) return '-';
  const parts = ct.split('/');
  return parts.length === 2 ? parts[1].toUpperCase() : ct;
}

export default function ArtifactList() {
  const [activeTab, setActiveTab] = useState<Tab>('my');
  const [loading, setLoading] = useState(true);
  const [activeArtifact, setActiveArtifact] = useState<ArtifactSpec | null>(null);

  // My artifacts state
  const [myArtifacts, setMyArtifacts] = useState<MyArtifactView[]>([]);
  const [myTotal, setMyTotal] = useState(0);
  const [myOffset, setMyOffset] = useState(0);
  const [myLimit, setMyLimit] = useState(PAGE_SIZES[0]);

  // Shared artifacts state
  const [sharedArtifacts, setSharedArtifacts] = useState<SharedArtifactView[]>([]);
  const [sharedTotal, setSharedTotal] = useState(0);
  const [sharedOffset, setSharedOffset] = useState(0);
  const [sharedLimit, setSharedLimit] = useState(PAGE_SIZES[0]);
  const [searchName, setSearchName] = useState('');
  const [filterUserId, setFilterUserId] = useState('');

  // Load my artifacts
  const loadMyArtifacts = useCallback(async (offset: number, limit: number) => {
    setLoading(true);
    try {
      const res = await api.artifacts.listMy(offset, limit);
      setMyArtifacts(res.artifacts);
      setMyTotal(res.total);
      setMyOffset(offset);
      setMyLimit(limit);
    } catch (err) {
      console.warn('Failed to load my artifacts', err);
    } finally {
      setLoading(false);
    }
  }, []);

  // Load shared artifacts
  const loadSharedArtifacts = useCallback(async (offset: number, limit: number, name?: string, userId?: string) => {
    setLoading(true);
    try {
      const res = await api.artifacts.listShared(offset, limit, name || undefined, userId || undefined);
      setSharedArtifacts(res.artifacts);
      setSharedTotal(res.total);
      setSharedOffset(offset);
      setSharedLimit(limit);
    } catch (err) {
      console.warn('Failed to load shared artifacts', err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (activeTab === 'my') {
      loadMyArtifacts(0, myLimit);
    } else {
      loadSharedArtifacts(0, sharedLimit, searchName, filterUserId);
    }
  }, [activeTab]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleTabChange = (tab: Tab) => {
    setActiveTab(tab);
    setLoading(true);
  };

  const handleMyPageChange = (newOffset: number) => {
    loadMyArtifacts(newOffset, myLimit);
  };

  const handleSharedPageChange = (newOffset: number) => {
    loadSharedArtifacts(newOffset, sharedLimit, searchName, filterUserId);
  };

  const handleSharedSearch = () => {
    loadSharedArtifacts(0, sharedLimit, searchName, filterUserId);
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') handleSharedSearch();
  };

  const myPageCount = Math.ceil(myTotal / myLimit);
  const myCurrentPage = Math.floor(myOffset / myLimit) + 1;
  const sharedPageCount = Math.ceil(sharedTotal / sharedLimit);
  const sharedCurrentPage = Math.floor(sharedOffset / sharedLimit) + 1;

  return (
    <div className="flex h-full">
      <div className="flex-1 min-w-0 overflow-auto">
    <div className="p-6">
      <div className="mb-6">
        <h1 className="text-2xl font-semibold">Artifacts</h1>
        <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
          Browse all your generated files and shared artifacts
        </p>
      </div>

      {/* Tabs */}
      <div className="mb-4 flex items-center justify-between">
        <div className="flex gap-1 p-1 rounded-lg" style={{ background: 'var(--color-bg-secondary)' }}>
          <button
            onClick={() => handleTabChange('my')}
            className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors cursor-pointer"
            style={{
              background: activeTab === 'my' ? 'var(--color-bg-tertiary)' : 'transparent',
              color: activeTab === 'my' ? 'var(--color-text)' : 'var(--color-text-secondary)',
            }}>
            <Files size={14} />
            My Artifacts
          </button>
          <button
            onClick={() => handleTabChange('shared')}
            className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-colors cursor-pointer"
            style={{
              background: activeTab === 'shared' ? 'var(--color-bg-tertiary)' : 'transparent',
              color: activeTab === 'shared' ? 'var(--color-text)' : 'var(--color-text-secondary)',
            }}>
            <Share2 size={14} />
            Shared Artifacts
          </button>
        </div>

        {/* Search & filter for shared tab */}
        {activeTab === 'shared' && (
          <div className="flex items-center gap-2">
            <div className="relative w-64">
              <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2"
                style={{ color: 'var(--color-text-secondary)' }} />
              <input
                type="text"
                value={searchName}
                onChange={e => setSearchName(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="Search by name..."
                className="w-full pl-9 pr-3 py-2 rounded-lg border text-sm outline-none"
                style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}
              />
            </div>
            <div className="relative w-48">
              <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2"
                style={{ color: 'var(--color-text-secondary)' }} />
              <input
                type="text"
                value={filterUserId}
                onChange={e => setFilterUserId(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="Filter by user ID..."
                className="w-full pl-9 pr-3 py-2 rounded-lg border text-sm outline-none"
                style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}
              />
            </div>
            <button
              onClick={handleSharedSearch}
              className="px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
              style={{ background: 'var(--color-primary)' }}>
              Search
            </button>
          </div>
        )}
      </div>

      {/* Content */}
      <div className="grid gap-3">
        {loading ? (
          <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
        ) : activeTab === 'my' ? (
          myArtifacts.length === 0 ? (
            <div className="text-center py-12 rounded-xl border"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
              No artifacts yet. Generated files from your chats will appear here.
            </div>
          ) : (
            myArtifacts.map(a => (
              <div
                key={a.id}
                onClick={() => setActiveArtifact({
                  kind: 'file',
                  title: a.file_name,
                  fileId: a.id,
                  fileName: a.file_name,
                  contentType: a.content_type,
                  size: a.size ?? undefined,
                })}
                className="rounded-xl border p-4 cursor-pointer transition-colors"
                style={{
                  background: 'var(--color-bg-secondary)',
                  borderColor: 'var(--color-border)',
                }}
                onMouseEnter={e => { e.currentTarget.style.background = 'var(--color-bg-tertiary)'; }}
                onMouseLeave={e => { e.currentTarget.style.background = 'var(--color-bg-secondary)'; }}>
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3 min-w-0">
                    {iconFor(a.content_type)}
                    <span className="font-medium truncate">{a.file_name}</span>
                    <span className="px-2 py-0.5 rounded text-xs"
                      style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                      {formatContentType(a.content_type)}
                    </span>
                    <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                      {formatSize(a.size)}
                    </span>
                  </div>
                  <div className="flex items-center gap-4 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                    <span>Created: {formatTime(a.created_at)}</span>
                    {a.session_title && (
                      <span className="text-xs" style={{ color: 'var(--color-primary)' }}>
                        Chat: {a.session_title}
                      </span>
                    )}
                  </div>
                </div>
              </div>
            ))
          )
        ) : (
          sharedArtifacts.length === 0 ? (
            <div className="text-center py-12 rounded-xl border"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
              No shared artifacts found.
            </div>
          ) : (
            sharedArtifacts.map(a => (
              <div
                key={a.id}
                onClick={() => setActiveArtifact({
                  kind: 'file',
                  title: a.file_name,
                  fileId: a.id,
                  fileName: a.file_name,
                  contentType: a.content_type,
                  size: a.size ?? undefined,
                })}
                className="rounded-xl border p-4 cursor-pointer transition-colors"
                style={{
                  background: 'var(--color-bg-secondary)',
                  borderColor: 'var(--color-border)',
                }}
                onMouseEnter={e => { e.currentTarget.style.background = 'var(--color-bg-tertiary)'; }}
                onMouseLeave={e => { e.currentTarget.style.background = 'var(--color-bg-secondary)'; }}>
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3 min-w-0">
                    {iconFor(a.content_type)}
                    <span className="font-medium truncate">{a.file_name}</span>
                    <span className="px-2 py-0.5 rounded text-xs"
                      style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                      {formatContentType(a.content_type)}
                    </span>
                    <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                      {formatSize(a.size)}
                    </span>
                  </div>
                  <div className="flex items-center gap-4 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                    <span>By: {a.user_id?.substring(0, 8)}</span>
                    <span>Created: {formatTime(a.created_at)}</span>
                    {a.shared_at && <span>Shared: {formatTime(a.shared_at)}</span>}
                  </div>
                </div>
              </div>
            ))
          )
        )}
      </div>

      {/* Pagination */}
      {activeTab === 'my' && myTotal > 0 && (
        <div className="flex items-center justify-between mt-4">
          <div className="flex items-center gap-3">
            <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
              Showing {myOffset + 1}-{Math.min(myOffset + myLimit, myTotal)} of {myTotal}
            </span>
            <select
              value={myLimit}
              onChange={e => { const l = Number(e.target.value); loadMyArtifacts(0, l); }}
              className="px-2 py-1 rounded-lg border text-xs"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
              {PAGE_SIZES.map(n => <option key={n} value={n}>{n} / page</option>)}
            </select>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
              Page {myCurrentPage} of {myPageCount || 1}
            </span>
            <button
              onClick={() => handleMyPageChange(Math.max(0, myOffset - myLimit))}
              disabled={myOffset === 0}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              <ChevronLeft size={14} /> Prev
            </button>
            <button
              onClick={() => handleMyPageChange(myOffset + myLimit)}
              disabled={myOffset + myLimit >= myTotal}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              Next <ChevronRight size={14} />
            </button>
          </div>
        </div>
      )}

      {activeTab === 'shared' && sharedTotal > 0 && (
        <div className="flex items-center justify-between mt-4">
          <div className="flex items-center gap-3">
            <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
              Showing {sharedOffset + 1}-{Math.min(sharedOffset + sharedLimit, sharedTotal)} of {sharedTotal}
            </span>
            <select
              value={sharedLimit}
              onChange={e => { const l = Number(e.target.value); loadSharedArtifacts(0, l, searchName, filterUserId); }}
              className="px-2 py-1 rounded-lg border text-xs"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)', color: 'var(--color-text)' }}>
              {PAGE_SIZES.map(n => <option key={n} value={n}>{n} / page</option>)}
            </select>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-sm" style={{ color: 'var(--color-text-secondary)' }}>
              Page {sharedCurrentPage} of {sharedPageCount || 1}
            </span>
            <button
              onClick={() => handleSharedPageChange(Math.max(0, sharedOffset - sharedLimit))}
              disabled={sharedOffset === 0}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              <ChevronLeft size={14} /> Prev
            </button>
            <button
              onClick={() => handleSharedPageChange(sharedOffset + sharedLimit)}
              disabled={sharedOffset + sharedLimit >= sharedTotal}
              className="px-3 py-1.5 rounded-lg border text-sm flex items-center gap-1 disabled:opacity-40 cursor-pointer"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              Next <ChevronRight size={14} />
            </button>
          </div>
        </div>
      )}
    </div>
      </div>
      {activeArtifact && (
        <Suspense fallback={null}>
          <ArtifactDrawer artifact={activeArtifact} onClose={() => setActiveArtifact(null)} />
        </Suspense>
      )}
    </div>
  );
}
