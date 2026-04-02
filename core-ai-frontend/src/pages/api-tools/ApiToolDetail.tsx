import { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  ChevronDown, ChevronRight, Save, ArrowLeft, RefreshCw,
  Power, PowerOff, Settings, Type, Code, Key,
  FileText, Shield, Info, Edit2, Check, X,
} from 'lucide-react';
import { api } from '../../api/client';
import type { ServiceApiView, ServiceAdditionalView, TypeAdditionalView } from '../../api/client';

// --- Types for parsed payload ---
interface ParsedService {
  name: string;
  description?: string;
  operations: ParsedOperation[];
}

interface ParsedOperation {
  name: string;
  method?: string;
  path?: string;
  description?: string;
  requestType?: string;
  responseType?: string;
  deprecated?: boolean;
  optional?: boolean;
  pathParams: ParsedPathParam[];
}

interface ParsedPathParam {
  name: string;
  type?: string;
  description?: string;
  example?: string;
}

interface ParsedType {
  name: string;
  type?: string;
  description?: string;
  fields: ParsedField[];
  enumConstants?: ParsedEnumConstant[];
}

interface ParsedField {
  name: string;
  type?: string;
  description?: string;
  example?: string;
}

interface ParsedEnumConstant {
  name: string;
  value?: string;
}

interface ParsedPayload {
  app?: string;
  services: ParsedService[];
  types: ParsedType[];
}

type EditableService = {
  name: string;
  enabled: boolean;
  description: string;
  operations: EditableOperation[];
};

type EditableOperation = {
  name: string;
  enabled: boolean;
  method?: string;
  path?: string;
  description: string;
  example: string;
  needAuth: boolean;
  deprecated?: boolean;
  pathParams: EditablePathParam[];
};

type EditablePathParam = {
  name: string;
  type?: string;
  description: string;
  example: string;
};

type EditableType = {
  name: string;
  description: string;
  fields: EditableField[];
};

type EditableField = {
  name: string;
  description: string;
  example: string;
};

