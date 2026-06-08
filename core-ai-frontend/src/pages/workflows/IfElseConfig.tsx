import { type CSSProperties } from 'react';
import type { Edge } from '@xyflow/react';
import { Plus, X } from 'lucide-react';
import type { WorkflowNodeData, WorkflowRFNode } from './graph';
import {
  OPERATORS, isUnary, availableVariables, outEdges,
  EdgeSelect, widgetInput, smallBtn, iconBtnSmall,
} from './configWidgets';

interface Condition { selector: string; operator: string; value: string; }
interface Case { logic: string; conditions: Condition[]; edge_id: string; }
interface IfElseCfg { cases: Case[]; else_edge_id: string; }

interface Props {
  node: WorkflowRFNode;
  nodes: WorkflowRFNode[];
  edges: Edge[];
  onChange: (partial: Partial<WorkflowNodeData>) => void;
}

const emptyCondition: Condition = { selector: '', operator: 'eq', value: '' };

/** Form-based IF_ELSE editor: cases of conditions, each routed to a real out-edge picked by target name. The
 *  config JSON the backend reads is rebuilt on every edit; the user never sees it. */
export default function IfElseConfig({ node, nodes, edges, onChange }: Props) {
  const cfg = normalize(node.data.config);
  const vars = availableVariables(nodes, node.id);
  const branches = outEdges(edges, node.id);

  const commit = (next: IfElseCfg) => onChange({ config: { ...node.data.config, ...(next as unknown as Record<string, unknown>) } });
  const patchCase = (ci: number, patch: Partial<Case>) =>
    commit({ ...cfg, cases: cfg.cases.map((c, i) => (i === ci ? { ...c, ...patch } : c)) });
  const patchCondition = (ci: number, ki: number, patch: Partial<Condition>) =>
    patchCase(ci, { conditions: cfg.cases[ci].conditions.map((c, i) => (i === ki ? { ...c, ...patch } : c)) });

  if (branches.length === 0) {
    return <div style={hint}>Draw edges from this node to its branch targets first, then define the conditions here.</div>;
  }

  return (
    <div>
      {cfg.cases.map((c, ci) => (
        <div key={ci} style={caseCard}>
          <div style={{ display: 'flex', alignItems: 'center', marginBottom: 8 }}>
            <span style={{ fontSize: 11, fontWeight: 700, color: 'var(--color-text-secondary)' }}>IF (case {ci + 1})</span>
            <div style={{ flex: 1 }} />
            <button onClick={() => commit({ ...cfg, cases: cfg.cases.filter((_, i) => i !== ci) })} style={iconBtnSmall} title="Remove branch"><X size={13} /></button>
          </div>

          {c.conditions.map((cond, ki) => {
            return (
              <div key={ki} style={condRow}>
                <select value={cond.selector} onChange={(e) => patchCondition(ci, ki, { selector: e.target.value })} style={{ ...widgetInput, flex: 1, minWidth: 0 }}>
                  <option value="">— variable —</option>
                  {vars.map((v) => <option key={v.selector} value={v.selector}>{v.label}</option>)}
                </select>
                <select value={cond.operator} onChange={(e) => patchCondition(ci, ki, { operator: e.target.value })} style={{ ...widgetInput, width: 96 }}>
                  {OPERATORS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                </select>
                {!isUnary(cond.operator) && (
                  <input placeholder="value" value={cond.value} onChange={(e) => patchCondition(ci, ki, { value: e.target.value })} style={{ ...widgetInput, width: 80 }} />
                )}
                <button onClick={() => patchCase(ci, { conditions: c.conditions.filter((_, i) => i !== ki) })} style={iconBtnSmall} title="Remove condition"><X size={12} /></button>
              </div>
            );
          })}

          <div style={{ display: 'flex', alignItems: 'center', gap: 8, margin: '6px 0' }}>
            <button onClick={() => patchCase(ci, { conditions: [...c.conditions, { ...emptyCondition }] })} style={smallBtn}><Plus size={12} /> condition</button>
            {c.conditions.length > 1 && (
              <select value={c.logic} onChange={(e) => patchCase(ci, { logic: e.target.value })} style={{ ...widgetInput, width: 72 }}>
                <option value="and">match all</option>
                <option value="or">match any</option>
              </select>
            )}
          </div>

          <div style={thenRow}>
            <span style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>then go to</span>
            <EdgeSelect edges={branches} nodes={nodes} value={c.edge_id} onChange={(edgeId) => patchCase(ci, { edge_id: edgeId })} />
          </div>
        </div>
      ))}

      <button onClick={() => commit({ ...cfg, cases: [...cfg.cases, { logic: 'and', conditions: [{ ...emptyCondition }], edge_id: '' }] })} style={{ ...smallBtn, marginBottom: 12 }}>
        <Plus size={13} /> Add branch
      </button>

      <div style={thenRow}>
        <span style={{ fontSize: 12, color: 'var(--color-text-secondary)' }}>else go to</span>
        <EdgeSelect edges={branches} nodes={nodes} value={cfg.else_edge_id} onChange={(edgeId) => commit({ ...cfg, else_edge_id: edgeId })} />
      </div>
    </div>
  );
}

function normalize(config: Record<string, unknown>): IfElseCfg {
  const rawCases = Array.isArray(config.cases) ? (config.cases as Partial<Case>[]) : [];
  return {
    cases: rawCases.map((c) => ({
      logic: c.logic === 'or' ? 'or' : 'and',
      conditions: (Array.isArray(c.conditions) ? c.conditions : []).map((cond: Partial<Condition>) => ({
        selector: typeof cond.selector === 'string' ? cond.selector : 'sys.input',
        operator: typeof cond.operator === 'string' ? cond.operator : 'eq',
        value: cond.value == null ? '' : String(cond.value),
      })),
      edge_id: typeof c.edge_id === 'string' ? c.edge_id : '',
    })),
    else_edge_id: typeof config.else_edge_id === 'string' ? config.else_edge_id : '',
  };
}

const caseCard: CSSProperties = { border: '1px solid var(--color-border)', borderRadius: 8, padding: 10, marginBottom: 10 };
const condRow: CSSProperties = { display: 'flex', alignItems: 'center', gap: 5, marginBottom: 6 };
const thenRow: CSSProperties = { display: 'flex', alignItems: 'center', gap: 8 };
const hint: CSSProperties = { fontSize: 12, color: 'var(--color-text-secondary)', lineHeight: 1.5, padding: '8px 0' };
