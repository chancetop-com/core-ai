import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Save, Database, Trash2, Code, ChevronDown, ChevronRight, Loader2, Sparkles, Table } from 'lucide-react';
import { api } from '../../api/client';
import type { SchemaFieldView } from '../../api/client';

const JSON_SCHEMA_PLACEHOLDER = `{
  "type": "object",
  "properties": {
    "sentiment": {
      "type": "string",
      "description": "Sentiment label"
    },
    "score": {
      "type": "number",
      "description": "Confidence score 0-100"
    },
    "is_positive": {
      "type": "boolean",
      "description": "Whether sentiment is positive"
    }
  }
}`;

const JAVA_CLASS_PLACEHOLDER = `public class SentimentResult {
    @CoreAiParameter(description = "sentiment label")
    public String sentiment;

    @CoreAiParameter(description = "confidence 0-100")
    public int confidence;

    public List<String> keywords;
}`;

function jsonSchemaTypeToFieldType(jsType: string): string {
  switch (jsType) {
    case 'number': case 'integer': return 'NUMBER';
    case 'boolean': return 'BOOLEAN';
    default: return 'STRING';
  }
}

function fieldTypeToJsonSchemaType(t: string): string {
  switch (t) {
    case 'NUMBER': return 'number';
    case 'BOOLEAN': return 'boolean';
    default: return 'string';
  }
}

function parseJsonSchemaToFields(raw: string): SchemaFieldView[] {
  let schema: Record<string, unknown>;
  try { schema = JSON.parse(raw); } catch { return []; }

  const properties = schema.properties as Record<string, { type?: string; description?: string; title?: string }> | undefined;
  if (!properties || typeof properties !== 'object') return [];

  return Object.entries(properties).map(([name, prop]) => ({
    name,
    type: jsonSchemaTypeToFieldType(prop.type || 'string'),
    label: prop.description || prop.title || '',
  }));
}

function fieldsToJsonSchema(fields: SchemaFieldView[]): string {
  const properties: Record<string, { type: string; description?: string }> = {};
  for (const f of fields) {
    const prop: { type: string; description?: string } = {
      type: fieldTypeToJsonSchemaType(f.type),
    };
    if (f.label) prop.description = f.label;
    properties[f.name] = prop;
  }
  return JSON.stringify({ type: 'object', properties }, null, 2);
}

