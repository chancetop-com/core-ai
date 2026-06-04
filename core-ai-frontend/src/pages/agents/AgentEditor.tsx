import { useEffect, useState, useRef, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Save, Trash2, Upload, Play, Copy, Check, Code, Download, FileUp, Maximize2, Minimize2, Square, Loader2, ChevronDown, ChevronRight, X, Wrench, Search, Link, Trash, Users, Sparkles, Plus, Database, Braces, SlidersHorizontal } from 'lucide-react';
import { api } from '../../api/client';
import type { AgentDefinition, SandboxConfig, SystemPrompt, AgentRun, AgentRunDetail, ToolRegistryView, ToolRef, SkillDefinition, ApiAppView, ApiServiceView, McpToolInfo, AgentDatasetConfig } from '../../api/client';
import { sessionApi } from '../../api/session';
import type { SseTextChunkEvent, SseErrorEvent } from '../../api/session';
import KeyValueVariablesEditor from '../../components/KeyValueVariablesEditor';
import StatusBadge from '../../components/StatusBadge';

const NEW_AGENT_SKELETON: AgentDefinition = {
  id: '', name: '', description: '', system_prompt: '', system_prompt_id: '',
  model: '', multi_modal_model: '', temperature: 0.7, max_turns: 100, timeout_seconds: 600,
  tools: [], input_template: '', variables: {},
  system_default: false, type: 'AGENT', response_schema: null,
  created_by: '', status: 'DRAFT', published_at: '', created_at: '', updated_at: '',
  subagent_ids: [], skill_ids: [],
  dataset_config: [],
};

