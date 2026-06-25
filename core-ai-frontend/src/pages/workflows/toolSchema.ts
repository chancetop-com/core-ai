// A declared parameter from a tool's input schema — drives auto-filled rows so users don't type param names.
export interface ParamSpec { name: string; type: string; required: boolean; description?: string }

// Parse a tool JSON-schema string into its declared params. Tolerant: returns [] on anything unexpected.
export function parseToolSchema(schema?: string): ParamSpec[] {
  if (!schema || !schema.trim()) return [];
  try {
    const o = JSON.parse(schema) as { properties?: Record<string, { type?: unknown; description?: unknown }>; required?: unknown };
    if (!o.properties || typeof o.properties !== 'object') return [];
    const required = new Set(Array.isArray(o.required) ? o.required.map(String) : []);
    return Object.entries(o.properties).map(([name, def]) => ({
      name,
      type: schemaType(def?.type),
      required: required.has(name),
      description: typeof def?.description === 'string' ? def.description : undefined,
    }));
  } catch {
    return [];
  }
}
export const parseMcpSchema = parseToolSchema;

function schemaType(t: unknown): string {
  if (typeof t === 'string') return t;
  if (Array.isArray(t) && typeof t[0] === 'string') return t[0];
  return 'any';
}