export default function DatasetEditor() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const isNew = id === 'new';

  const [loading, setLoading] = useState(!isNew);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState('');

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [schema, setSchema] = useState<SchemaFieldView[]>([]);

  // Schema editor state (collapsible, two-tab like ResponseSchemaEditor)
  const [schemaOpen, setSchemaOpen] = useState(false);
  const [schemaMode, setSchemaMode] = useState<'json' | 'java'>('json');
  const [jsonText, setJsonText] = useState('');
  const [jsonError, setJsonError] = useState('');
  const [javaCode, setJavaCode] = useState('');
    const [converting, setConverting] = useState(false);
  const [generatingSchema, setGeneratingSchema] = useState(false);

  useEffect(() => {
    if (isNew) return;
    setLoading(true);
    api.datasets.get(id!)
      .then(d => {
        setName(d.name);
        setDescription(d.description || '');
        const fields = d.schema || [];
        setSchema(fields);
        // Reconstruct JSON schema from fields for the JSON Schema tab
        if (fields.length > 0) {
          setJsonText(fieldsToJsonSchema(fields));
        }
      })
      .catch(console.error)
      .finally(() => setLoading(false));
  }, [id, isNew]);

  const handleJsonChange = (raw: string) => {
    setJsonText(raw);
    if (!raw.trim()) {
      setJsonError('');
      setSchema([]);
      return;
    }
    try {
      JSON.parse(raw);
      setJsonError('');
      const fields = parseJsonSchemaToFields(raw);
      setSchema(fields);
    } catch (e) {
      setJsonError(e instanceof Error ? e.message : 'Invalid JSON');
    }
  };

  const handleConvertJava = async () => {
    if (!javaCode.trim()) return;
    setConverting(true);
    try {
      const res = await api.agents.javaToSchema(javaCode);
      if (res.error) {
        setJsonError(res.error);
      } else if (res.schema) {
        const formatted = JSON.stringify(JSON.parse(res.schema), null, 2);
        setJsonText(formatted);
        setSchema(parseJsonSchemaToFields(res.schema));
        setSchemaMode('json');
        setJsonError('');
      }
    } catch (e) {
      setJsonError(e instanceof Error ? e.message : 'Convert failed');
    } finally {
      setConverting(false);
    }
  };

  const handleGenerateSchema = async () => {
    setGeneratingSchema(true);
    try {
      const res = await api.utils.generate({
        system_prompt: `You are a JSON Schema generator for structured data extraction. Generate a valid JSON Schema object based on the dataset name and description.

Rules:
- Always include "type": "object" and "properties"
- Each property must have "type" and "description"
- Use common types: string, number, boolean, array
- Be comprehensive but not overly detailed
- Keep field names snake_case

Return ONLY the JSON Schema object without any markdown formatting, code blocks, or commentary.`,
        user_prompt: `Generate a JSON Schema for a dataset with the following details:

Name: ${name || 'N/A'}
Description: ${description || 'N/A'}`,
      });
      if (res.output) {
        try {
          const schemaObj = JSON.parse(res.output.trim());
          if (schemaObj.type === 'object' && schemaObj.properties) {
            const formatted = JSON.stringify(schemaObj, null, 2);
            setJsonText(formatted);
            setSchema(parseJsonSchemaToFields(JSON.stringify(schemaObj)));
            setSchemaMode('json');
            setSchemaOpen(true);
            setJsonError('');
          } else {
            setJsonError('Generated output is not a valid JSON Schema object');
          }
        } catch {
          setJsonError('Failed to parse generated schema');
        }
      }
    } catch (e) {
      setJsonError(e instanceof Error ? e.message : 'Generate failed');
    } finally {
      setGeneratingSchema(false);
    }
  };

  const handleSave = async () => {
    if (!name.trim()) {
      setSaveError('Name is required');
      return;
    }

    setSaving(true);
    setSaveError('');
    try {
      if (isNew) {
        const created = await api.datasets.create({ name, description, schema });
        navigate(`/datasets/${created.id}`, { replace: true });
      } else {
        await api.datasets.update(id!, { name, description, schema });
        navigate(`/datasets/${id}`, { replace: true });
      }
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!id || isNew || !confirm('Delete this dataset and all its records?')) return;
    await api.datasets.delete(id);
    navigate('/datasets');
  };

  const inputStyle = {
    background: 'var(--color-bg-tertiary)',
    borderColor: 'var(--color-border)',
    color: 'var(--color-text)',
  };

  const hasSchema = schema.length > 0;

  if (loading) return <div className="p-6" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>;

  return (
    <div className="p-6">
      {/* Header */}
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <button onClick={() => navigate('/datasets')}
            className="flex items-center gap-1 text-sm cursor-pointer"
            style={{ color: 'var(--color-primary)' }}>
            <ArrowLeft size={16} /> Back to Datasets
          </button>
          <Database size={20} style={{ color: 'var(--color-primary)' }} />
          <h1 className="text-xl font-semibold">{isNew ? 'New Dataset' : name}</h1>
        </div>
        <div className="flex items-center gap-2">
          {!isNew && (
            <>
              <button onClick={() => navigate(`/datasets/${id}/records`)}
                className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer"
                style={{ borderColor: 'var(--color-border)', color: 'var(--color-primary)' }}>
                <Table size={14} /> Records
              </button>
              <button onClick={handleDelete}
                className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer"
                style={{ borderColor: 'var(--color-border)', color: 'var(--color-error)' }}>
                <Trash2 size={14} /> Delete
              </button>
            </>
          )}
          <button onClick={handleSave} disabled={saving}
            className="flex items-center gap-1 px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
            style={{ background: 'var(--color-primary)' }}>
            <Save size={14} /> {saving ? 'Saving...' : 'Save'}
          </button>
        </div>
      </div>

      {saveError && (
        <div className="mb-4 p-3 rounded-lg text-sm" style={{ background: 'rgba(239,68,68,0.1)', color: 'var(--color-error)' }}>
          {saveError}
        </div>
      )}

      {/* Basic info */}
      <div className="rounded-xl border p-4 mb-4"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <div className="mb-3">
          <label className="text-xs mb-1 block" style={{ color: 'var(--color-text-secondary)' }}>Name *</label>
          <input value={name} onChange={e => setName(e.target.value)}
            className="w-full px-3 py-1.5 rounded-lg border text-sm" style={inputStyle}
            placeholder="e.g. lead_scores" />
        </div>
        <div>
          <label className="text-xs mb-1 block" style={{ color: 'var(--color-text-secondary)' }}>Description</label>
          <input value={description} onChange={e => setDescription(e.target.value)}
            className="w-full px-3 py-1.5 rounded-lg border text-sm" style={inputStyle}
            placeholder="Describe what this dataset contains" />
        </div>
      </div>

      {/* Schema editor — collapsible, same UX as agent's ResponseSchemaEditor */}
      <div className="rounded-xl border mb-4"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <button
          className="w-full flex items-center justify-between p-4 text-sm font-medium cursor-pointer hover:bg-[var(--color-bg-tertiary)] rounded-xl"
          onClick={() => setSchemaOpen(!schemaOpen)}>
          <div className="flex items-center gap-2">
            <Code size={14} style={{ color: 'var(--color-text-secondary)' }} />
            <span>Schema (optional)</span>
            {!schemaOpen && hasSchema && (
              <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                {schema.length} field{schema.length !== 1 ? 's' : ''}
              </span>
            )}
          </div>
          {schemaOpen ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
        </button>
        {schemaOpen && (
          <div className="px-4 pb-4 border-t" style={{ borderColor: 'var(--color-border)' }}>
            <div className="flex items-center gap-1 pt-3 mb-3">
              {(['json', 'java'] as const).map(m => (
                <button key={m} onClick={() => setSchemaMode(m)}
                  className="text-xs px-2 py-1 rounded cursor-pointer"
                  style={{
                    background: schemaMode === m ? 'var(--color-primary)' : 'var(--color-bg-tertiary)',
                    color: schemaMode === m ? 'white' : 'var(--color-text-secondary)',
                  }}>
                  {m === 'json' ? 'JSON Schema' : 'Java Class'}
                </button>
              ))}
            </div>

            {schemaMode === 'json' ? (
              <>
                <textarea value={jsonText} onChange={e => handleJsonChange(e.target.value)}
                  rows={12}
                  className="w-full px-3 py-2 rounded-lg border text-sm font-mono outline-none resize-y"
                  style={{ ...inputStyle, borderColor: jsonError ? 'var(--color-error)' : inputStyle.borderColor }}
                  placeholder={JSON_SCHEMA_PLACEHOLDER} />
                {jsonError && <p className="text-xs mt-1" style={{ color: 'var(--color-error)' }}>{jsonError}</p>}
                <div className="flex items-center gap-2 mt-2">
                  <button onClick={handleGenerateSchema} disabled={generatingSchema}
                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-white cursor-pointer disabled:opacity-50"
                    style={{ background: 'var(--color-primary)' }}>
                    {generatingSchema ? <><Loader2 size={12} className="animate-spin" /> Generating...</> : <><Sparkles size={12} /> AI Generate</>}
                  </button>
                  <span className="text-[10px]" style={{ color: 'var(--color-text-secondary)' }}>
                    Uses LLM to generate a schema from name &amp; description
                  </span>
                </div>
                <p className="text-xs mt-2" style={{ color: 'var(--color-text-secondary)' }}>
                  Optional. Define a JSON Schema to extract structured fields from agent output.
                  Leave empty to save raw output as-is for later analysis.
                </p>
              </>
            ) : (
              <>
                <textarea value={javaCode} onChange={e => setJavaCode(e.target.value)}
                  rows={10}
                  className="w-full px-3 py-2 rounded-lg border text-sm font-mono outline-none resize-y"
                  style={inputStyle}
                  placeholder={JAVA_CLASS_PLACEHOLDER} />
                <div className="flex items-center gap-2 mt-2">
                  <button onClick={handleConvertJava} disabled={!javaCode.trim() || converting}
                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-white cursor-pointer disabled:opacity-50"
                    style={{ background: 'var(--color-primary)' }}>
                    {converting ? <><Loader2 size={12} className="animate-spin" /> Converting...</> : 'Convert to JSON Schema'}
                  </button>
                  <span className="text-[10px]" style={{ color: 'var(--color-text-secondary)' }}>
                    Uses LLM to convert Java class to JSON Schema
                  </span>
                </div>
              </>
            )}
          </div>
        )}
      </div>

      {/* Record structure info */}
      <div className="rounded-xl border p-4 mt-4 text-xs"
        style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
        <p className="font-medium mb-2" style={{ color: 'var(--color-text)' }}>Record Structure</p>
        <p className="mb-2" style={{ color: 'var(--color-text-secondary)' }}>
          Each record in this dataset contains the following fields:
        </p>
        <table className="w-full border-collapse" style={{ color: 'var(--color-text-secondary)' }}>
          <thead>
            <tr className="border-b" style={{ borderColor: 'var(--color-border)' }}>
              <th className="text-left py-1 pr-3 font-medium">Field</th>
              <th className="text-left py-1 pr-3 font-medium">Type</th>
              <th className="text-left py-1 font-medium">Description</th>
            </tr>
          </thead>
          <tbody>
            <tr className="border-b" style={{ borderColor: 'var(--color-border)' }}>
              <td className="py-1 pr-3 font-mono">id</td>
              <td className="py-1 pr-3">string</td>
              <td className="py-1">Unique record ID</td>
            </tr>
            <tr className="border-b" style={{ borderColor: 'var(--color-border)' }}>
              <td className="py-1 pr-3 font-mono">run_id</td>
              <td className="py-1 pr-3">string</td>
              <td className="py-1">The agent run that produced this record</td>
            </tr>
            <tr className="border-b" style={{ borderColor: 'var(--color-border)' }}>
              <td className="py-1 pr-3 font-mono">agent_id</td>
              <td className="py-1 pr-3">string</td>
              <td className="py-1">The agent definition ID</td>
            </tr>
            <tr className="border-b" style={{ borderColor: 'var(--color-border)' }}>
              <td className="py-1 pr-3 font-mono">run_started_at</td>
              <td className="py-1 pr-3">datetime</td>
              <td className="py-1">When the agent run started</td>
            </tr>
            <tr className="border-b" style={{ borderColor: 'var(--color-border)' }}>
              <td className="py-1 pr-3 font-mono">user_id</td>
              <td className="py-1 pr-3">string</td>
              <td className="py-1">The user who created the record</td>
            </tr>
            <tr className="border-b" style={{ borderColor: 'var(--color-border)' }}>
              <td className="py-1 pr-3 font-mono">created_by</td>
              <td className="py-1 pr-3">string</td>
              <td className="py-1">Who created the record (agent or user)</td>
            </tr>
            <tr className="border-b" style={{ borderColor: 'var(--color-border)' }}>
              <td className="py-1 pr-3 font-mono">created_at</td>
              <td className="py-1 pr-3">datetime</td>
              <td className="py-1">When the record was created</td>
            </tr>
            <tr className="border-b" style={{ borderColor: 'var(--color-border)' }}>
              <td className="py-1 pr-3 font-mono">updated_at</td>
              <td className="py-1 pr-3">datetime</td>
              <td className="py-1">Last update timestamp</td>
            </tr>
            <tr className="border-b" style={{ borderColor: 'var(--color-border)' }}>
              <td className="py-1 pr-3 font-mono">updated_by</td>
              <td className="py-1 pr-3">string</td>
              <td className="py-1">Who last updated the record</td>
            </tr>
            <tr>
              <td className="py-1 pr-3 font-mono">data</td>
              <td className="py-1 pr-3">object</td>
              <td className="py-1">{schema.length > 0 ? 'Structured data matching the schema' : 'Raw output stored as {"output": "..."}'}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  );
}