export default function AgentEditor() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const isNew = id === 'new';

  const [agent, setAgent] = useState<AgentDefinition | null>(isNew ? { ...NEW_AGENT_SKELETON } : null);
  const [loading, setLoading] = useState(!isNew);
  const [saving, setSaving] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [saveError, setSaveError] = useState('');
  const [publishSuccess, setPublishSuccess] = useState(false);
  const [promptExpanded, setPromptExpanded] = useState(false);
  const [generatingPrompt, setGeneratingPrompt] = useState(false);

  // system prompts for dropdown
  const [systemPrompts, setSystemPrompts] = useState<SystemPrompt[]>([]);

  // tools
  const [allTools, setAllTools] = useState<ToolRegistryView[]>([]);

  // subagents
  const [allAgents, setAllAgents] = useState<AgentDefinition[]>([]);
  const [subAgentSearch, setSubAgentSearch] = useState('');
  const [subAgentFocused, setSubAgentFocused] = useState(false);

  // skills
  const [allSkills, setAllSkills] = useState<SkillDefinition[]>([]);
  const [skillSearch, setSkillSearch] = useState('');
  const [skillFocused, setSkillFocused] = useState(false);

  // service-api picker
  const [apiApps, setApiApps] = useState<ApiAppView[]>([]);
  const [apiAppsLoaded, setApiAppsLoaded] = useState(false);
  const [expandedApiApp, setExpandedApiApp] = useState<string | null>(null);
  const [apiAppServices, setApiAppServices] = useState<Record<string, ApiServiceView[]>>({});
  const [showApiPicker, setShowApiPicker] = useState(false);
  const [expandedApiService, setExpandedApiService] = useState<string | null>(null);

  // Output datasets
  const [allDatasets, setAllDatasets] = useState<{ id: string; name: string }[]>([]);
  const [datasetsLoaded, setDatasetsLoaded] = useState(false);
  const [datasetSearch, setDatasetSearch] = useState('');
  const [datasetFocused, setDatasetFocused] = useState(false);
  const [datasetsOpen, setDatasetsOpen] = useState(false);
  const [inputTemplateOpen, setInputTemplateOpen] = useState(false);
  const [variablesOpen, setVariablesOpen] = useState(false);
  const [modelConfigOpen, setModelConfigOpen] = useState(true);

  // multi-modal model toggle
  const [showMultiModalModel, setShowMultiModalModel] = useState(false);

  // When agent loads (edit mode), sync the toggle if multi_modal_model is set
  useEffect(() => {
    if (agent?.multi_modal_model) setShowMultiModalModel(true);
  }, [agent?.id]);

  // mcp server tool picker - expand individual MCP servers to select specific tools
  const [mcpServerTools, setMcpServerTools] = useState<Record<string, McpToolInfo[]>>({});
  const [expandedMcpServer, setExpandedMcpServer] = useState<string | null>(null);
  const [showMcp, setShowMcp] = useState(false);

  // test panel
  const [testInput, setTestInput] = useState('');
  const [testOutput, setTestOutput] = useState('');
  const [testing, setTesting] = useState(false);
  const testControllerRef = useRef<AbortController | null>(null);
  const testOutputRef = useRef('');

  // image attachments for LLM_CALL
  const [imageAttachments, setImageAttachments] = useState<{ url?: string; data?: string; mediaType?: string; preview: string }[]>([]);
  const [showImageUrlInput, setShowImageUrlInput] = useState(false);
  const [imageUrlValue, setImageUrlValue] = useState('');
  const [showBase64Input, setShowBase64Input] = useState(false);
  const [base64Value, setBase64Value] = useState('');
  const [base64MediaType, setBase64MediaType] = useState('image/png');
  const imageFileRef = useRef<HTMLInputElement>(null);
  const importFileRef = useRef<HTMLInputElement>(null);
  const [showImportConfirm, setShowImportConfirm] = useState(false);
  const [pendingImportData, setPendingImportData] = useState<Record<string, unknown> | null>(null);

  // runs
  const [runs, setRuns] = useState<AgentRun[]>([]);
  const [runsLoading, setRunsLoading] = useState(false);
  const [expandedRunId, setExpandedRunId] = useState<string | null>(null);
  const [expandedRunDetail, setExpandedRunDetail] = useState<AgentRunDetail | null>(null);
  const [modalRunDetail, setModalRunDetail] = useState<AgentRunDetail | null>(null);

  useEffect(() => {
    if (!id) return;

    // Load auxiliary data in both create and edit modes
    api.systemPrompts.list(0, 100).then(setSystemPrompts).catch(console.error);
    api.tools.list().then(res => setAllTools(res.tools || [])).catch(console.error);
    api.agents.list().then(res => {
      const published = (res.agents || []).filter(a =>
        a.id !== id &&
        (a.status === 'PUBLISHED' || a.status === 'DRAFT') &&
        (a.type === 'AGENT' || a.type === 'local')
      );
      setAllAgents(published);
    }).catch(console.error);
    api.skills.list().then(res => setAllSkills(res.skills || [])).catch(console.error);

    if (isNew) {
      setLoading(false);
      return;
    }

    api.agents.get(id).then(data => setAgent(data)).catch(console.error).finally(() => setLoading(false));
    setRunsLoading(true);
    api.agents.runs(id).then(res => setRuns(res.runs || [])).catch(console.error).finally(() => setRunsLoading(false));
  }, [id, isNew]);

  const loadRuns = useCallback(async () => {
    if (!id || isNew) return;
    setRunsLoading(true);
    try {
      const res = await api.agents.runs(id);
      setRuns(res.runs || []);
    } finally {
      setRunsLoading(false);
    }
  }, [id, isNew]);

  if (loading) return <div className="p-6" style={{ color: 'var(--color-text-secondary)' }}>Loading...</div>;
  if (!agent) return <div className="p-6">Agent not found</div>;

  const update = (field: string, value: unknown) => {
    setAgent({ ...agent, [field]: value } as AgentDefinition);
  };

  const datasetConfig = agent.dataset_config || [];

  const addDatasetConfig = (datasetId: string) => {
    const current = agent.dataset_config || [];
    if (current.some(c => c.dataset_id === datasetId)) return;
    const entry: AgentDatasetConfig = { dataset_id: datasetId, permission: 'READ' };
    setAgent({ ...agent, dataset_config: [...current, entry] } as AgentDefinition);
    setDatasetSearch('');
  };

  const removeDatasetConfig = (datasetId: string) => {
    setAgent({
      ...agent,
      dataset_config: (agent.dataset_config || []).filter(c => c.dataset_id !== datasetId),
    } as AgentDefinition);
  };

  const changeDatasetPermission = (datasetId: string, permission: 'READ' | 'WRITE' | 'FULL') => {
    const current = agent.dataset_config || [];
    setAgent({
      ...agent,
      dataset_config: current.map(c => c.dataset_id === datasetId
        ? { ...c, permission, is_output: permission === 'READ' ? false : c.is_output }
        : c),
    } as AgentDefinition);
  };

  const setOutputDataset = (datasetId: string) => {
    const current = agent.dataset_config || [];
    setAgent({
      ...agent,
      dataset_config: current.map(c => ({ ...c, is_output: c.dataset_id === datasetId })),
    } as AgentDefinition);
  };

  const clearOutputDataset = () => {
    const current = agent.dataset_config || [];
    setAgent({
      ...agent,
      dataset_config: current.map(c => ({ ...c, is_output: false })),
    } as AgentDefinition);
  };

  const handleGeneratePrompt = async () => {
    if (!agent.name && !agent.description) return;
    setGeneratingPrompt(true);
    try {
      const res = await api.utils.generate({
        system_prompt: `You are a system prompt generator for AI agents. Your task is to generate effective, well-structured system prompts based on the agent's name and description.

Generate a system prompt that:
- Defines the agent's role and personality clearly
- Specifies how the agent should behave and respond
- Includes any relevant constraints or guidelines
- Uses clear, actionable language
- Is concise but comprehensive

Return ONLY the system prompt text without any additional commentary, markdown formatting, or labels.`,
        user_prompt: `Generate a system prompt for an AI agent with the following details:

Name: ${agent.name || 'N/A'}
Description: ${agent.description || 'N/A'}

The system prompt should define how this agent behaves, its capabilities, and its constraints.`,
      });
      if (res.output) {
        update('system_prompt', res.output);
      }
    } catch (e) {
      console.error('Failed to generate system prompt:', e);
    } finally {
      setGeneratingPrompt(false);
    }
  };

  const loadApiApps = async () => {
    if (apiAppsLoaded) return;
    try {
      const res = await api.tools.listApiApps();
      setApiApps(res.apps || []);
      setApiAppsLoaded(true);
    } catch (e) {
      console.error('Failed to load API apps:', e);
    }
  };

  const loadDatasets = async () => {
    if (datasetsLoaded) return;
    try {
      const res = await api.datasets.list();
      setAllDatasets((res.datasets || []).map(d => ({ id: d.id, name: d.name })));
      setDatasetsLoaded(true);
    } catch (e) {
      console.error('Failed to load datasets:', e);
    }
  };

  const loadApiAppServices = async (appName: string) => {
    if (apiAppServices[appName]) return;
    try {
      const res = await api.tools.listApiAppServices(appName);
      setApiAppServices(prev => ({ ...prev, [appName]: res.services || [] }));
    } catch (e) {
      console.error('Failed to load services for app:', appName, e);
    }
  };

  const toggleApiApp = (appName: string) => {
    if (expandedApiApp === appName) {
      setExpandedApiApp(null);
    } else {
      setExpandedApiApp(appName);
      loadApiAppServices(appName);
    }
  };

  const addApiAppTool = (appName: string) => {
    const toolId = `api-app:${appName}`;
    if (agent.tools?.some((t: ToolRef) => t.id === toolId)) return;
    // Remove any service/operation-level refs for this app
    const filtered = (agent.tools || []).filter((t: ToolRef) =>
      !t.id.startsWith(`api-service:${appName}:`) && !t.id.startsWith(`api-operation:${appName}:`));
    update('tools', [...filtered, { id: toolId, type: 'API', source: appName }]);
  };

  const addApiServiceTool = (appName: string, serviceName: string) => {
    const toolId = `api-service:${appName}:${serviceName}`;
    if (agent.tools?.some((t: ToolRef) => t.id === toolId)) return;
    // Remove app-level and operation-level refs for this service
    const filtered = (agent.tools || []).filter((t: ToolRef) =>
      t.id !== `api-app:${appName}` && !t.id.startsWith(`api-operation:${appName}:${serviceName}:`));
    update('tools', [...filtered, { id: toolId, type: 'API', source: appName }]);
  };

  const addApiOperationTool = (appName: string, serviceName: string, operationName: string) => {
    const toolId = `api-operation:${appName}:${serviceName}:${operationName}`;
    if (agent.tools?.some((t: ToolRef) => t.id === toolId)) return;
    update('tools', [...(agent.tools || []), { id: toolId, type: 'API', source: appName }]);
  };

  const loadMcpServerTools = async (serverId: string) => {
    if (mcpServerTools[serverId]) return;
    try {
      const res = await api.tools.listMcpServerTools(serverId);
      setMcpServerTools(prev => ({ ...prev, [serverId]: res.tools || [] }));
    } catch (e) {
      console.error('Failed to load MCP server tools:', serverId, e);
    }
  };

  const toggleMcpServer = (serverId: string) => {
    if (expandedMcpServer === serverId) {
      setExpandedMcpServer(null);
    } else {
      setExpandedMcpServer(serverId);
      loadMcpServerTools(serverId);
    }
  };

  const isMcpServerFullySelected = (serverId: string): boolean => {
    return agent.tools?.some((t: ToolRef) => t.id === serverId) || false;
  };

  const addMcpServerAll = (serverId: string) => {
    if (isMcpServerFullySelected(serverId)) return;
    // Remove individual tool refs for this server (composite format: mcp-tool:{serverId}:{toolName})
    const toolPrefix = `mcp-tool:${serverId}:`;
    const filtered = (agent.tools || []).filter((t: ToolRef) =>
      !t.id.startsWith(toolPrefix));
    update('tools', [...filtered, { id: serverId, type: 'MCP' }]);
  };

  const addMcpTool = (serverId: string, toolName: string) => {
    const toolId = `mcp-tool:${serverId}:${toolName}`;
    if (agent.tools?.some((t: ToolRef) => t.id === toolId)) return;
    update('tools', [...(agent.tools || []), { id: toolId, type: 'MCP', source: serverId }]);
  };

  const isMcpToolSelected = (serverId: string, toolName: string): boolean => {
    const toolId = `mcp-tool:${serverId}:${toolName}`;
    return agent.tools?.some((t: ToolRef) => t.id === toolId) || false;
  };

  const removeMcpTool = (toolRef: ToolRef) => {
    update('tools', (agent.tools || []).filter((t: ToolRef) => t.id !== toolRef.id));
  };

  const getMcpToolDisplayName = (toolRef: ToolRef): string => {
    if (toolRef.id.startsWith('mcp-tool:')) {
      const parts = toolRef.id.substring('mcp-tool:'.length).split(':');
      if (parts.length >= 2) {
        const serverId = parts[0];
        const toolName = parts.slice(1).join(':');
        const server = allTools.find(t => t.id === serverId);
        return server ? `${server.name} > ${toolName}` : toolName;
      }
    }
    return toolRef.id;
  };

  const mcpServers = allTools.filter(t => t.type === 'MCP');

  const getApiToolDisplayName = (toolRef: ToolRef): string => {
    if (toolRef.id.startsWith('api-app:')) return toolRef.id.substring('api-app:'.length);
    if (toolRef.id.startsWith('api-operation:')) {
      const parts = toolRef.id.substring('api-operation:'.length).split(':');
      return `${parts[0]} > ${parts[1] || ''} > ${parts[2] || ''}`;
    }
    if (toolRef.id.startsWith('api-service:')) {
      const parts = toolRef.id.substring('api-service:'.length).split(':');
      return `${parts[0]} > ${parts[1] || ''}`;
    }
    return toolRef.id;
  };

  const handleSave = async () => {
    if (!id) return;
    setSaving(true);
    setSaveSuccess(false);
    setSaveError('');

    try {
      if (isNew) {
        const created = await api.agents.create({
          name: agent.name || `New Agent ${new Date().toLocaleString('en-US', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}`,
          description: agent.description,
          type: agent.type,
          system_prompt: agent.system_prompt,
          system_prompt_id: agent.system_prompt_id,
          model: agent.model,
          multi_modal_model: agent.multi_modal_model,
          temperature: agent.temperature,
          max_turns: agent.max_turns,
          timeout_seconds: agent.timeout_seconds,
          tools: agent.tools,
          input_template: agent.input_template,
          variables: agent.variables,
          response_schema: agent.response_schema,
          subagent_ids: agent.subagent_ids,
          skill_ids: agent.skill_ids,
          dataset_config: agent.dataset_config,
          sandbox_config: agent.sandbox_config,
        } as any);
        navigate(`/agents/${created.id}`);
        return;
      }

      const updated = await api.agents.update(id, {
        name: agent.name,
        description: agent.description,
        type: agent.type,
        system_prompt: agent.system_prompt,
        system_prompt_id: agent.system_prompt_id,
        model: agent.model,
        multi_modal_model: agent.multi_modal_model,
        temperature: agent.temperature,
        max_turns: agent.max_turns,
        timeout_seconds: agent.timeout_seconds,
        tools: agent.tools,
        input_template: agent.input_template,
        variables: agent.variables,
        response_schema: agent.response_schema,
          subagent_ids: agent.subagent_ids,
          skill_ids: agent.skill_ids,
          dataset_config: agent.dataset_config,
          sandbox_config: agent.sandbox_config,
        } as any);
        setAgent(updated);
      setSaveSuccess(true);
      setTimeout(() => setSaveSuccess(false), 3000);
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  };

  const handlePublish = async () => {
    if (!id) return;
    setPublishing(true);
    setPublishSuccess(false);
    try {
      const published = await api.agents.publish(id);
      setAgent(published);
      setPublishSuccess(true);
      setTimeout(() => setPublishSuccess(false), 3000);
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'Publish failed');
    } finally {
      setPublishing(false);
    }
  };

  const handleDelete = async () => {
    if (!id) return;
    if (isNew) {
      navigate('/agents');
      return;
    }
    if (!confirm('Delete this agent?')) return;
    await api.agents.delete(id);
    navigate('/agents');
  };

  const handleImageUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      const result = reader.result as string;
      const base64 = result.split(',')[1];
      setImageAttachments(prev => [...prev, { data: base64, mediaType: file.type, preview: result }]);
    };
    reader.readAsDataURL(file);
    e.target.value = '';
  };

  const handleAddImageUrl = () => {
    if (!imageUrlValue.trim()) return;
    setImageAttachments(prev => [...prev, { url: imageUrlValue.trim(), preview: imageUrlValue.trim() }]);
    setImageUrlValue('');
    setShowImageUrlInput(false);
  };

  const handleAddBase64 = () => {
    if (!base64Value.trim()) return;
    const raw = base64Value.trim().replace(/^data:[^;]+;base64,/, '');
    const preview = `data:${base64MediaType};base64,${raw}`;
    setImageAttachments(prev => [...prev, { data: raw, mediaType: base64MediaType, preview }]);
    setBase64Value('');
    setShowBase64Input(false);
  };

  const handleTest = async () => {
    if (!testInput.trim()) return;
    setTesting(true);
    setTestOutput('');
    testOutputRef.current = '';

    // LLM_CALL: use direct API call with attachments
    if (agent.type === 'LLM_CALL' && id) {
      try {
        const attachments = imageAttachments.length > 0
          ? imageAttachments.map(img => img.data
            ? { type: 'IMAGE', data: img.data, media_type: img.mediaType }
            : { type: 'IMAGE', url: img.url })
          : undefined;
        const res = await api.agents.llmCall(id, testInput, attachments as Parameters<typeof api.agents.llmCall>[2]);
        setTestOutput(res.output || '(empty)');
      } catch (e) {
        setTestOutput(`Error: ${e instanceof Error ? e.message : String(e)}`);
      } finally {
        setTesting(false);
      }
      return;
    }

    try {
      // Resolve system prompt
      let systemPrompt = agent.system_prompt || '';
      if (agent.system_prompt_id) {
        const sp = systemPrompts.find(s => s.promptId === agent.system_prompt_id);
        if (sp) systemPrompt = sp.content;
      }

      const config: Record<string, unknown> = { systemPrompt };
      if (agent.model) config.model = agent.model;
      if (agent.temperature != null) config.temperature = agent.temperature;
      if (agent.max_turns) config.maxTurns = agent.max_turns;
      const tools = agent.tools && agent.tools.length > 0 ? agent.tools : undefined;

      const res = await sessionApi.create('', { config, tools });
      const sid = res.sessionId;

      const controller = sessionApi.connectSSE(sid, (event) => {
        if (event.type === 'TEXT_CHUNK' || event.type === 'text_chunk') {
          const chunk = (event as SseTextChunkEvent).content || '';
          testOutputRef.current += chunk;
          setTestOutput(testOutputRef.current);
        } else if (event.type === 'TURN_COMPLETE' || event.type === 'turn_complete') {
          setTesting(false);
          sessionApi.close(sid).catch(() => {});
        } else if (event.type === 'ERROR' || event.type === 'error') {
          const errorEvent = event as SseErrorEvent;
          testOutputRef.current += `\nError: ${errorEvent.message || 'Unknown error'}`;
          setTestOutput(testOutputRef.current);
          setTesting(false);
          sessionApi.close(sid).catch(() => {});
        }
      }, () => { setTesting(false); });
      testControllerRef.current = controller;

      setTimeout(() => {
        sessionApi.sendMessage(sid, testInput, agent.variables || undefined).catch(err => {
          setTestOutput(`Error: ${err}`);
          setTesting(false);
        });
      }, 500);
    } catch (e) {
      setTestOutput(`Error: ${e instanceof Error ? e.message : String(e)}`);
      setTesting(false);
    }
  };

  const handleStopTest = () => {
    testControllerRef.current?.abort();
    testControllerRef.current = null;
    setTesting(false);
  };

  const handleTriggerRun = async () => {
    if (!id || !testInput.trim()) return;
    setTesting(true);
    setTestOutput('');
    try {
      const res = await api.agents.trigger(id, testInput);
      setTestOutput(`Run triggered: ${res.run_id}\nWaiting for result...`);
      // Poll for result
      const poll = async (attempts: number) => {
        if (attempts <= 0) { setTesting(false); return; }
        await new Promise(r => setTimeout(r, 2000));
        try {
          const detail = await api.agents.getRun(res.run_id);
          if (detail.status === 'COMPLETED' || detail.status === 'FAILED' || detail.status === 'ERROR') {
            setTestOutput(detail.output || detail.error || 'No output');
            setTesting(false);
            loadRuns();
          } else {
            poll(attempts - 1);
          }
        } catch { poll(attempts - 1); }
      };
      poll(30);
    } catch (e) {
      setTestOutput(`Error: ${e instanceof Error ? e.message : String(e)}`);
      setTesting(false);
    }
  };

  const toggleRunDetail = async (runId: string) => {
    if (expandedRunId === runId) {
      setExpandedRunId(null);
      setExpandedRunDetail(null);
      return;
    }
    setExpandedRunId(runId);
    setExpandedRunDetail(null);
    try {
      const detail = await api.agents.getRun(runId);
      setExpandedRunDetail(detail);
    } catch (e) {
      console.error('Failed to load run detail:', e);
    }
  };

  const openRunModal = async (runId: string) => {
    try {
      const detail = await api.agents.getRun(runId);
      setModalRunDetail(detail);
    } catch (e) {
      console.error('Failed to load run detail:', e);
    }
  };

  const IMPORT_FIELDS = ['name', 'description', 'type', 'system_prompt', 'model', 'multi_modal_model', 'temperature',
    'max_turns', 'timeout_seconds', 'tools', 'input_template', 'variables', 'response_schema', 'subagent_ids', 'skill_ids'] as const;

  const handleImportFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    file.text().then(text => {
      try {
        const data = JSON.parse(text);
        const item = Array.isArray(data) ? data[0] : data;
        if (!item || typeof item !== 'object') {
          alert('Invalid agent JSON file');
          return;
        }
        const filtered: Record<string, unknown> = {};
        for (const key of IMPORT_FIELDS) {
          if (item[key] != null) filtered[key] = item[key];
        }
        setPendingImportData(filtered);
        setShowImportConfirm(true);
      } catch {
        alert('Invalid JSON file');
      }
    });
    e.target.value = '';
  };

  const applyImport = () => {
    if (!pendingImportData) return;
    setAgent({ ...agent, ...pendingImportData } as AgentDefinition);
    setShowImportConfirm(false);
    setPendingImportData(null);
  };

  const cancelImport = () => {
    setShowImportConfirm(false);
    setPendingImportData(null);
  };

  const handleExport = (a: AgentDefinition) => {
    const exportData = {
      name: a.name, description: a.description, type: a.type,
      system_prompt: a.system_prompt, model: a.model, multi_modal_model: a.multi_modal_model, temperature: a.temperature,
      max_turns: a.max_turns, timeout_seconds: a.timeout_seconds,         tools: a.tools,
      input_template: a.input_template, variables: a.variables, response_schema: a.response_schema,
      subagent_ids: a.subagent_ids,
      skill_ids: a.skill_ids,
    };
    const blob = new Blob([JSON.stringify(exportData, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${a.name.replace(/\s+/g, '-').toLowerCase()}.agent.json`;
    link.click();
    URL.revokeObjectURL(url);
  };

  const inputStyle = {
    background: 'var(--color-bg-tertiary)',
    borderColor: 'var(--color-border)',
    color: 'var(--color-text)',
  };

  return (
    <div className="p-6">
      <button onClick={() => navigate('/agents')}
        className="flex items-center gap-1 text-sm mb-4 cursor-pointer"
        style={{ color: 'var(--color-primary)' }}>
        <ArrowLeft size={16} /> Back to Agents
      </button>

      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <h1 className="text-xl font-semibold">{agent.name}</h1>
          <StatusBadge status={agent.status} />
          <span className="px-2 py-0.5 rounded text-xs"
            style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
            {agent.type}
          </span>
        </div>
        <div className="flex gap-2">
          <button onClick={handleDelete}
            className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)', color: 'var(--color-error)' }}>
            <Trash2 size={14} /> Delete
          </button>
          <label className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }}>
            <FileUp size={14} /> Import
            <input ref={importFileRef} type="file" accept=".json" className="hidden" onChange={handleImportFile} />
          </label>
          <button onClick={() => handleExport(agent)}
            className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer"
            style={{ borderColor: 'var(--color-border)' }}>
            <Download size={14} /> Export
          </button>
          <button onClick={handlePublish} disabled={publishing || isNew}
            className="flex items-center gap-1 px-3 py-2 rounded-lg text-sm border cursor-pointer disabled:opacity-50"
            style={{
              borderColor: publishSuccess ? 'var(--color-success)' : 'var(--color-border)',
              color: publishSuccess ? 'var(--color-success)' : undefined,
            }}>
            {publishSuccess ? <><Check size={14} /> Published!</> : <><Upload size={14} /> {publishing ? 'Publishing...' : 'Publish'}</>}
          </button>
          <button onClick={handleSave} disabled={saving || saveSuccess}
            className="flex items-center gap-1 px-4 py-2 rounded-lg text-sm font-medium cursor-pointer"
            style={{
              background: saveSuccess ? 'var(--color-success)' : 'var(--color-primary)',
              color: 'white',
            }}>
            {saveSuccess ? <><Check size={14} /> Saved!</> : <><Save size={14} /> {saving ? 'Saving...' : 'Save'}</>}
          </button>
        </div>
      </div>

      {showImportConfirm && (
        <div className="fixed inset-0 z-50 flex items-center justify-center" style={{ background: 'rgba(0,0,0,0.5)' }}>
          <div className="rounded-xl p-6 max-w-md w-full mx-4" style={{ background: 'var(--color-bg-secondary)', border: '1px solid var(--color-border)' }}>
            <h3 className="text-lg font-semibold mb-2">Confirm Import</h3>
            <p className="text-sm mb-1" style={{ color: 'var(--color-text-secondary)' }}>
              This will overwrite the current agent configuration with the imported data.
            </p>
            {!!(pendingImportData as Record<string, unknown>)?.name && (
              <p className="text-sm mb-4" style={{ color: 'var(--color-text-secondary)' }}>
                Importing: <strong style={{ color: 'var(--color-text)' }}>{String(((pendingImportData as Record<string, unknown>)?.name as string) || '')}</strong>
              </p>
            )}
            <p className="text-sm mb-4" style={{ color: 'var(--color-warning, #f59e0b)' }}>
              Unsaved changes will be lost. You can review and save after import.
            </p>
            <div className="flex justify-end gap-2">
              <button onClick={cancelImport}
                className="px-4 py-2 rounded-lg text-sm border cursor-pointer"
                style={{ borderColor: 'var(--color-border)' }}>
                Cancel
              </button>
              <button onClick={applyImport}
                className="px-4 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
                style={{ background: 'var(--color-primary)' }}>
                Confirm Import
              </button>
            </div>
          </div>
        </div>
      )}

      {saveError && (
        <div className="mb-4 p-3 rounded-lg text-sm" style={{ background: 'rgba(239,68,68,0.1)', color: 'var(--color-error)' }}>
          {saveError}
        </div>
      )}

      <div className="grid grid-cols-3 gap-6">
        {/* Left: config */}
        <div className="col-span-2 space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium mb-1">Name</label>
              <input value={agent.name || ''} onChange={e => update('name', e.target.value)}
                className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
                style={inputStyle} />
            </div>
            <div>
              <label className="block text-sm font-medium mb-1">Type</label>
              <select value={agent.type || 'AGENT'} onChange={e => update('type', e.target.value)}
                className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
                style={inputStyle}>
                <option value="AGENT">AGENT</option>
                <option value="LLM_CALL">LLM_CALL</option>
              </select>
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium mb-1">Description <span className="text-xs font-normal" style={{ color: 'var(--color-text-secondary)' }}>(optional)</span></label>
            <input value={agent.description || ''} onChange={e => update('description', e.target.value)}
              className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
              style={inputStyle} />
          </div>

          {/* System Prompt Selection */}
          <div className="rounded-xl border p-4"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <h3 className="font-medium text-sm mb-3">System Prompt <span className="text-xs font-normal" style={{ color: 'var(--color-text-secondary)' }}>(optional)</span></h3>
            <div className="space-y-3">
              <div>
                <label className="block text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Select from System Prompts
                </label>
                <select value={agent.system_prompt_id || ''}
                  onChange={e => {
                    const promptId = e.target.value || null;
                    setAgent(prev => prev ? { ...prev, system_prompt_id: promptId, system_prompt: promptId ? null : prev.system_prompt } as AgentDefinition : prev);
                  }}
                  className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
                  style={inputStyle}>
                  <option value="">-- None (use inline prompt) --</option>
                  {systemPrompts.map(sp => (
                    <option key={sp.promptId} value={sp.promptId}>
                      {sp.name} (v{sp.version})
                    </option>
                  ))}
                </select>
                {agent.system_prompt_id && (() => {
                  const sp = systemPrompts.find(s => s.promptId === agent.system_prompt_id);
                  return sp ? (
                    <div className="mt-2">
                      <p className="text-xs mb-1" style={{ color: 'var(--color-success)' }}>
                        Using managed prompt: {sp.name} (v{sp.version})
                      </p>
                      <pre className="text-xs p-3 rounded-lg overflow-auto max-h-48 whitespace-pre-wrap"
                        style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                        {sp.content}
                      </pre>
                    </div>
                  ) : null;
                })()}
              </div>

              {!agent.system_prompt_id && (
                <div>
                  <div className="flex items-center justify-between mb-1">
                    <label className="block text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                      Inline System Prompt
                    </label>
                    <button onClick={() => setPromptExpanded(!promptExpanded)}
                      className="text-xs px-2 py-0.5 rounded cursor-pointer flex items-center gap-1"
                      style={{ color: 'var(--color-primary)', background: 'var(--color-bg-tertiary)' }}>
                      {promptExpanded ? <><Minimize2 size={12} /> Collapse</> : <><Maximize2 size={12} /> Expand</>}
                    </button>
                  </div>
                  <div className="flex items-center gap-2 mb-2">
                    <button onClick={handleGeneratePrompt}
                      disabled={generatingPrompt || (!agent.name && !agent.description)}
                      className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-white cursor-pointer disabled:opacity-50"
                      style={{ background: 'var(--color-primary)' }}>
                      {generatingPrompt ? <><Loader2 size={12} className="animate-spin" /> Generating...</> : <><Sparkles size={12} /> AI Generate</>}
                    </button>
                    {(!agent.name && !agent.description) && (
                      <span className="text-xs" style={{ color: 'var(--color-text-tertiary)' }}>Enter name or description to generate</span>
                    )}
                  </div>
                  <textarea value={agent.system_prompt || ''}
                    onChange={e => update('system_prompt', e.target.value)}
                    rows={promptExpanded ? 30 : 8}
                    className="w-full px-3 py-2 rounded-lg border text-sm font-mono outline-none resize-y"
                    style={inputStyle}
                    placeholder="Enter system prompt directly..." />
                </div>
              )}
            </div>
          </div>

          {/* Model Config */}
          <div className="rounded-xl border mt-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <button
              onClick={() => setModelConfigOpen(!modelConfigOpen)}
              className="w-full flex items-center justify-between p-4 cursor-pointer">
              <div className="flex items-center gap-2">
                <SlidersHorizontal size={16} style={{ color: 'var(--color-text-secondary)' }} />
                <span className="font-medium text-sm">Model Config <span className="text-xs font-normal" style={{ color: 'var(--color-text-secondary)' }}>(optional)</span></span>
              </div>
              {modelConfigOpen ? <ChevronDown size={16} style={{ color: 'var(--color-text-secondary)' }} /> : <ChevronRight size={16} style={{ color: 'var(--color-text-secondary)' }} />}
            </button>
            {modelConfigOpen && (
              <div className="px-4 pb-4 border-t" style={{ borderColor: 'var(--color-border)' }}>
                <div className="grid grid-cols-4 gap-4 pt-3">
                  <div>
                    <label className="block text-sm font-medium mb-1">Model</label>
                    <input value={agent.model || ''} onChange={e => update('model', e.target.value)}
                      className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
                      style={inputStyle}
                      placeholder="e.g. gpt-4" />
                  </div>
                  <div>
                    <label className="block text-sm font-medium mb-1">Temperature</label>
                    <input type="number" step="0.1" min="0" max="2"
                      value={agent.temperature ?? ''} onChange={e => update('temperature', e.target.value ? parseFloat(e.target.value) : null)}
                      className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
                      style={inputStyle}
                      placeholder="0.7" />
                  </div>
                  <div>
                    <label className="block text-sm font-medium mb-1">Max Turns</label>
                    <input type="number" min="1"
                      value={agent.max_turns ?? ''} onChange={e => update('max_turns', e.target.value ? parseInt(e.target.value) : null)}
                      className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
                      style={inputStyle}
                      placeholder="20" />
                  </div>
                  <div>
                    <label className="block text-sm font-medium mb-1">Timeout (s)</label>
                    <input type="number" min="1"
                      value={agent.timeout_seconds ?? ''} onChange={e => update('timeout_seconds', e.target.value ? parseInt(e.target.value) : null)}
                      className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
                      style={inputStyle}
                      placeholder="600" />
                  </div>
                </div>

                <div className="mt-4">
                  <label className="flex items-center gap-2 cursor-pointer">
                    <input type="checkbox"
                      checked={showMultiModalModel}
                      onChange={e => {
                        setShowMultiModalModel(e.target.checked);
                        if (!e.target.checked) update('multi_modal_model', '');
                      }}
                      className="w-4 h-4 rounded accent-[var(--color-accent)]" />
                    <span className="text-sm font-medium">Enable multi-modal model</span>
                  </label>
                  <p className="text-xs mt-1 ml-6" style={{ color: 'var(--color-text-tertiary)' }}>
                    Only needed when your main model lacks vision / multi-modal capabilities and you need to handle images or files.
                    When a request contains images or file attachments, it will be routed to this model instead.
                  </p>
                  {showMultiModalModel && (
                    <div className="mt-2 ml-6 w-80">
                      <input value={agent.multi_modal_model || ''} onChange={e => update('multi_modal_model', e.target.value)}
                        className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
                        style={inputStyle}
                        placeholder="e.g. gpt-4o" />
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>

          {/* Input Template */}
          <div className="rounded-xl border mt-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <button
              onClick={() => setInputTemplateOpen(!inputTemplateOpen)}
              className="w-full flex items-center justify-between p-4 cursor-pointer">
              <div className="flex items-center gap-2">
                <Code size={16} style={{ color: 'var(--color-text-secondary)' }} />
                <span className="font-medium text-sm">Input Template <span className="text-xs font-normal" style={{ color: 'var(--color-text-secondary)' }}>(optional)</span></span>
              </div>
              {inputTemplateOpen ? <ChevronDown size={16} style={{ color: 'var(--color-text-secondary)' }} /> : <ChevronRight size={16} style={{ color: 'var(--color-text-secondary)' }} />}
            </button>
            {inputTemplateOpen && (
              <div className="px-4 pb-4 border-t" style={{ borderColor: 'var(--color-border)' }}>
                <textarea value={agent.input_template || ''}
                  onChange={e => update('input_template', e.target.value)}
                  rows={4}
                  className="w-full px-3 py-2 rounded-lg border text-sm font-mono outline-none resize-y mt-3"
                  style={inputStyle}
                  placeholder="Input template with {{variable}} placeholders..." />
              </div>
            )}
          </div>

          {/* Variables */}
          <div className="rounded-xl border mt-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <button
              onClick={() => setVariablesOpen(!variablesOpen)}
              className="w-full flex items-center justify-between p-4 cursor-pointer">
              <div className="flex items-center gap-2">
                <Braces size={16} style={{ color: 'var(--color-text-secondary)' }} />
                <span className="font-medium text-sm">Variables <span className="text-xs font-normal" style={{ color: 'var(--color-text-secondary)' }}>(optional)</span></span>
                {agent.variables && Object.keys(agent.variables).length > 0 && (
                  <span className="text-xs px-1.5 py-0.5 rounded" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                    {Object.keys(agent.variables).length}
                  </span>
                )}
              </div>
              {variablesOpen ? <ChevronDown size={16} style={{ color: 'var(--color-text-secondary)' }} /> : <ChevronRight size={16} style={{ color: 'var(--color-text-secondary)' }} />}
            </button>
            {variablesOpen && (
              <div className="px-4 pb-4 border-t" style={{ borderColor: 'var(--color-border)' }}>
                <p className="text-xs pt-3 mb-3" style={{ color: 'var(--color-text-secondary)' }}>
                  Configure default variable values. These defaults are used by chat and schedule unless overridden.
                </p>
                <KeyValueVariablesEditor
                  value={agent.variables}
                  onChange={value => update('variables', value)}
                  keyPlaceholder="Variable key"
                  valuePlaceholder="Default value"
                />
              </div>
            )}
          </div>

          {/* Datasets */}
          <div className="rounded-xl border mt-4" style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <button
              onClick={() => { if (!datasetsOpen) loadDatasets(); setDatasetsOpen(!datasetsOpen); }}
              className="w-full flex items-center justify-between p-4 cursor-pointer">
              <div className="flex items-center gap-2">
                <Database size={16} style={{ color: 'var(--color-primary)' }} />
                <span className="font-medium text-sm">Datasets <span className="text-xs font-normal" style={{ color: 'var(--color-text-secondary)' }}>(optional)</span></span>
                {datasetConfig.length > 0 && (
                  <span className="text-xs px-1.5 py-0.5 rounded" style={{ background: 'var(--color-primary)', color: '#fff' }}>
                    {datasetConfig.length}
                  </span>
                )}
              </div>
              {datasetsOpen ? <ChevronDown size={16} style={{ color: 'var(--color-text-secondary)' }} /> : <ChevronRight size={16} style={{ color: 'var(--color-text-secondary)' }} />}
            </button>
            {datasetsOpen && (
              <div className="px-4 pb-4 border-t" style={{ borderColor: 'var(--color-border)' }}>
                <p className="text-xs pt-3 mb-1" style={{ color: 'var(--color-text-secondary)' }}>
                  Datasets the agent can read from. Auto-extraction results can optionally be written to a single output dataset.
                </p>
                <p className="text-xs mb-3" style={{ color: 'var(--color-text-secondary)', opacity: 0.7 }}>
                  Tip: At most one dataset can be the output target. Output requires WRITE or FULL permission.
                </p>

                {/* Current dataset config */}
                {datasetConfig.length > 0 && (
                  <div className="space-y-2 mb-3">
                    {datasetConfig.map(cfg => {
                      const ds = allDatasets.find(d => d.id === cfg.dataset_id);
                      return (
                        <div key={cfg.dataset_id} className="flex items-center gap-2 px-3 py-2 rounded-lg text-sm"
                          style={{ background: 'var(--color-bg)', border: '1px solid var(--color-border)' }}>
                          <span className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ background: 'var(--color-primary)' }} />
                          <span className="font-medium flex-1" style={{ color: 'var(--color-text)' }}>{ds?.name || cfg.dataset_id}</span>
                          <select value={cfg.permission}
                            onChange={e => changeDatasetPermission(cfg.dataset_id, e.target.value as 'READ' | 'WRITE' | 'FULL')}
                            className="text-xs px-1.5 py-0.5 rounded border outline-none"
                            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)', color: 'var(--color-text)' }}>
                            <option value="READ">READ</option>
                            <option value="WRITE">WRITE</option>
                            <option value="FULL">FULL</option>
                          </select>
                          {cfg.is_output ? (
                            <button onClick={clearOutputDataset}
                              className="text-xs px-1.5 py-0.5 rounded flex-shrink-0 cursor-pointer"
                              style={{ background: 'var(--color-primary)', color: '#fff' }}
                              title="Click to unset output">output</button>
                          ) : (cfg.permission !== 'READ') ? (
                            <button onClick={() => setOutputDataset(cfg.dataset_id)}
                              className="text-xs px-1.5 py-0.5 rounded cursor-pointer flex-shrink-0"
                              style={{ color: 'var(--color-text-secondary)', border: '1px dashed var(--color-border)' }}
                              title="Set as output dataset">output</button>
                          ) : (
                            <span className="text-xs flex-shrink-0" style={{ color: 'var(--color-text-secondary)', opacity: 0.4 }}>—</span>
                          )}
                          <button onClick={() => removeDatasetConfig(cfg.dataset_id)}
                            className="cursor-pointer rounded flex-shrink-0"
                            style={{ color: 'var(--color-text-secondary)' }}>
                            <X size={14} />
                          </button>
                        </div>
                      );
                    })}
                  </div>
                )}

                {/* Add dataset */}
                <div className="relative">
                  <div className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg border text-sm" style={inputStyle}>
                    <Search size={14} style={{ color: 'var(--color-text-secondary)', flexShrink: 0 }} />
                    <input value={datasetSearch} onChange={e => setDatasetSearch(e.target.value)}
                      onFocus={() => setDatasetFocused(true)}
                      onBlur={() => setDatasetFocused(false)}
                      className="flex-1 bg-transparent outline-none text-sm"
                      placeholder="Add dataset..." />
                  </div>
                  {(datasetFocused || datasetSearch) && (
                    <div className="absolute z-10 mt-1 w-full rounded-lg border shadow-lg overflow-auto"
                      onMouseDown={e => e.preventDefault()}
                      style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', maxHeight: '200px' }}>
                      {allDatasets
                        .filter(d => !datasetConfig.some(c => c.dataset_id === d.id))
                        .filter(d => !datasetSearch || d.name.toLowerCase().includes(datasetSearch.toLowerCase()))
                        .map(d => (
                          <button key={d.id}
                            onClick={() => addDatasetConfig(d.id)}
                            className="w-full px-3 py-2 text-left text-sm cursor-pointer hover:bg-[var(--color-bg-tertiary)] flex items-center gap-2"
                            style={{ borderBottom: '1px solid var(--color-border)' }}>
                            <span className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ background: 'var(--color-text-secondary)' }} />
                            <span style={{ color: 'var(--color-text)' }}>{d.name}</span>
                          </button>
                        ))}
                      {allDatasets.filter(d => !datasetConfig.some(c => c.dataset_id === d.id)).length === 0 && (
                        <div className="px-3 py-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                          {allDatasets.length === 0 ? 'No datasets available' : 'All datasets added'}
                        </div>
                      )}
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>

          {/* Response Schema - only for LLM_CALL */}
          {agent.type === 'LLM_CALL' && (
            <ResponseSchemaEditor
              value={agent.response_schema}
              onChange={v => update('response_schema', v)}
              inputStyle={inputStyle}
            />
          )}

          {/* Tools - not shown for LLM_CALL */}
          {agent.type !== 'LLM_CALL' && (
          <div className="rounded-xl border p-4"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <h3 className="font-medium text-sm mb-3 flex items-center gap-2">
              <Wrench size={16} style={{ color: '#f59e0b' }} /> Tools <span className="text-xs font-normal" style={{ color: 'var(--color-text-secondary)' }}>(optional)</span>
              {agent.tools && agent.tools.length > 0 && (
                <span className="text-[10px] px-1.5 py-0.5 rounded-full" style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                  {agent.tools.length} selected
                </span>
              )}
            </h3>

            {/* Selected tools summary */}
            {agent.tools && agent.tools.length > 0 && (
              <div className="flex flex-wrap gap-1.5 mb-3 pb-3 border-b" style={{ borderColor: 'var(--color-border)' }}>
                {agent.tools.map((toolRef: ToolRef) => {
                  const isApiTool = toolRef.id.startsWith('api-app:') || toolRef.id.startsWith('api-service:') || toolRef.id.startsWith('api-operation:');
                  const isMcpTool = toolRef.id.startsWith('mcp-tool:');
                  const tool = (isApiTool || isMcpTool) ? null : allTools.find(t => t.id === toolRef.id);
                  const displayName = isApiTool ? getApiToolDisplayName(toolRef)
                    : isMcpTool ? getMcpToolDisplayName(toolRef)
                    : (tool?.name || toolRef.id);
                  const color = isApiTool ? '#10b981' : (toolRef.type || tool?.type) === 'MCP' ? '#8b5cf6' : '#f59e0b';
                  return (
                    <span key={`${toolRef.id}:${toolRef.source || ''}`}
                      className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-[11px]"
                      style={{ background: `${color}10`, border: `1px solid ${color}30` }}>
                      <span style={{ color }}>{displayName}</span>
                      <button onClick={() => {
                        if (isMcpTool) {
                          removeMcpTool(toolRef);
                        } else {
                          update('tools', agent.tools.filter((t: ToolRef) => t.id !== toolRef.id));
                        }
                      }}
                        className="cursor-pointer rounded hover:opacity-70" style={{ color }}>
                        <X size={10} />
                      </button>
                    </span>
                  );
                })}
              </div>
            )}

            {/* Builtin Tools */}
            <ToolSection
              title="Builtin"
              color="#f59e0b"
              items={allTools.filter(t => t.type === 'BUILTIN' && t.id !== 'builtin-service-api')}
              selectedIds={agent.tools?.map((t: ToolRef) => t.id) || []}
              onToggle={(id, selected) => {
                if (selected) {
                  update('tools', agent.tools.filter((t: ToolRef) => t.id !== id));
                } else {
                  const tool = allTools.find(t => t.id === id);
                  update('tools', [...(agent.tools || []), { id, type: 'BUILTIN', source: tool?.category }]);
                }
              }}
            />

            {/* MCP Tools */}
            <div className="mt-2 rounded-lg border" style={{ borderColor: 'var(--color-border)' }}>
              <button
                className="w-full flex items-center justify-between px-3 py-2 text-xs font-medium cursor-pointer hover:bg-[var(--color-bg-tertiary)] rounded-lg"
                onClick={() => setShowMcp(!showMcp)}>
                <div className="flex items-center gap-2">
                  <span className="w-2 h-2 rounded-full" style={{ background: '#8b5cf6' }} />
                  <span>MCP</span>
                  {mcpServers.length > 0 && agent.tools?.some((t: ToolRef) => t.type === 'MCP') && (
                    <span className="text-[10px] px-1 rounded-full" style={{ background: '#8b5cf620', color: '#8b5cf6' }}>
                      {agent.tools.filter((t: ToolRef) => t.type === 'MCP').length}
                    </span>
                  )}
                </div>
                {showMcp ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
              </button>
              {showMcp && (
                <>{mcpServers.length === 0 ? (
                <div className="px-3 py-2 text-xs border-t" style={{ color: 'var(--color-text-secondary)', borderColor: 'var(--color-border)' }}>No MCP servers available</div>
              ) : (
                <div className="border-t max-h-[400px] overflow-auto" style={{ borderColor: 'var(--color-border)' }}>
                {mcpServers.map(server => {
                  const serverFullySelected = isMcpServerFullySelected(server.id);
                  const individualToolCount = (agent.tools || []).filter((t: ToolRef) => t.id.startsWith(`mcp-tool:${server.id}:`)).length;
                  return (
                  <div key={server.id} className="border-t first:border-t-0" style={{ borderColor: 'var(--color-border)' }}>
                    <div className="flex items-center px-3 py-1.5">
                      <button
                        className="flex-1 flex items-center gap-2 text-xs cursor-pointer text-left"
                        onClick={() => toggleMcpServer(server.id)}>
                        {expandedMcpServer === server.id ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                        <span className="font-medium">{server.name}</span>
                        {individualToolCount > 0 && (
                          <span className="text-[10px]" style={{ color: 'var(--color-text-secondary)' }}>
                            {individualToolCount} tool{individualToolCount > 1 ? 's' : ''}
                          </span>
                        )}
                      </button>
                      <button
                        className="text-[10px] px-1.5 py-0.5 rounded cursor-pointer flex-shrink-0"
                        style={{
                          background: (serverFullySelected || (server.enabled === false)) ? 'var(--color-bg-tertiary)' : '#8b5cf620',
                          color: (serverFullySelected || (server.enabled === false)) ? 'var(--color-text-secondary)' : '#8b5cf6',
                        }}
                        disabled={!!(serverFullySelected || server.enabled === false)}
                        onClick={() => addMcpServerAll(server.id)}>
                        {serverFullySelected ? 'Added' : 'Add All'}
                      </button>
                    </div>
                    {expandedMcpServer === server.id && (
                      <div className="pl-6 pb-1">
                        {!mcpServerTools[server.id] ? (
                          <div className="px-3 py-1 text-xs flex items-center gap-2" style={{ color: 'var(--color-text-secondary)' }}>
                            <Loader2 size={10} className="animate-spin" /> Loading...
                          </div>
                        ) : mcpServerTools[server.id].length === 0 ? (
                          <div className="px-3 py-1 text-xs" style={{ color: 'var(--color-text-secondary)' }}>No tools available</div>
                        ) : (
                          mcpServerTools[server.id].map(tool => {
                            const toolSelected = isMcpToolSelected(server.id, tool.name);
                            return (
                              <div key={tool.name}
                                className="flex items-center justify-between px-3 py-0.5 text-xs hover:bg-[var(--color-bg-tertiary)] rounded">
                                <span className="truncate flex-1">{tool.name}</span>
                                <button
                                  className="text-[10px] px-1.5 py-0.5 rounded cursor-pointer flex-shrink-0"
                                  style={{
                                    background: (toolSelected || serverFullySelected) ? 'var(--color-bg-tertiary)' : '#8b5cf620',
                                    color: (toolSelected || serverFullySelected) ? 'var(--color-text-secondary)' : '#8b5cf6',
                                  }}
                                    disabled={!!(toolSelected || serverFullySelected)}
                                    onClick={() => {
                                      const toolRefId = `mcp-tool:${server.id}:${tool.name}`;
                                      if (toolSelected) {
                                        removeMcpTool({ id: toolRefId, type: 'MCP' });
                                      } else {
                                        addMcpTool(server.id, tool.name);
                                      }
                                    }}>
                                  {serverFullySelected ? '✓' : toolSelected ? 'Added' : 'Add'}
                                </button>
                              </div>
                            );
                          })
                        )}
                      </div>
                    )}
                  </div>
                  );
                })}
                </div>
              )}
            </>
          )}
            </div>

            {/* Service API Tools */}
            <div className="mt-2 rounded-lg border" style={{ borderColor: 'var(--color-border)' }}>
              <button
                className="w-full flex items-center justify-between px-3 py-2 text-xs font-medium cursor-pointer hover:bg-[var(--color-bg-tertiary)] rounded-lg"
                onClick={() => { setShowApiPicker(!showApiPicker); if (!apiAppsLoaded) loadApiApps(); }}>
                <div className="flex items-center gap-2">
                  <span className="w-2 h-2 rounded-full" style={{ background: '#10b981' }} />
                  <span>Service API</span>
                  {agent.tools?.some((t: ToolRef) => t.id.startsWith('api-app:') || t.id.startsWith('api-service:') || t.id.startsWith('api-operation:')) && (
                    <span className="text-[10px] px-1 rounded-full" style={{ background: '#10b98120', color: '#10b981' }}>
                      {agent.tools.filter((t: ToolRef) => t.id.startsWith('api-app:') || t.id.startsWith('api-service:') || t.id.startsWith('api-operation:')).length}
                    </span>
                  )}
                </div>
                {showApiPicker ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
              </button>
              {showApiPicker && (
                <div className="border-t" style={{ borderColor: 'var(--color-border)' }}>
                  {!apiAppsLoaded ? (
                    <div className="px-3 py-2 text-xs flex items-center gap-2" style={{ color: 'var(--color-text-secondary)' }}>
                      <Loader2 size={12} className="animate-spin" /> Loading...
                    </div>
                  ) : apiApps.length === 0 ? (
                    <div className="px-3 py-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>No API apps available</div>
                  ) : (
                    <div className="max-h-[400px] overflow-auto">
                    {apiApps.map(app => {
                      const appAdded = agent.tools?.some((t: ToolRef) => t.id === `api-app:${app.name}`);
                      return (
                      <div key={app.name} className="border-t first:border-t-0" style={{ borderColor: 'var(--color-border)' }}>
                        <div className="flex items-center px-3 py-1.5">
                          <button
                            className="flex-1 flex items-center gap-2 text-xs cursor-pointer text-left"
                            onClick={() => toggleApiApp(app.name)}>
                            {expandedApiApp === app.name ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                            <span className="font-medium">{app.name}</span>
                            {app.version && (
                              <span className="text-[10px]" style={{ color: 'var(--color-text-secondary)' }}>v{app.version}</span>
                            )}
                          </button>
                          <button
                            className="text-[10px] px-1.5 py-0.5 rounded cursor-pointer"
                            style={{
                              background: appAdded ? 'var(--color-bg-tertiary)' : '#10b98120',
                              color: appAdded ? 'var(--color-text-secondary)' : '#10b981',
                            }}
                            disabled={!!appAdded}
                            onClick={() => addApiAppTool(app.name)}>
                            {appAdded ? 'Added' : 'Add All'}
                          </button>
                        </div>
                        {expandedApiApp === app.name && (
                          <div className="pl-6 pb-1">
                            {!apiAppServices[app.name] ? (
                              <div className="px-3 py-1 text-xs flex items-center gap-2" style={{ color: 'var(--color-text-secondary)' }}>
                                <Loader2 size={10} className="animate-spin" /> Loading...
                              </div>
                            ) : apiAppServices[app.name].length === 0 ? (
                              <div className="px-3 py-1 text-xs" style={{ color: 'var(--color-text-secondary)' }}>No services</div>
                            ) : (
                              apiAppServices[app.name].map(svc => {
                                const svcToolId = `api-service:${app.name}:${svc.name}`;
                                const svcAdded = agent.tools?.some((t: ToolRef) => t.id === svcToolId);
                                const svcKey = `${app.name}:${svc.name}`;
                                const isExpanded = expandedApiService === svcKey;
                                return (
                                  <div key={svc.name}>
                                    <div className="flex items-center justify-between px-3 py-1 text-xs hover:bg-[var(--color-bg-tertiary)] rounded">
                                      <button
                                        className="flex items-center gap-2 flex-1 min-w-0 cursor-pointer text-left"
                                        onClick={() => setExpandedApiService(isExpanded ? null : svcKey)}>
                                        {isExpanded ? <ChevronDown size={10} /> : <ChevronRight size={10} />}
                                        <span className="font-medium truncate">{svc.name}</span>
                                        <span className="text-[10px] flex-shrink-0" style={{ color: 'var(--color-text-secondary)' }}>
                                          {svc.operation_count} ops
                                        </span>
                                      </button>
                                      <button
                                        className="text-[10px] px-1.5 py-0.5 rounded cursor-pointer flex-shrink-0"
                                        style={{
                                          background: (svcAdded || appAdded) ? 'var(--color-bg-tertiary)' : '#10b98120',
                                          color: (svcAdded || appAdded) ? 'var(--color-text-secondary)' : '#10b981',
                                        }}
                                        disabled={!!(svcAdded || appAdded)}
                                        onClick={() => addApiServiceTool(app.name, svc.name)}>
                                        {appAdded ? 'App added' : svcAdded ? 'Added' : 'Add All'}
                                      </button>
                                    </div>
                                    {isExpanded && svc.operations && (
                                      <div className="pl-6 pb-0.5">
                                        {svc.operations.map(op => {
                                          const opToolId = `api-operation:${app.name}:${svc.name}:${op.name}`;
                                          const opAdded = agent.tools?.some((t: ToolRef) => t.id === opToolId);
                                          const parentAdded = appAdded || svcAdded;
                                          return (
                                            <div key={op.name}
                                              className="flex items-center justify-between px-3 py-0.5 text-xs hover:bg-[var(--color-bg-tertiary)] rounded">
                                              <div className="flex items-center gap-2 flex-1 min-w-0">
                                                {op.method && (
                                                  <span className="text-[9px] font-mono px-1 rounded flex-shrink-0"
                                                    style={{
                                                      background: op.method === 'GET' ? '#3b82f615' : op.method === 'POST' ? '#10b98115' : op.method === 'PUT' ? '#f59e0b15' : '#ef444415',
                                                      color: op.method === 'GET' ? '#3b82f6' : op.method === 'POST' ? '#10b981' : op.method === 'PUT' ? '#f59e0b' : '#ef4444',
                                                    }}>
                                                    {op.method}
                                                  </span>
                                                )}
                                                <span className="truncate">{op.name}</span>
                                              </div>
                                              <button
                                                className="text-[10px] px-1.5 py-0.5 rounded cursor-pointer flex-shrink-0"
                                                style={{
                                                  background: (opAdded || parentAdded) ? 'var(--color-bg-tertiary)' : '#10b98120',
                                                  color: (opAdded || parentAdded) ? 'var(--color-text-secondary)' : '#10b981',
                                                }}
                                                disabled={!!(opAdded || parentAdded)}
                                                onClick={() => addApiOperationTool(app.name, svc.name, op.name)}>
                                                {parentAdded ? '✓' : opAdded ? 'Added' : 'Add'}
                                              </button>
                                            </div>
                                          );
                                        })}
                                      </div>
                                    )}
                                  </div>
                                );
                              })
                            )}
                          </div>
                        )}
                      </div>
                      );
                    })}
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
          )}

          {/* Subagents & Skills - not shown for LLM_CALL */}
          {agent.type !== 'LLM_CALL' && (
          <>
          {/* Subagents */}
          <div className="rounded-xl border p-4"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <h3 className="font-medium text-sm mb-3 flex items-center gap-2">
              <Users size={16} style={{ color: '#8b5cf6' }} /> Subagents <span className="text-xs font-normal" style={{ color: 'var(--color-text-secondary)' }}>(optional)</span>
            </h3>
            <p className="text-xs mb-3" style={{ color: 'var(--color-text-secondary)' }}>
              Select agents to load as subagents when this agent is used in a chat session.
            </p>

            {/* Selected subagents */}
            {agent.subagent_ids && agent.subagent_ids.length > 0 ? (
              <div className="flex flex-wrap gap-2 mb-3">
                {agent.subagent_ids.map((subAgentId: string) => {
                  const subAgent = allAgents.find(a => a.id === subAgentId);
                  return (
                    <span key={subAgentId}
                      className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs"
                      style={{ background: 'var(--color-bg-tertiary)', border: '1px solid var(--color-border)' }}>
                      <span className="w-1.5 h-1.5 rounded-full" style={{ background: '#8b5cf6' }} />
                      <span className="font-medium">{subAgent?.name || subAgentId}</span>
                      <button onClick={() => update('subagent_ids', agent.subagent_ids!.filter((id: string) => id !== subAgentId))}
                        className="cursor-pointer ml-0.5 rounded hover:bg-[var(--color-bg-tertiary)]"
                        style={{ color: 'var(--color-text-secondary)' }}>
                        <X size={12} />
                      </button>
                    </span>
                  );
                })}
              </div>
            ) : (
              <p className="text-xs mb-3" style={{ color: 'var(--color-text-secondary)' }}>No subagents selected</p>
            )}

            {/* Add subagents */}
            <div className="relative">
              <div className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg border text-sm"
                style={inputStyle}>
                <Search size={14} style={{ color: 'var(--color-text-secondary)', flexShrink: 0 }} />
                <input value={subAgentSearch} onChange={e => setSubAgentSearch(e.target.value)}
                  onFocus={() => setSubAgentFocused(true)}
                  onBlur={() => setSubAgentFocused(false)}
                  className="flex-1 bg-transparent outline-none text-sm"
                  placeholder="Click to browse or type to filter agents..." />
              </div>
              {(subAgentFocused || subAgentSearch) && (
                // preventDefault on mousedown keeps the input focused so clicking a candidate doesn't trigger blur first
                <div className="absolute z-10 mt-1 w-full rounded-lg border shadow-lg overflow-auto"
                  onMouseDown={e => e.preventDefault()}
                  style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', maxHeight: '200px' }}>
                  {allAgents
                    .filter(a => !agent.subagent_ids?.includes(a.id))
                    .filter(a => !subAgentSearch || a.name.toLowerCase().includes(subAgentSearch.toLowerCase()) || a.type?.toLowerCase().includes(subAgentSearch.toLowerCase()))
                    .map(a => (
                      <button key={a.id}
                        onClick={() => {
                          const currentIds = agent.subagent_ids || [];
                          update('subagent_ids', [...currentIds, a.id]);
                          setSubAgentSearch('');
                        }}
                        className="w-full px-3 py-2 text-left text-xs flex items-center gap-2 cursor-pointer hover:bg-[var(--color-bg-tertiary)]"
                        style={{ borderBottom: '1px solid var(--color-border)' }}>
                        <span className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ background: '#8b5cf6' }} />
                        <span className="font-medium">{a.name}</span>
                        <span className="text-[10px] px-1 rounded ml-auto"
                          style={{ background: '#8b5cf615', color: '#8b5cf6' }}>
                          {a.type}
                        </span>
                        {a.description && (
                          <span className="text-[10px] truncate" style={{ color: 'var(--color-text-secondary)' }}>{a.description}</span>
                        )}
                      </button>
                    ))}
                  {allAgents
                    .filter(a => !agent.subagent_ids?.includes(a.id))
                    .filter(a => !subAgentSearch || a.name.toLowerCase().includes(subAgentSearch.toLowerCase())).length === 0 && (
                    <div className="px-3 py-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                      {subAgentSearch ? 'No matching agents' : 'No more agents available'}
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>

          {/* Skills */}
          <div className="rounded-xl border p-4"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <h3 className="font-medium text-sm mb-3 flex items-center gap-2">
              <Sparkles size={16} style={{ color: '#ec4899' }} /> Skills <span className="text-xs font-normal" style={{ color: 'var(--color-text-secondary)' }}>(optional)</span>
            </h3>
            <p className="text-xs mb-3" style={{ color: 'var(--color-text-secondary)' }}>
              Select skills to enhance this agent's capabilities.
            </p>

            {/* Selected skills */}
            {agent.skill_ids && agent.skill_ids.length > 0 ? (
              <div className="flex flex-wrap gap-2 mb-3">
                {agent.skill_ids.map((skillId: string) => {
                  const skill = allSkills.find(s => s.id === skillId);
                  return (
                    <span key={skillId}
                      className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs"
                      style={{ background: 'var(--color-bg-tertiary)', border: '1px solid var(--color-border)' }}>
                      <span className="w-1.5 h-1.5 rounded-full" style={{ background: '#ec4899' }} />
                      <span className="font-medium">{skill?.name || skillId}</span>
                      <span className="text-[10px] px-1 rounded"
                        style={{ background: '#ec489915', color: '#ec4899' }}>
                        {skill?.namespace || 'custom'}
                      </span>
                      <button onClick={() => update('skill_ids', agent.skill_ids!.filter((id: string) => id !== skillId))}
                        className="cursor-pointer ml-0.5 rounded hover:bg-[var(--color-bg-tertiary)]"
                        style={{ color: 'var(--color-text-secondary)' }}>
                        <X size={12} />
                      </button>
                    </span>
                  );
                })}
              </div>
            ) : (
              <p className="text-xs mb-3" style={{ color: 'var(--color-text-secondary)' }}>No skills selected</p>
            )}

            {/* Add skills */}
            <div className="relative">
              <div className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg border text-sm"
                style={inputStyle}>
                <Search size={14} style={{ color: 'var(--color-text-secondary)', flexShrink: 0 }} />
                <input value={skillSearch} onChange={e => setSkillSearch(e.target.value)}
                  onFocus={() => setSkillFocused(true)}
                  onBlur={() => setSkillFocused(false)}
                  className="flex-1 bg-transparent outline-none text-sm"
                  placeholder="Click to browse or type to filter skills..." />
              </div>
              {(skillFocused || skillSearch) && (
                <div className="absolute z-10 mt-1 w-full rounded-lg border shadow-lg overflow-auto"
                  onMouseDown={e => e.preventDefault()}
                  style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)', maxHeight: '200px' }}>
                  {allSkills
                    .filter(s => !agent.skill_ids?.includes(s.id))
                    .filter(s => !skillSearch || s.name.toLowerCase().includes(skillSearch.toLowerCase()) || s.namespace?.toLowerCase().includes(skillSearch.toLowerCase()))
                    .map(s => (
                      <button key={s.id}
                        onClick={() => {
                          const currentIds = agent.skill_ids || [];
                          update('skill_ids', [...currentIds, s.id]);
                          setSkillSearch('');
                        }}
                        className="w-full px-3 py-2 text-left text-xs flex items-center gap-2 cursor-pointer hover:bg-[var(--color-bg-tertiary)]"
                        style={{ borderBottom: '1px solid var(--color-border)' }}>
                        <span className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ background: '#ec4899' }} />
                        <span className="font-medium">{s.name}</span>
                        <span className="text-[10px] px-1 rounded ml-auto"
                          style={{ background: '#ec489915', color: '#ec4899' }}>
                          {s.namespace}
                        </span>
                        {s.description && (
                          <span className="text-[10px] truncate" style={{ color: 'var(--color-text-secondary)' }}>{s.description}</span>
                        )}
                      </button>
                    ))}
                  {allSkills
                    .filter(s => !agent.skill_ids?.includes(s.id))
                    .filter(s => !skillSearch || s.name.toLowerCase().includes(skillSearch.toLowerCase())).length === 0 && (
                    <div className="px-3 py-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                      {skillSearch ? 'No matching skills' : 'No more skills available'}
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
          </>
          )}

          {/* Sandbox Configuration */}
          {agent.type === 'AGENT' && (
            <SandboxConfigSection
              key={agent.id || 'new'}
              config={agent.sandbox_config}
              onChange={(key, value) => setAgent(prev => prev ? {
                ...prev,
                sandbox_config: { ...(prev.sandbox_config || {}), [key]: value }
              } as AgentDefinition : prev)}
              onEnvChange={(envVars) => setAgent(prev => prev ? {
                ...prev,
                sandbox_config: { ...(prev.sandbox_config || {}), env_vars: envVars }
              } as AgentDefinition : prev)}
              inputStyle={inputStyle}
            />
          )}
        </div>

        {/* Right: info + test + runs */}
        <div className="space-y-4">
          {/* Info */}
          <div className="rounded-xl border p-4"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <h3 className="font-medium text-sm mb-3">Info</h3>
            <dl className="text-xs space-y-2" style={{ color: 'var(--color-text-secondary)' }}>
                <div className="flex justify-between"><dt>ID</dt><dd className="font-mono truncate ml-2 max-w-32">{isNew ? '(new)' : agent.id}</dd></div>
              <div className="flex justify-between"><dt>Status</dt><dd>{agent.status}</dd></div>
              <div className="flex justify-between"><dt>Created by</dt><dd>{agent.created_by || '-'}</dd></div>
              <div className="flex justify-between"><dt>Timeout</dt><dd>{agent.timeout_seconds || 600}s</dd></div>
              <div className="flex justify-between"><dt>Published</dt><dd>{agent.published_at ? new Date(agent.published_at).toLocaleString() : '-'}</dd></div>
            </dl>
          </div>

          {/* Test Panel - always available */}
          <div className="rounded-xl border p-4"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <h3 className="font-medium text-sm mb-3 flex items-center gap-2">
              <Play size={16} style={{ color: 'var(--color-success)' }} /> Test
            </h3>
            <div className="space-y-3">
              <textarea value={testInput} onChange={e => setTestInput(e.target.value)}
                rows={3}
                className="w-full px-3 py-1.5 rounded-lg border text-sm outline-none resize-y"
                style={inputStyle}
                placeholder="Enter test input..." />

              {/* Image attachments for LLM_CALL */}
              {agent.type === 'LLM_CALL' && (
                <div>
                  {imageAttachments.length > 0 && (
                    <div className="flex flex-wrap gap-2 mb-2">
                      {imageAttachments.map((img, i) => (
                        <div key={i} className="relative group">
                          <img src={img.preview} alt="attachment"
                            className="w-16 h-16 object-cover rounded-lg border"
                            style={{ borderColor: 'var(--color-border)' }} />
                          <button onClick={() => setImageAttachments(prev => prev.filter((_, j) => j !== i))}
                            className="absolute -top-1.5 -right-1.5 w-5 h-5 rounded-full flex items-center justify-center cursor-pointer opacity-0 group-hover:opacity-100 transition-opacity"
                            style={{ background: 'var(--color-error)', color: 'white' }}>
                            <X size={10} />
                          </button>
                        </div>
                      ))}
                    </div>
                  )}
                  <div className="flex gap-1.5">
                    <input ref={imageFileRef} type="file" accept="image/*" className="hidden" onChange={handleImageUpload} />
                    <button onClick={() => imageFileRef.current?.click()}
                      className="flex items-center gap-1 px-2 py-1 rounded-md text-xs cursor-pointer border"
                      style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                      <Upload size={12} /> Upload
                    </button>
                    <button onClick={() => { setShowImageUrlInput(!showImageUrlInput); setShowBase64Input(false); }}
                      className="flex items-center gap-1 px-2 py-1 rounded-md text-xs cursor-pointer border"
                      style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                      <Link size={12} /> URL
                    </button>
                    <button onClick={() => { setShowBase64Input(!showBase64Input); setShowImageUrlInput(false); }}
                      className="flex items-center gap-1 px-2 py-1 rounded-md text-xs cursor-pointer border"
                      style={{ borderColor: 'var(--color-border)', color: 'var(--color-text-secondary)' }}>
                      <Code size={12} /> Base64
                    </button>
                    {imageAttachments.length > 0 && (
                      <button onClick={() => setImageAttachments([])}
                        className="flex items-center gap-1 px-2 py-1 rounded-md text-xs cursor-pointer"
                        style={{ color: 'var(--color-error)' }}>
                        <Trash size={12} /> Clear
                      </button>
                    )}
                  </div>
                  {showImageUrlInput && (
                    <div className="flex gap-1.5 mt-1.5">
                      <input value={imageUrlValue} onChange={e => setImageUrlValue(e.target.value)}
                        onKeyDown={e => e.key === 'Enter' && handleAddImageUrl()}
                        className="flex-1 px-2 py-1 rounded-md border text-xs outline-none"
                        style={inputStyle}
                        placeholder="https://example.com/image.png" />
                      <button onClick={handleAddImageUrl} disabled={!imageUrlValue.trim()}
                        className="px-2 py-1 rounded-md text-xs font-medium text-white cursor-pointer disabled:opacity-50"
                        style={{ background: 'var(--color-primary)' }}>
                        Add
                      </button>
                    </div>
                  )}
                  {showBase64Input && (
                    <div className="mt-1.5 space-y-1.5">
                      <div className="flex gap-1.5">
                        <select value={base64MediaType} onChange={e => setBase64MediaType(e.target.value)}
                          className="px-2 py-1 rounded-md border text-xs outline-none"
                          style={inputStyle}>
                          <option value="image/png">PNG</option>
                          <option value="image/jpeg">JPEG</option>
                          <option value="image/webp">WebP</option>
                          <option value="image/gif">GIF</option>
                        </select>
                        <button onClick={handleAddBase64} disabled={!base64Value.trim()}
                          className="px-2 py-1 rounded-md text-xs font-medium text-white cursor-pointer disabled:opacity-50"
                          style={{ background: 'var(--color-primary)' }}>
                          Add
                        </button>
                      </div>
                      <textarea value={base64Value} onChange={e => setBase64Value(e.target.value)}
                        rows={3}
                        className="w-full px-2 py-1 rounded-md border text-xs font-mono outline-none resize-y"
                        style={inputStyle}
                        placeholder="Paste base64 string or data URI (data:image/png;base64,...)" />
                    </div>
                  )}
                </div>
              )}

              <div className="flex gap-2">
                {!testing ? (
                  <>
                    <button onClick={handleTest} disabled={!testInput.trim()}
                      className="flex-1 flex items-center justify-center gap-2 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer disabled:opacity-50"
                      style={{ background: 'var(--color-success)' }}>
                      <Play size={14} /> Test
                    </button>
                    {agent.status === 'PUBLISHED' && (
                      <button onClick={handleTriggerRun} disabled={!testInput.trim()}
                        className="flex items-center justify-center gap-2 px-3 py-2 rounded-lg text-sm font-medium cursor-pointer disabled:opacity-50 border"
                        style={{ borderColor: 'var(--color-border)' }}>
                        Run
                      </button>
                    )}
                  </>
                ) : (
                  <button onClick={handleStopTest}
                    className="flex-1 flex items-center justify-center gap-2 px-3 py-2 rounded-lg text-sm font-medium text-white cursor-pointer"
                    style={{ background: 'var(--color-error)' }}>
                    <Square size={14} /> Stop
                  </button>
                )}
              </div>
              <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
                Test uses current config directly. Run uses published config.
              </p>

              {/* Test output */}
              {(testOutput || testing) && (
                <div className="rounded-lg p-3 text-sm overflow-auto"
                  style={{ background: 'var(--color-bg-tertiary)', maxHeight: '400px' }}>
                  <pre className="whitespace-pre-wrap text-sm">
                    {testOutput || ''}
                    {testing && !testOutput && <Loader2 size={14} className="animate-spin inline" />}
                  </pre>
                  {testing && testOutput && (
                    <span className="inline-block w-1.5 h-4 ml-0.5 animate-pulse" style={{ background: 'var(--color-primary)' }} />
                  )}
                </div>
              )}
            </div>
          </div>

          {/* API Usage - collapsible */}
          {agent.status === 'PUBLISHED' && (
            <CollapsibleApiUsage agentId={agent.id} agentType={agent.type} />
          )}

          {/* Recent Runs - inline expandable */}
          <div className="rounded-xl border p-4"
            style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
            <div className="flex items-center justify-between mb-3">
              <h3 className="font-medium text-sm">Recent Runs</h3>
              <button onClick={loadRuns}
                className="text-xs px-2 py-1 rounded cursor-pointer"
                style={{ color: 'var(--color-primary)', background: 'var(--color-bg-tertiary)' }}>
                {runsLoading ? 'Loading...' : 'Refresh'}
              </button>
            </div>
            {runs.length === 0 ? (
              <p className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>No runs yet.</p>
            ) : (
              <div className="space-y-2 max-h-[500px] overflow-y-auto">
                {runs.map(r => (
                  <div key={r.id} className="rounded-lg border text-xs"
                    style={{ borderColor: 'var(--color-border)' }}>
                    <button className="w-full p-2 text-left cursor-pointer flex items-center justify-between"
                      onClick={() => toggleRunDetail(r.id)}>
                      <div className="flex items-center gap-2">
                        {expandedRunId === r.id ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                        <StatusBadge status={r.status} />
                        <span className="truncate max-w-32" style={{ color: 'var(--color-text-secondary)' }}>
                          {r.input || '-'}
                        </span>
                      </div>
                      <span style={{ color: 'var(--color-text-secondary)' }}>
                        {r.started_at ? new Date(r.started_at).toLocaleTimeString() : '-'}
                      </span>
                    </button>
                    {expandedRunId === r.id && (
                      <RunInlineDetail run={r} detail={expandedRunDetail} onViewFull={() => openRunModal(r.id)} />
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Run Detail Modal */}
      {modalRunDetail && (
        <RunDetailModal detail={modalRunDetail} onClose={() => setModalRunDetail(null)} />
      )}
    </div>
  );
}

function RunDetailModal({ detail, onClose }: { detail: AgentRunDetail; onClose: () => void }) {
  const [copied, setCopied] = useState('');
  const handleCopy = (text: string, key: string) => {
    navigator.clipboard.writeText(text);
    setCopied(key);
    setTimeout(() => setCopied(''), 2000);
  };

  const duration = detail.started_at && detail.completed_at
    ? (new Date(detail.completed_at).getTime() - new Date(detail.started_at).getTime()) / 1000
    : null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center" style={{ background: 'rgba(0,0,0,0.5)' }}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="rounded-xl border shadow-2xl w-[90vw] max-w-4xl max-h-[85vh] overflow-hidden flex flex-col"
        style={{ background: 'var(--color-bg)', borderColor: 'var(--color-border)' }}>
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b" style={{ borderColor: 'var(--color-border)' }}>
          <div className="flex items-center gap-3">
            <h2 className="text-lg font-semibold">Run Detail</h2>
            <StatusBadge status={detail.status} />
            <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              {detail.started_at ? new Date(detail.started_at).toLocaleString() : ''}
            </span>
          </div>
          <div className="flex items-center gap-4">
            <div className="flex gap-4 text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              {duration !== null && <span>{duration < 1 ? `${(duration * 1000).toFixed(0)}ms` : `${duration.toFixed(1)}s`}</span>}
              <span>{(detail.token_usage?.input || 0) + (detail.token_usage?.output || 0)} tokens</span>
            </div>
            <button onClick={onClose} className="cursor-pointer p-1 rounded hover:bg-[var(--color-bg-tertiary)]">
              <X size={18} />
            </button>
          </div>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-auto p-6 space-y-4">
          {/* I/O */}
          <div className="grid grid-cols-2 gap-4">
            {detail.input && (
              <div>
                <div className="flex items-center justify-between mb-1">
                  <span className="text-sm font-medium">Input</span>
                  <button onClick={() => handleCopy(detail.input, 'in')}
                    className="flex items-center gap-1 text-xs px-1.5 py-0.5 rounded cursor-pointer"
                    style={{ color: 'var(--color-text-secondary)', background: 'var(--color-bg-tertiary)' }}>
                    {copied === 'in' ? <><Check size={10} style={{ color: 'var(--color-success)' }} /> Copied</> : <><Copy size={10} /> Copy</>}
                  </button>
                </div>
                <pre className="text-sm whitespace-pre-wrap p-3 rounded-lg overflow-auto max-h-40"
                  style={{ background: 'var(--color-bg-tertiary)' }}>{detail.input}</pre>
              </div>
            )}
            {detail.output && (
              <div>
                <div className="flex items-center justify-between mb-1">
                  <span className="text-sm font-medium">Output</span>
                  <button onClick={() => handleCopy(detail.output, 'out')}
                    className="flex items-center gap-1 text-xs px-1.5 py-0.5 rounded cursor-pointer"
                    style={{ color: 'var(--color-text-secondary)', background: 'var(--color-bg-tertiary)' }}>
                    {copied === 'out' ? <><Check size={10} style={{ color: 'var(--color-success)' }} /> Copied</> : <><Copy size={10} /> Copy</>}
                  </button>
                </div>
                <pre className="text-sm whitespace-pre-wrap p-3 rounded-lg overflow-auto max-h-40"
                  style={{ background: 'var(--color-bg-tertiary)' }}>{detail.output}</pre>
              </div>
            )}
          </div>
          {detail.error && (
            <pre className="text-sm whitespace-pre-wrap p-3 rounded-lg" style={{ background: 'rgba(239,68,68,0.1)', color: 'var(--color-error)' }}>
              {detail.error}
            </pre>
          )}

          {/* Transcript */}
          {detail.transcript && detail.transcript.length > 0 && (
            <div>
              <h3 className="text-sm font-medium mb-2">Transcript ({detail.transcript.length})</h3>
              <div className="space-y-2">
                {detail.transcript.map((entry, i) => (
                  <div key={i} className="p-3 rounded-lg border text-sm"
                    style={{ borderColor: 'var(--color-border)' }}>
                    <div className="flex items-center gap-2 mb-1">
                      <span className="text-xs font-medium px-1.5 py-0.5 rounded"
                        style={{
                          background: entry.role === 'user' ? 'rgba(59,130,246,0.1)' : entry.role === 'assistant' ? 'rgba(34,197,94,0.1)' : entry.role === 'tool' ? 'rgba(245,158,11,0.1)' : 'rgba(139,92,246,0.1)',
                          color: entry.role === 'user' ? '#3b82f6' : entry.role === 'assistant' ? '#22c55e' : entry.role === 'tool' ? '#f59e0b' : '#8b5cf6',
                        }}>
                        {entry.role}
                      </span>
                      {entry.name && <span className="text-xs font-mono" style={{ color: 'var(--color-text-secondary)' }}>{entry.name}</span>}
                      {entry.ts && <span className="text-xs ml-auto" style={{ color: 'var(--color-text-secondary)' }}>{new Date(entry.ts).toLocaleTimeString()}</span>}
                    </div>
                    {entry.content && (
                      <pre className="text-xs whitespace-pre-wrap mt-1" style={{ color: 'var(--color-text)' }}>{entry.content}</pre>
                    )}
                    {entry.args && (
                      <pre className="text-xs whitespace-pre-wrap mt-1 p-2 rounded overflow-auto max-h-24"
                        style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                        {tryFormatJson(entry.args)}
                      </pre>
                    )}
                    {entry.result && (
                      <pre className="text-xs whitespace-pre-wrap mt-1 p-2 rounded overflow-auto max-h-24"
                        style={{ background: 'var(--color-bg-tertiary)', color: 'var(--color-text-secondary)' }}>
                        {tryFormatJson(entry.result)}
                      </pre>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

function tryFormatJson(str: string): string {
  try { return JSON.stringify(JSON.parse(str), null, 2); } catch { return str; }
}

function ToolSection({ title, color, items, selectedIds, onToggle }: {
  title: string;
  color: string;
  items: ToolRegistryView[];
  selectedIds: string[];
  onToggle: (id: string, selected: boolean) => void;
}) {
  const [open, setOpen] = useState(false);
  const selectedCount = items.filter(t => selectedIds.includes(t.id)).length;

  return (
    <div className="mt-2 rounded-lg border" style={{ borderColor: 'var(--color-border)' }}>
      <button
        className="w-full flex items-center justify-between px-3 py-2 text-xs font-medium cursor-pointer hover:bg-[var(--color-bg-tertiary)] rounded-lg"
        onClick={() => setOpen(!open)}>
        <div className="flex items-center gap-2">
          <span className="w-2 h-2 rounded-full" style={{ background: color }} />
          <span>{title}</span>
          {selectedCount > 0 && (
            <span className="text-[10px] px-1 rounded-full" style={{ background: `${color}20`, color }}>
              {selectedCount}
            </span>
          )}
        </div>
        <div className="flex items-center gap-2">
          <span className="text-[10px]" style={{ color: 'var(--color-text-secondary)' }}>{items.length} available</span>
          {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        </div>
      </button>
      {open && (
        <div className="border-t max-h-[300px] overflow-auto" style={{ borderColor: 'var(--color-border)' }}>
          {items.length === 0 ? (
            <div className="px-3 py-2 text-xs" style={{ color: 'var(--color-text-secondary)' }}>No {title.toLowerCase()} tools available</div>
          ) : (
            items.map(t => {
              const selected = selectedIds.includes(t.id);
              return (
                <label key={t.id}
                  className="flex items-center gap-2.5 px-3 py-1.5 text-xs cursor-pointer hover:bg-[var(--color-bg-tertiary)]"
                  style={{ borderBottom: '1px solid var(--color-border)' }}>
                  <input type="checkbox" checked={selected} onChange={() => onToggle(t.id, selected)}
                    className="accent-current" style={{ accentColor: color }} />
                  <span className="font-medium flex-1">{t.name}</span>
                  {t.description && (
                    <span className="text-[10px] truncate max-w-[200px]" style={{ color: 'var(--color-text-secondary)' }}>{t.description}</span>
                  )}
                </label>
              );
            })
          )}
        </div>
      )}
    </div>
  );
}

function RunInlineDetail({ run, detail, onViewFull }: {
  run: AgentRun;
  detail: AgentRunDetail | null;
  onViewFull: () => void;
}) {
  const [copied, setCopied] = useState('');
  const handleCopy = (text: string, key: string) => {
    navigator.clipboard.writeText(text);
    setCopied(key);
    setTimeout(() => setCopied(''), 2000);
  };

  return (
    <div className="border-t p-3 space-y-2" style={{ borderColor: 'var(--color-border)' }}>
      {run.input && (
        <div>
          <div className="flex items-center justify-between mb-1">
            <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>Input</span>
            <button onClick={() => handleCopy(run.input, 'in')}
              className="flex items-center gap-1 text-xs px-1.5 py-0.5 rounded cursor-pointer"
              style={{ color: 'var(--color-text-secondary)', background: 'var(--color-bg-tertiary)' }}>
              {copied === 'in' ? <><Check size={10} style={{ color: 'var(--color-success)' }} /> Copied</> : <><Copy size={10} /> Copy</>}
            </button>
          </div>
          <pre className="text-xs whitespace-pre-wrap p-2 rounded-lg overflow-auto max-h-24"
            style={{ background: 'var(--color-bg-tertiary)' }}>{run.input}</pre>
        </div>
      )}
      {run.output && (
        <div>
          <div className="flex items-center justify-between mb-1">
            <span className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>Output</span>
            <button onClick={() => handleCopy(run.output, 'out')}
              className="flex items-center gap-1 text-xs px-1.5 py-0.5 rounded cursor-pointer"
              style={{ color: 'var(--color-text-secondary)', background: 'var(--color-bg-tertiary)' }}>
              {copied === 'out' ? <><Check size={10} style={{ color: 'var(--color-success)' }} /> Copied</> : <><Copy size={10} /> Copy</>}
            </button>
          </div>
          <pre className="text-xs whitespace-pre-wrap p-2 rounded-lg overflow-auto max-h-48"
            style={{ background: 'var(--color-bg-tertiary)' }}>{run.output}</pre>
        </div>
      )}
      {run.error && (
        <pre className="text-xs whitespace-pre-wrap p-2 rounded-lg" style={{ color: 'var(--color-error)' }}>
          {run.error}
        </pre>
      )}
      {detail && (
        <div className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
          <span>Tokens: {(detail.token_usage?.input || 0) + (detail.token_usage?.output || 0)}</span>
          {detail.started_at && detail.completed_at && (
            <span className="ml-3">
              Duration: {((new Date(detail.completed_at).getTime() - new Date(detail.started_at).getTime()) / 1000).toFixed(1)}s
            </span>
          )}
        </div>
      )}
      <button onClick={onViewFull}
        className="text-xs px-2 py-1 rounded cursor-pointer"
        style={{ color: 'var(--color-primary)', background: 'var(--color-bg-tertiary)' }}>
        View Full Detail →
      </button>
    </div>
  );
}

const JSON_SCHEMA_PLACEHOLDER = `{
  "title": "Response",
  "type": "object",
  "properties": {
    "result": {
      "type": "string",
      "description": "The result text"
    },
    "score": {
      "type": "integer",
      "description": "Confidence score 0-100"
    }
  },
  "required": ["result", "score"]
}`;

const JAVA_CLASS_PLACEHOLDER = `public class SentimentResult {
    @CoreAiParameter(description = "sentiment label")
    public String sentiment;

    @CoreAiParameter(description = "confidence 0-100")
    public int confidence;

    public List<String> keywords;
}`;

function ResponseSchemaEditor({ value, onChange, inputStyle }: {
  value: unknown;
  onChange: (v: unknown) => void;
  inputStyle: React.CSSProperties;
}) {
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<'json' | 'java'>('json');
  const [jsonText, setJsonText] = useState('');
  const [jsonError, setJsonError] = useState('');
  const [javaCode, setJavaCode] = useState('');
  const [converting, setConverting] = useState(false);
  const [convertError, setConvertError] = useState('');

  useEffect(() => {
    if (typeof value === 'string' && value) {
      try { setJsonText(JSON.stringify(JSON.parse(value), null, 2)); } catch { setJsonText(value); }
    } else {
      setJsonText('');
    }
  }, []);

  const handleJsonChange = (raw: string) => {
    setJsonText(raw);
    if (!raw.trim()) { setJsonError(''); onChange(null); return; }
    try {
      JSON.parse(raw);
      setJsonError('');
      onChange(raw);
    } catch (e) {
      setJsonError(e instanceof Error ? e.message : 'Invalid JSON');
    }
  };

  const handleConvert = async () => {
    if (!javaCode.trim()) return;
    setConverting(true);
    setConvertError('');
    try {
      const res = await api.agents.javaToSchema(javaCode);
      if (res.error) {
        setConvertError(res.error);
      } else if (res.schema) {
        const formatted = JSON.stringify(JSON.parse(res.schema), null, 2);
        setJsonText(formatted);
        onChange(res.schema);
        setMode('json');
      }
    } catch (e) {
      setConvertError(e instanceof Error ? e.message : 'Convert failed');
    } finally {
      setConverting(false);
    }
  };

  const hasValue = open ? false : (typeof value === 'string' && value.trim().length > 0);

  return (
    <div className="rounded-xl border"
      style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
      <button
        className="w-full flex items-center justify-between p-4 text-sm font-medium cursor-pointer hover:bg-[var(--color-bg-tertiary)] rounded-xl"
        onClick={() => setOpen(!open)}>
        <div className="flex items-center gap-2">
          <Code size={14} style={{ color: 'var(--color-text-secondary)' }} />
          <span>Response Schema</span>
          <span className="text-xs font-normal" style={{ color: 'var(--color-text-secondary)' }}>(optional)</span>
          {!open && hasValue && (
            <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              schema set
            </span>
          )}
        </div>
        {open ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
      </button>
      {open && (
        <div className="px-4 pb-4 border-t" style={{ borderColor: 'var(--color-border)' }}>
          <div className="flex items-center gap-1 pt-3 mb-3">
            {(['json', 'java'] as const).map(m => (
              <button key={m} onClick={() => setMode(m)}
                className="text-xs px-2 py-1 rounded cursor-pointer"
                style={{
                  background: mode === m ? 'var(--color-primary)' : 'var(--color-bg-tertiary)',
                  color: mode === m ? 'white' : 'var(--color-text-secondary)',
                }}>
                {m === 'json' ? 'JSON Schema' : 'Java Class'}
              </button>
            ))}
          </div>

          {mode === 'json' ? (
            <>
              <textarea value={jsonText} onChange={e => handleJsonChange(e.target.value)}
                rows={10}
                className="w-full px-3 py-2 rounded-lg border text-sm font-mono outline-none resize-y"
                style={{ ...inputStyle, borderColor: jsonError ? 'var(--color-error)' : inputStyle.borderColor }}
                placeholder={JSON_SCHEMA_PLACEHOLDER} />
              {jsonError && <p className="text-xs mt-1" style={{ color: 'var(--color-error)' }}>{jsonError}</p>}
              {!jsonText.trim() && (
                <p className="text-xs mt-1.5" style={{ color: 'var(--color-text-secondary)' }}>
                  Optional. Use standard JSON Schema to define structured output format.
                </p>
              )}
            </>
          ) : (
            <>
              <textarea value={javaCode} onChange={e => setJavaCode(e.target.value)}
                rows={10}
                className="w-full px-3 py-2 rounded-lg border text-sm font-mono outline-none resize-y"
                style={inputStyle}
                placeholder={JAVA_CLASS_PLACEHOLDER} />
              <div className="flex items-center gap-2 mt-2">
                <button onClick={handleConvert} disabled={!javaCode.trim() || converting}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-white cursor-pointer disabled:opacity-50"
                  style={{ background: 'var(--color-primary)' }}>
                  {converting ? <><Loader2 size={12} className="animate-spin" /> Converting...</> : 'Convert to JSON Schema'}
                </button>
                <span className="text-[10px]" style={{ color: 'var(--color-text-secondary)' }}>
                  Uses LLM to convert Java class to JSON Schema
                </span>
              </div>
              {convertError && <p className="text-xs mt-1" style={{ color: 'var(--color-error)' }}>{convertError}</p>}
            </>
          )}
        </div>
      )}
    </div>
  );
}

function CollapsibleApiUsage({ agentId, agentType }: { agentId: string; agentType?: string }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="rounded-xl border"
      style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
      <button onClick={() => setOpen(o => !o)}
        className="w-full flex items-center justify-between p-4 cursor-pointer text-left">
        <div className="flex items-center gap-2">
          <Code size={14} style={{ color: 'var(--color-text-secondary)' }} />
          <h3 className="font-medium text-sm">API Usage</h3>
        </div>
        {open ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
      </button>
      {open && (
        <div className="border-t" style={{ borderColor: 'var(--color-border)' }}>
          <ApiUsagePanel agentId={agentId} agentType={agentType} />
        </div>
      )}
    </div>
  );
}

function ApiUsagePanel({ agentId, agentType }: { agentId: string; agentType?: string }) {
  const [copied, setCopied] = useState('');
  const isLlmCall = agentType === 'LLM_CALL';
  const [tab, setTab] = useState<'call' | 'trigger' | 'session'>(isLlmCall ? 'call' : 'trigger');
  const apiKey = localStorage.getItem('apiKey') || '<YOUR_API_KEY>';
  const baseUrl = window.location.origin;

  const callCurl = `curl -X POST '${baseUrl}/api/agents/${agentId}/call' \\
  -H 'Content-Type: application/json' \\
  -H 'Authorization: Bearer ${apiKey}' \\
  -d '{"input": "Hello, what can you do?"}'

# Response:
# {"output": "...", "token_usage": {"input": 27, "output": 42}, "run_id": "..."}`;

  const triggerCurl = `curl -X POST '${baseUrl}/api/runs/agent/${agentId}/trigger' \\
  -H 'Content-Type: application/json' \\
  -H 'Authorization: Bearer ${apiKey}' \\
  -d '{"input": "Hello, what can you do?"}'`;

  const sessionCurl = `# 1. Create session
SESSION_ID=$(curl -s -X POST '${baseUrl}/api/sessions' \\
  -H 'Content-Type: application/json' \\
  -H 'Authorization: Bearer ${apiKey}' \\
  -d '{"agent_id": "${agentId}"}' | jq -r '.sessionId')

# 2. Connect SSE (in another terminal)
curl -N -X PUT "${baseUrl}/api/sessions/events?agent-session-id=$SESSION_ID" \\
  -H 'Authorization: Bearer ${apiKey}' \\
  -H 'Accept: text/event-stream'

# 3. Send message
curl -X POST "${baseUrl}/api/sessions/$SESSION_ID/messages" \\
  -H 'Content-Type: application/json' \\
  -H 'Authorization: Bearer ${apiKey}' \\
  -d '{"message": "Hello!"}'`;

  const handleCopy = (text: string, key: string) => {
    navigator.clipboard.writeText(text);
    setCopied(key);
    setTimeout(() => setCopied(''), 2000);
  };

  const allTabs = [
    { key: 'call' as const, label: 'Call (Sync)' },
    { key: 'trigger' as const, label: 'Trigger (Async)' },
    { key: 'session' as const, label: 'Session (Stream)' },
  ];
  const tabs = isLlmCall
    ? allTabs.filter(t => t.key === 'call')
    : allTabs.filter(t => t.key !== 'call');

  const currentCode = tab === 'call' ? callCurl : tab === 'trigger' ? triggerCurl : sessionCurl;

  return (
    <div>
      <div className="flex items-center justify-between px-4 py-3">
        <div />
        <button onClick={() => handleCopy(currentCode, tab)}
          className="flex items-center gap-1 text-xs px-2 py-1 rounded cursor-pointer"
          style={{ color: 'var(--color-text-secondary)', background: 'var(--color-bg-tertiary)' }}>
          {copied === tab ? <><Check size={12} style={{ color: 'var(--color-success)' }} /> Copied</> : <><Copy size={12} /> Copy</>}
        </button>
      </div>
      <div className="flex border-b" style={{ borderColor: 'var(--color-border)' }}>
        {tabs.map(t => (
          <button key={t.key} onClick={() => setTab(t.key)}
            className="px-4 py-1.5 text-xs font-medium cursor-pointer"
            style={{
              color: tab === t.key ? 'var(--color-primary)' : 'var(--color-text-secondary)',
              borderBottom: tab === t.key ? '2px solid var(--color-primary)' : '2px solid transparent',
            }}>
            {t.label}
          </button>
        ))}
      </div>
      <pre className="px-4 py-3 text-xs font-mono overflow-auto whitespace-pre-wrap"
        style={{ maxHeight: '200px', color: 'var(--color-text-secondary)' }}>
        {currentCode}
      </pre>
    </div>
  );
}

function SandboxConfigSection({ config, onChange, onEnvChange, inputStyle }: {
  config?: SandboxConfig;
  onChange: (key: string, value: unknown) => void;
  onEnvChange: (envVars: Record<string, string> | undefined) => void;
  inputStyle: React.CSSProperties;
}) {
  const [open, setOpen] = useState(false);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const envRowIdRef = useRef(0);
  const [envRows, setEnvRows] = useState<{ id: number; key: string; value: string }[]>(() => {
    const entries = Object.entries(config?.env_vars || {});
    return entries.map(([k, v]) => ({ id: ++envRowIdRef.current, key: k, value: v ?? '' }));
  });

  const rowsToMap = (rows: typeof envRows) => {
    const map: Record<string, string> = {};
    for (const r of rows) {
      const k = r.key.trim();
      if (!k) continue;
      map[k] = r.value;
    }
    return Object.keys(map).length > 0 ? map : undefined;
  };

  const emitEnv = (rows: typeof envRows) => {
    onEnvChange(rowsToMap(rows));
  };

  const addEnvRow = () => {
    setEnvRows(prev => {
      const next = [...prev, { id: ++envRowIdRef.current, key: '', value: '' }];
      emitEnv(next);
      return next;
    });
  };

  const updateEnvRow = (id: number, patch: Partial<{ key: string; value: string }>) => {
    setEnvRows(prev => {
      const next = prev.map(r => r.id === id ? { ...r, ...patch } : r);
      emitEnv(next);
      return next;
    });
  };

  const removeEnvRow = (id: number) => {
    setEnvRows(prev => {
      const next = prev.filter(r => r.id !== id);
      emitEnv(next);
      return next;
    });
  };

  const hasEnv = envRows.some(r => r.key.trim() !== '');

  return (
    <div className="rounded-xl border"
      style={{ background: 'var(--color-bg-secondary)', borderColor: 'var(--color-border)' }}>
      <button
        className="w-full flex items-center justify-between p-4 text-sm font-medium cursor-pointer hover:bg-[var(--color-bg-tertiary)] rounded-xl"
        onClick={() => setOpen(!open)}>
        <div className="flex items-center gap-2">
          <Wrench size={14} style={{ color: 'var(--color-text-secondary)' }} />
          <span>Sandbox Configuration</span>
          <span className="text-xs font-normal" style={{ color: 'var(--color-text-secondary)' }}>(optional)</span>
          {!open && (config?.image || hasEnv) && (
            <span className="text-xs" style={{ color: 'var(--color-text-secondary)' }}>
              {config?.image ? 'image set' : ''}{config?.image && hasEnv ? ', ' : ''}{hasEnv ? `${envRows.filter(r => r.key.trim()).length} env vars` : ''}
            </span>
          )}
        </div>
        {open ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
      </button>
      {open && (
        <div className="px-4 pb-4 space-y-3 border-t" style={{ borderColor: 'var(--color-border)' }}>
          {/* Image */}
          <div className="pt-3">
            <label className="block text-xs font-medium mb-1" style={{ color: 'var(--color-text-secondary)' }}>Image</label>
            <input
              value={config?.image || ''}
              onChange={e => onChange('image', e.target.value || undefined)}
              className="w-full px-3 py-2 rounded-lg border text-sm outline-none"
              style={inputStyle}
              placeholder="chancetop/core-ai-sandbox-runtime:latest"
            />
          </div>

          {/* Environment Variables */}
          <div>
            <div className="flex items-center justify-between mb-1">
              <label className="text-xs font-medium" style={{ color: 'var(--color-text-secondary)' }}>Environment Variables</label>
              <button
                onClick={addEnvRow}
                className="inline-flex items-center gap-1 px-2 py-1 rounded text-xs cursor-pointer"
                style={{ color: 'var(--color-primary)', background: 'var(--color-bg-tertiary)' }}>
                <Plus size={12} /> Add
              </button>
            </div>
            <div className="space-y-1.5">
              {envRows.map(row => (
                <div key={row.id} className="flex gap-2 items-center">
                  <input
                    value={row.key}
                    onChange={e => updateEnvRow(row.id, { key: e.target.value })}
                    className="flex-1 px-3 py-1.5 rounded-lg border text-sm outline-none"
                    style={{ ...inputStyle, flexBasis: '40%' }}
                    placeholder="KEY"
                  />
                  <input
                    value={row.value}
                    onChange={e => updateEnvRow(row.id, { value: e.target.value })}
                    className="flex-1 px-3 py-1.5 rounded-lg border text-sm outline-none"
                    style={{ ...inputStyle, flexBasis: '60%' }}
                    placeholder="VALUE"
                  />
                  <button
                    onClick={() => removeEnvRow(row.id)}
                    className="p-1.5 rounded-lg cursor-pointer flex-shrink-0"
                    style={{ color: 'var(--color-error)' }}
                    title="Remove">
                    <X size={14} />
                  </button>
                </div>
              ))}
              {envRows.length === 0 && (
                <p className="text-xs" style={{ color: 'var(--color-text-tertiary)' }}>No custom environment variables</p>
              )}
            </div>
          </div>

          {/* Advanced */}
          <div>
            <button
              className="flex items-center gap-1 text-xs cursor-pointer"
              style={{ color: 'var(--color-text-secondary)' }}
              onClick={() => setAdvancedOpen(!advancedOpen)}>
              {advancedOpen ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
              Advanced
            </button>
            {advancedOpen && (
              <div className="mt-2 space-y-3">
                <div className="grid grid-cols-3 gap-3">
                  <div>
                    <label className="block text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>Memory (MB)</label>
                    <input
                      type="number" min={64} max={2048}
                      value={config?.memory_limit_mb ?? ''}
                      onChange={e => onChange('memory_limit_mb', e.target.value ? Number(e.target.value) : undefined)}
                      className="w-full px-2 py-1.5 rounded-lg border text-sm outline-none"
                      style={inputStyle}
                    />
                  </div>
                  <div>
                    <label className="block text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>CPU (m)</label>
                    <input
                      type="number" min={100} max={2000} step={100}
                      value={config?.cpu_limit_millicores ?? ''}
                      onChange={e => onChange('cpu_limit_millicores', e.target.value ? Number(e.target.value) : undefined)}
                      className="w-full px-2 py-1.5 rounded-lg border text-sm outline-none"
                      style={inputStyle}
                    />
                  </div>
                  <div>
                    <label className="block text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>Timeout (s)</label>
                    <input
                      type="number" min={300} max={7200}
                      value={config?.timeout_seconds ?? ''}
                      onChange={e => onChange('timeout_seconds', e.target.value ? Number(e.target.value) : undefined)}
                      className="w-full px-2 py-1.5 rounded-lg border text-sm outline-none"
                      style={inputStyle}
                    />
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <label className="flex items-center gap-2 text-xs cursor-pointer" style={{ color: 'var(--color-text-secondary)' }}>
                    <input
                      type="checkbox"
                      checked={config?.network_enabled || false}
                      onChange={e => onChange('network_enabled', e.target.checked || undefined)}
                      className="accent-current"
                      style={{ accentColor: 'var(--color-primary)' }}
                    />
                    Network enabled
                  </label>
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>Git Repo URL</label>
                    <input
                      value={config?.git_repo_url || ''}
                      onChange={e => onChange('git_repo_url', e.target.value || undefined)}
                      className="w-full px-2 py-1.5 rounded-lg border text-sm outline-none"
                      style={inputStyle}
                    />
                  </div>
                  <div>
                    <label className="block text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>Git Branch</label>
                    <input
                      value={config?.git_branch || ''}
                      onChange={e => onChange('git_branch', e.target.value || undefined)}
                      className="w-full px-2 py-1.5 rounded-lg border text-sm outline-none"
                      style={inputStyle}
                      placeholder="main"
                    />
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className="block text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>Tmp Size Limit</label>
                    <input
                      value={config?.tmp_size_limit || ''}
                      onChange={e => onChange('tmp_size_limit', e.target.value || undefined)}
                      className="w-full px-2 py-1.5 rounded-lg border text-sm outline-none"
                      style={inputStyle}
                      placeholder="512Mi"
                    />
                  </div>
                  <div>
                    <label className="block text-xs mb-1" style={{ color: 'var(--color-text-secondary)' }}>Max Async Tasks</label>
                    <input
                      type="number" min={1} max={20}
                      value={config?.max_async_tasks ?? ''}
                      onChange={e => onChange('max_async_tasks', e.target.value ? Number(e.target.value) : undefined)}
                      className="w-full px-2 py-1.5 rounded-lg border text-sm outline-none"
                      style={inputStyle}
                    />
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