export default function ApiToolDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [data, setData] = useState<ServiceApiView | null>(null);
  const [loading, setLoading] = useState(true);
  const [updatingFromSys, setUpdatingFromSys] = useState(false);
  const [saving, setSaving] = useState(false);

  // Editable state
  const [expandedServices, setExpandedServices] = useState<Set<string>>(new Set());
  const [expandedTypes, setExpandedTypes] = useState<Set<string>>(new Set());
  const [expandedOps, setExpandedOps] = useState<Set<string>>(new Set());
  const [services, setServices] = useState<EditableService[]>([]);
  const [types, setTypes] = useState<EditableType[]>([]);
  const [activeTab, setActiveTab] = useState<'services' | 'types'>('services');
  const [dirty, setDirty] = useState(false);

  const load = useCallback(() => {
    if (!id) return;
    setLoading(true);
    api.serviceApis.get(id)
      .then(res => {
        setData(res);
        const payload = res.payload ? JSON.parse(res.payload) as Record<string, unknown> : null;
        if (payload) {
          const parsedData = parsePayload(payload);
          setServices(buildEditableServices(parsedData.services, res.service_additional || []));
          setTypes(buildEditableTypes(parsedData.types, res.type_additional || []));
        } else {
          setServices([]);
          setTypes([]);
        }
        setDirty(false);
      })
      .catch(err => {
        console.error('Failed to load service API:', err);
        setData(null);
      })
      .finally(() => setLoading(false));
  }, [id]);

  useEffect(() => { load(); }, [load]);

  const toggleServiceExpand = (name: string) => {
    setExpandedServices(prev => {
      const next = new Set(prev);
      if (next.has(name)) next.delete(name); else next.add(name);
      return next;
    });
  };

  const toggleTypeExpand = (name: string) => {
    setExpandedTypes(prev => {
      const next = new Set(prev);
      if (next.has(name)) next.delete(name); else next.add(name);
      return next;
    });
  };

  const toggleOpExpand = (name: string) => {
    setExpandedOps(prev => {
      const next = new Set(prev);
      if (next.has(name)) next.delete(name); else next.add(name);
      return next;
    });
  };

  const updateService = (svcName: string, update: Partial<EditableService>) => {
    setServices(prev => prev.map(s => s.name === svcName ? { ...s, ...update } : s));
    setDirty(true);
  };

  const updateOperation = (svcName: string, opName: string, update: Partial<EditableOperation>) => {
    setServices(prev => prev.map(s =>
      s.name === svcName
        ? { ...s, operations: s.operations.map(o => o.name === opName ? { ...o, ...update } : o) }
        : s
    ));
    setDirty(true);
  };

  const updatePathParam = (svcName: string, opName: string, ppName: string, update: Partial<EditablePathParam>) => {
    setServices(prev => prev.map(s =>
      s.name === svcName
        ? {
            ...s,
            operations: s.operations.map(o =>
              o.name === opName
                ? { ...o, pathParams: o.pathParams.map(p => p.name === ppName ? { ...p, ...update } : p) }
                : o
            ),
          }
        : s
    ));
    setDirty(true);
  };

  const updateType = (typeName: string, update: Partial<EditableType>) => {
    setTypes(prev => prev.map(t => t.name === typeName ? { ...t, ...update } : t));
    setDirty(true);
  };

  const updateField = (typeName: string, fieldName: string, update: Partial<EditableField>) => {
    setTypes(prev => prev.map(t =>
      t.name === typeName
        ? { ...t, fields: t.fields.map(f => f.name === fieldName ? { ...f, ...update } : f) }
        : t
    ));
    setDirty(true);
  };

  const handleSave = async () => {
    if (!data || !id) return;
    setSaving(true);
    try {
      const serviceAdditional: ServiceAdditionalView[] = services.map(s => ({
        name: s.name,
        description: s.description || undefined,
        enabled: s.enabled,
        operation_additional: s.operations.map(op => ({
          name: op.name,
          description: op.description || undefined,
          example: op.example || undefined,
          enabled: op.enabled,
          need_auth: op.needAuth || undefined,
          path_param_additional: op.pathParams.map(pp => ({
            name: pp.name,
            description: pp.description || undefined,
            example: pp.example || undefined,
          })),
        })),
      }));

      const typeAdditional: TypeAdditionalView[] = types.map(t => ({
        name: t.name,
        description: t.description || undefined,
        field_additional: t.fields.map(f => ({
          name: f.name,
          description: f.description || undefined,
          example: f.example || undefined,
        })),
      }));

      await api.serviceApis.update(id, {
        description: data.description,
        enabled: data.enabled,
        url: data.url,
        base_url: data.base_url,
        operator: 'admin',
        service_additional: serviceAdditional,
        type_additional: typeAdditional,
      });
      setDirty(false);
      load();
    } catch (err) {
      console.error('Failed to save:', err);
      alert('Failed to save. Check console for details.');
    } finally {
      setSaving(false);
    }
  };

  const handleToggleEnabled = async () => {
    if (!data || !id) return;
    try {
      if (data.enabled) {
        await api.serviceApis.disable(id, 'admin');
      } else {
        await api.serviceApis.enable(id, 'admin');
      }
      load();
    } catch (err) {
      console.error('Failed to toggle:', err);
    }
  };

  const handleUpdateFromSysApi = async () => {
    if (!data || !data.url || !id) return;
    if (!confirm(`Update from system API at ${data.url}? This will refresh all service/operation definitions.`)) return;
    setUpdatingFromSys(true);
    try {
      await api.serviceApis.updateFromSysApi(id, data.url, 'admin');
      load();
    } catch (err) {
      console.error('Failed to update from system API:', err);
      alert('Failed to update from system API. Check console for details.');
    } finally {
      setUpdatingFromSys(false);
    }
  };

  if (loading) {
    return (
      <div className="p-6">
        <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>
      </div>
    );
  }

  if (!data) {
    return (
      <div className="p-6">
        <div className="text-center py-12" style={{ color: 'var(--color-text-secondary)' }}>
          <p>Service API not found.</p>
          <button onClick={() => navigate('/api-tools')} className="mt-4 text-sm underline cursor-pointer" style={{ color: 'var(--color-primary)' }}>
            Back to API Tools
          </button>
        </div>
      </div>
    );
  }

  const totalOps = services.reduce((sum, s) => sum + s.operations.length, 0);
  const enabledOps = services.reduce((sum, s) => sum + s.operations.filter(o => o.enabled).length, 0);

  return (
    <div className="p-6">
      {/* Header */}
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-4">
          <button onClick={() => navigate('/api-tools')} className="p-2 rounded-lg border cursor-pointer"
            style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }} title="Back">
            <ArrowLeft size={18} />
          </button>
          <div>
            <div className="flex items-center gap-3">
              <Key size={20} style={{ color: 'var(--color-primary)' }} />
              <h1 className="text-2xl font-semibold">{data.name}</h1>
              <span className={`px-2 py-0.5 rounded text-xs ${data.enabled ? 'text-white' : ''}`}
                style={data.enabled ? { background: '#065f46' } : { background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                {data.enabled ? 'Enabled' : 'Disabled'}
              </span>
            </div>
            {data.description && (
              <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>{data.description}</p>
            )}
            <div className="flex items-center gap-4 mt-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              {data.base_url && <span>Base URL: <code className="font-mono">{data.base_url}</code></span>}
              {data.version && <span>Version: {data.version}</span>}
              <span>Services: {services.length}</span>
              <span>Operations: {enabledOps}/{totalOps}</span>
              <span>Types: {types.length}</span>
            </div>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button onClick={handleToggleEnabled} className="flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium cursor-pointer"
            style={{ background: data.enabled ? '#991b1b' : '#065f46', color: '#fff' }}>
            {data.enabled ? <PowerOff size={14} /> : <Power size={14} />}
            {data.enabled ? 'Disable' : 'Enable'}
          </button>
          {data.url && (
            <button onClick={handleUpdateFromSysApi} disabled={updatingFromSys}
              className="flex items-center gap-2 px-3 py-2 rounded-lg text-sm font-medium border cursor-pointer disabled:opacity-40"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
              <RefreshCw size={14} className={updatingFromSys ? 'animate-spin' : ''} />
              Sync
            </button>
          )}
          <button onClick={handleSave} disabled={saving || !dirty}
            className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-40"
            style={{ background: 'var(--color-primary)' }}>
            <Save size={14} />
            {saving ? 'Saving...' : dirty ? 'Save Changes' : 'Saved'}
          </button>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 mb-4 rounded-lg p-1" style={{ background: 'var(--color-bg-secondary)', width: 'fit-content' }}>
        <button onClick={() => setActiveTab('services')}
          className={`px-4 py-2 rounded-md text-sm font-medium flex items-center gap-2 cursor-pointer ${activeTab === 'services' ? '' : ''}`}
          style={activeTab === 'services'
            ? { background: 'var(--color-bg)', color: 'var(--color-text)' }
            : { background: 'transparent', color: 'var(--color-text-secondary)' }}>
          <Code size={14} /> Services ({services.length})
        </button>
        <button onClick={() => setActiveTab('types')}
          className={`px-4 py-2 rounded-md text-sm font-medium flex items-center gap-2 cursor-pointer`}
          style={activeTab === 'types'
            ? { background: 'var(--color-bg)', color: 'var(--color-text)' }
            : { background: 'transparent', color: 'var(--color-text-secondary)' }}>
          <Type size={14} /> Types ({types.length})
        </button>
      </div>

      {/* Services Tab */}
      {activeTab === 'services' && (
        <div className="space-y-3">
          {services.length === 0 ? (
            <div className="text-center py-12 rounded-xl border"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
              No services found. Update from system API to load definitions.
            </div>
          ) : services.map(svc => (
            <ServiceSection
              key={svc.name}
              service={svc}
              expanded={expandedServices.has(svc.name)}
              onToggle={() => toggleServiceExpand(svc.name)}
              onUpdate={(update) => updateService(svc.name, update)}
              expandedOps={expandedOps}
              onToggleOp={toggleOpExpand}
              onUpdateOp={(opName, update) => updateOperation(svc.name, opName, update)}
              onUpdatePathParam={(opName, ppName, update) => updatePathParam(svc.name, opName, ppName, update)}
            />
          ))}
        </div>
      )}

      {/* Types Tab */}
      {activeTab === 'types' && (
        <div className="space-y-3">
          {types.length === 0 ? (
            <div className="text-center py-12 rounded-xl border"
              style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
              No types found.
            </div>
          ) : types.map(t => (
            <TypeSection
              key={t.name}
              type={t}
              expanded={expandedTypes.has(t.name)}
              onToggle={() => toggleTypeExpand(t.name)}
              onUpdate={(update) => updateType(t.name, update)}
              onUpdateField={(fieldName, update) => updateField(t.name, fieldName, update)}
            />
          ))}
        </div>
      )}
    </div>
  );
}

