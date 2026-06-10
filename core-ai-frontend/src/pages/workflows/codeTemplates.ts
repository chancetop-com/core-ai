import { DEFAULT_CODE } from './graph';

/** Starter templates for the CODE node. All follow the `result` contract: assign `result` and it becomes the
 *  node output; print() is debug-only. Picking one replaces empty code and appends to non-empty code. */
export interface CodeTemplate { key: string; label: string; code: string; }

export const CODE_TEMPLATES: CodeTemplate[] = [
  { key: 'basic', label: 'Basic — read inputs, set result', code: DEFAULT_CODE },
  {
    key: 'transform',
    label: 'Transform JSON',
    code: `# Reshape an upstream object (map inputs["data"] to a node output).
data = inputs.get("data") or {}
result = {
    "id": data.get("id"),
    "name": str(data.get("name", "")).strip().title(),
    "tags": [t for t in data.get("tags", []) if t],
}
`,
  },
  {
    key: 'fetch',
    label: 'Fetch a URL / artifact file',
    code: `# Download a file by URL — e.g. map inputs["url"] to an upstream agent's artifacts.0.url.
import urllib.request

with urllib.request.urlopen(inputs["url"], timeout=30) as resp:
    body = resp.read()
print("fetched", len(body), "bytes")  # debug only
result = {"size": len(body), "head": body[:200].decode("utf-8", "replace")}
`,
  },
  {
    key: 'regex',
    label: 'Regex extract',
    code: `# Pull structured values out of free text (map inputs["text"]).
import re

text = str(inputs.get("text", ""))
emails = re.findall(r"[\\w.+-]+@[\\w-]+\\.[\\w.]+", text)
result = {"emails": emails, "count": len(emails)}
`,
  },
  {
    key: 'aggregate',
    label: 'Aggregate a list',
    code: `# Summarize a list of objects (map inputs["items"] to an upstream array).
items = inputs.get("items") or []
total = sum(float(i.get("amount", 0)) for i in items)
result = {"count": len(items), "total": total, "average": total / len(items) if items else 0}
`,
  },
];

/** Replace empty code with the template; append to non-empty code (never silently discard user work). */
export function applyTemplate(current: string, template: string): string {
  if (!current.trim()) return template;
  return `${current.replace(/\s+$/, '')}\n\n${template}`;
}