// --- Service Section ---
function ServiceSection({
  service,
  expanded,
  onToggle,
  onUpdate,
  expandedOps,
  onToggleOp,
  onUpdateOp,
  onUpdatePathParam,
}: {
  service: EditableService;
  expanded: boolean;
  onToggle: () => void;
  onUpdate: (update: Partial<EditableService>) => void;
  expandedOps: Set<string>;
  onToggleOp: (opName: string) => void;
  onUpdateOp: (opName: string, update: Partial<EditableOperation>) => void;
  onUpdatePathParam: (opName: string, ppName: string, update: Partial<EditablePathParam>) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [editDesc, setEditDesc] = useState(service.description);

  const handleSaveDesc = () => {
    onUpdate({ description: editDesc });
    setEditing(false);
  };

  return (
    <div className="rounded-xl border overflow-hidden"
      style={{
        borderColor: service.enabled ? 'var(--color-border)' : '#7f1d1d',
        background: 'var(--color-bg-secondary)',
        opacity: service.enabled ? 1 : 0.7,
      }}>
      {/* Service Header */}
      <div className="flex items-center justify-between p-4 cursor-pointer" onClick={onToggle}>
        <div className="flex items-center gap-3">
          {expanded ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
          <span className="font-medium">{service.name}</span>
          <span className={`px-2 py-0.5 rounded text-xs ${service.enabled ? 'text-white' : ''}`}
            style={service.enabled ? { background: '#065f46' } : { background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
            {service.enabled ? 'Enabled' : 'Disabled'}
          </span>
          <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
            {service.operations.length} ops
          </span>
        </div>
        <div className="flex items-center gap-2" onClick={e => e.stopPropagation()}>
          <button onClick={() => { setEditDesc(service.description); setEditing(true); }}
            className="p-1.5 rounded border cursor-pointer" style={{ borderColor: 'var(--color-border)' }} title="Edit description">
            <Edit2 size={13} />
          </button>
          <button onClick={() => onUpdate({ enabled: !service.enabled })}
            className="p-1.5 rounded border cursor-pointer" style={{ borderColor: 'var(--color-border)' }}
            title={service.enabled ? 'Disable' : 'Enable'}>
            {service.enabled ? <PowerOff size={13} /> : <Power size={13} />}
          </button>
        </div>
      </div>

      {/* Service Description Edit */}
      {editing && (
        <div className="px-4 pb-3" onClick={e => e.stopPropagation()}>
          <div className="flex items-center gap-2">
            <input type="text" value={editDesc} onChange={e => setEditDesc(e.target.value)}
              className="flex-1 px-3 py-1.5 rounded-lg border text-sm"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)', color: 'var(--color-text)' }}
              placeholder="Service description"
              onKeyDown={e => { if (e.key === 'Enter') handleSaveDesc(); }} />
            <button onClick={handleSaveDesc} className="p-1.5 rounded cursor-pointer" style={{ color: '#34d399' }}>
              <Check size={16} />
            </button>
            <button onClick={() => setEditing(false)} className="p-1.5 rounded cursor-pointer" style={{ color: '#f87171' }}>
              <X size={16} />
            </button>
          </div>
        </div>
      )}

      {/* Service Description Display */}
      {service.description && !editing && (
        <div className="px-4 pb-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
          {service.description}
        </div>
      )}

      {/* Operations */}
      {expanded && (
        <div className="border-t" style={{ borderColor: 'var(--color-border)' }}>
          {service.operations.map(op => (
            <OperationRow
              key={op.name}
              operation={op}
              expanded={expandedOps.has(op.name)}
              onToggle={() => onToggleOp(op.name)}
              onUpdate={(update) => onUpdateOp(op.name, update)}
              onUpdatePathParam={(ppName, update) => onUpdatePathParam(op.name, ppName, update)}
            />
          ))}
        </div>
      )}
    </div>
  );
}

// --- Operation Row ---
function OperationRow({
  operation,
  expanded,
  onToggle,
  onUpdate,
  onUpdatePathParam,
}: {
  operation: EditableOperation;
  expanded: boolean;
  onToggle: () => void;
  onUpdate: (update: Partial<EditableOperation>) => void;
  onUpdatePathParam: (ppName: string, update: Partial<EditablePathParam>) => void;
}) {
  const methodColors: Record<string, string> = {
    GET: '#065f46', POST: '#1e40af', PUT: '#78350f', DELETE: '#991b1b', PATCH: '#581c87',
  };

  return (
    <div className="border-b last:border-b-0" style={{ borderColor: 'var(--color-border)' }}>
      <div className="flex items-center justify-between px-4 py-2.5 cursor-pointer" onClick={onToggle}>
        <div className="flex items-center gap-3">
          {expanded ? <ChevronDown size={14} style={{ color: 'var(--color-text-secondary)' }} /> : <ChevronRight size={14} style={{ color: 'var(--color-text-secondary)' }} />}
          {operation.method && (
            <span className="px-1.5 py-0.5 rounded text-xs font-mono font-bold text-white"
              style={{ background: methodColors[operation.method] || '#374151' }}>
              {operation.method}
            </span>
          )}
          <span className="text-sm font-mono">{operation.name}</span>
          {operation.deprecated && (
            <span className="px-1.5 py-0.5 rounded text-xs" style={{ background: '#92400e', color: '#fbbf24' }}>DEPRECATED</span>
          )}
          {!operation.enabled && (
            <span className="px-1.5 py-0.5 rounded text-xs" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>DISABLED</span>
          )}
        </div>
        <div className="flex items-center gap-2" onClick={e => e.stopPropagation()}>
          <button onClick={() => onUpdate({ enabled: !operation.enabled })}
            className="p-1 rounded cursor-pointer" title={operation.enabled ? 'Disable' : 'Enable'}
            style={{ color: operation.enabled ? '#34d399' : '#f87171' }}>
            {operation.enabled ? <PowerOff size={12} /> : <Power size={12} />}
          </button>
        </div>
      </div>

      {/* Operation Details */}
      {expanded && (
        <div className="px-4 pb-3 pl-10 space-y-3">
          {/* Description */}
          <div>
            <label className="block text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>
              <Info size={10} className="inline mr-1" /> Description
            </label>
            <input type="text" value={operation.description}
              onChange={e => onUpdate({ description: e.target.value })}
              className="w-full px-2 py-1.5 rounded border text-xs"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)', color: 'var(--color-text)' }} />
          </div>

          {/* Example */}
          <div>
            <label className="block text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>
              <FileText size={10} className="inline mr-1" /> Example
            </label>
            <textarea value={operation.example} rows={2}
              onChange={e => onUpdate({ example: e.target.value })}
              className="w-full px-2 py-1.5 rounded border text-xs font-mono"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)', color: 'var(--color-text)' }} />
          </div>

          {/* Need Auth */}
          <div className="flex items-center gap-2">
            <label className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              <Shield size={10} className="inline mr-1" /> Need Auth
            </label>
            <input type="checkbox" checked={operation.needAuth || false}
              onChange={e => onUpdate({ needAuth: e.target.checked })}
              className="w-3.5 h-3.5" />
          </div>

          {/* Path Params */}
          {operation.pathParams.length > 0 && (
            <div>
              <label className="block text-xs mb-2" style={{ color: 'var(--color-text-secondary)' }}>
                <Settings size={10} className="inline mr-1" /> Path Parameters
              </label>
              <div className="space-y-2">
                {operation.pathParams.map(pp => (
                  <div key={pp.name} className="flex items-center gap-3 p-2 rounded-lg"
                    style={{ background: 'var(--color-bg-tertiary)' }}>
                    <code className="text-xs font-mono font-bold min-w-[80px]">{pp.name}</code>
                    {pp.type && <span className="text-xs font-mono" style={{ color: 'var(--color-text-secondary)' }}>{pp.type}</span>}
                    <div className="flex-1 flex items-center gap-2">
                      <input type="text" value={pp.description} placeholder="Description"
                        onChange={e => onUpdatePathParam(pp.name, { description: e.target.value })}
                        className="flex-1 px-2 py-1 rounded border text-xs"
                        style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)', color: 'var(--color-text)' }} />
                      <input type="text" value={pp.example} placeholder="Example"
                        onChange={e => onUpdatePathParam(pp.name, { example: e.target.value })}
                        className="flex-1 px-2 py-1 rounded border text-xs font-mono"
                        style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)', color: 'var(--color-text)' }} />
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// --- Type Section ---
function TypeSection({
  type,
  expanded,
  onToggle,
  onUpdate,
  onUpdateField,
}: {
  type: EditableType;
  expanded: boolean;
  onToggle: () => void;
  onUpdate: (update: Partial<EditableType>) => void;
  onUpdateField: (fieldName: string, update: Partial<EditableField>) => void;
}) {
  return (
    <div className="rounded-xl border overflow-hidden" style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg-secondary)' }}>
      <div className="flex items-center justify-between p-4 cursor-pointer" onClick={onToggle}>
        <div className="flex items-center gap-3">
          {expanded ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
          <Type size={16} style={{ color: 'var(--color-primary)' }} />
          <span className="font-medium font-mono">{type.name}</span>
          {type.description && (
            <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>{type.description}</span>
          )}
        </div>
      </div>

      {expanded && (
        <div className="border-t px-4 pb-3 pt-2 space-y-2" style={{ borderColor: 'var(--color-border)' }}>
          {/* Description */}
          <div>
            <label className="block text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>Description</label>
            <input type="text" value={type.description}
              onChange={e => onUpdate({ description: e.target.value })}
              className="w-full px-2 py-1.5 rounded border text-xs"
              style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)', color: 'var(--color-text)' }} />
          </div>

          {/* Fields */}
          {type.fields.length > 0 && (
            <div>
              <label className="block text-xs mb-2" style={{ color: 'var(--color-text-secondary)' }}>
                <Settings size={10} className="inline mr-1" /> Fields ({type.fields.length})
              </label>
              <div className="space-y-1.5">
                {type.fields.map(f => (
                  <div key={f.name} className="flex items-center gap-3 p-2 rounded-lg"
                    style={{ background: 'var(--color-bg-tertiary)' }}>
                    <code className="text-xs font-mono font-bold min-w-[100px]">{f.name}</code>
                    <div className="flex-1 flex items-center gap-2">
                      <input type="text" value={f.description} placeholder="Description"
                        onChange={e => onUpdateField(f.name, { description: e.target.value })}
                        className="flex-1 px-2 py-1 rounded border text-xs"
                        style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)', color: 'var(--color-text)' }} />
                      <input type="text" value={f.example} placeholder="Example"
                        onChange={e => onUpdateField(f.name, { example: e.target.value })}
                        className="flex-1 px-2 py-1 rounded border text-xs font-mono"
                        style={{ borderColor: 'var(--color-border)', background: 'var(--color-bg)', color: 'var(--color-text)' }} />
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// --- Helpers ---
function parsePayload(raw: Record<string, unknown>): ParsedPayload {
  const result: ParsedPayload = { app: raw.app as string | undefined, services: [], types: [] };
  const services = (raw.services as Record<string, unknown>[] | undefined) || [];
  for (const s of services) {
    result.services.push({
      name: s.name as string,
      description: s.description as string | undefined,
      operations: ((s.operations as Record<string, unknown>[] | undefined) || []).map(o => ({
        name: o.name as string,
        method: o.method as string | undefined,
        path: o.path as string | undefined,
        description: o.description as string | undefined,
        requestType: o.requestType as string | undefined,
        responseType: o.responseType as string | undefined,
        deprecated: o.deprecated as boolean | undefined,
        optional: o.optional as boolean | undefined,
        pathParams: ((o.pathParams as Record<string, unknown>[] | undefined) || []).map(p => ({
          name: p.name as string,
          type: p.type as string | undefined,
          description: p.description as string | undefined,
          example: p.example as string | undefined,
        })),
      })),
    });
  }
  const types = (raw.types as Record<string, unknown>[] | undefined) || [];
  for (const t of types) {
    result.types.push({
      name: t.name as string,
      type: t.type as string | undefined,
      description: t.description as string | undefined,
      fields: ((t.fields as Record<string, unknown>[] | undefined) || []).map(f => ({
        name: f.name as string,
        type: f.type as string | undefined,
        description: f.description as string | undefined,
        example: f.example as string | undefined,
      })),
      enumConstants: ((t.enumConstants as Record<string, unknown>[] | undefined) || []).map(e => ({
        name: e.name as string,
        value: e.value as string | undefined,
      })),
    });
  }
  return result;
}

function buildEditableServices(parsed: ParsedService[], additional: ServiceAdditionalView[]): EditableService[] {
  const addMap = new Map(additional.map(a => [a.name, a]));
  return parsed.map(s => {
    const add = addMap.get(s.name);
    return {
      name: s.name,
      enabled: add?.enabled ?? true,
      description: add?.description || s.description || '',
        operations: s.operations.map(op => {
          const opAdd = add?.operation_additional?.find(o => o.name === op.name);
          return {
            name: op.name,
            enabled: opAdd?.enabled ?? true,
            method: op.method,
            path: op.path,
            description: opAdd?.description || op.description || op.name,
            example: opAdd?.example || '',
            needAuth: opAdd?.need_auth ?? false,
            deprecated: op.deprecated,
            pathParams: op.pathParams.map(pp => {
              const ppAdd = opAdd?.path_param_additional?.find(p => p.name === pp.name);
              return {
                name: pp.name,
                type: pp.type,
                description: ppAdd?.description || pp.description || '',
                example: ppAdd?.example || pp.example || '',
              };
            }),
          };
        }),
    };
  });
}

function buildEditableTypes(parsed: ParsedType[], additional: TypeAdditionalView[]): EditableType[] {
  const addMap = new Map(additional.map(a => [a.name, a]));
  return parsed.map(t => {
    const add = addMap.get(t.name);
    return {
      name: t.name,
      description: add?.description || t.description || '',
      fields: t.fields.map(f => {
        const fAdd = add?.field_additional?.find(ff => ff.name === f.name);
        return {
          name: f.name,
          description: fAdd?.description || f.description || '',
          example: fAdd?.example || f.example || '',
        };
      }),
    };
  });
}
