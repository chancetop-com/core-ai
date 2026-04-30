interface Tool {
  name: string;
  description: string;
  category: string;
  parameters: { name: string; description: string; required?: boolean }[];
}

const tools: Tool[] = [
  {
    name: 'write_todos',
    description: 'Create and manage a structured task list to track progress, organize complex tasks, and demonstrate thoroughness.',
    category: 'Plan',
    parameters: [
      { name: 'todos', description: 'List of tasks with content and status (PENDING/IN_PROGRESS/COMPLETED)', required: true },
    ],
  },
  {
    name: 'task',
    description: 'Launch a new agent to handle complex, multistep tasks autonomously. Supports sub-agent types and background execution.',
    category: 'Plan',
    parameters: [
      { name: 'task_id', description: 'Unique task ID', required: true },
      { name: 'description', description: 'Short (3-5 words) description of the task', required: true },
      { name: 'prompt', description: 'Detailed task for the agent to perform', required: true },
      { name: 'subagent_type', description: 'Type of specialized agent (e.g., explore-agent, code-simplifier-agent)' },
      { name: 'run_in_background', description: 'Set to true to run this agent in the background' },
    ],
  },
  {
    name: 'async_task_output',
    description: 'Query status and output of async background tasks. Supports poll to check result and cancel to stop a running task.',
    category: 'Plan',
    parameters: [
      { name: 'action', description: 'Operation: "poll" to check result, "cancel" to stop running task', required: true },
      { name: 'task_id', description: 'Task ID to poll or cancel', required: true },
    ],
  },
  {
    name: 'read_file',
    description: 'Read a file from the local filesystem. Supports text files (with line offset and limit), images (PNG, JPG, GIF, etc.), and PDFs.',
    category: 'File Operations',
    parameters: [
      { name: 'file_path', description: 'Absolute path of the file to read', required: true },
      { name: 'offset', description: 'The line number to start reading from' },
      { name: 'limit', description: 'The number of lines to read' },
    ],
  },
  {
    name: 'write_file',
    description: 'Write or overwrite a file to the local filesystem. Supports creating parent directories automatically.',
    category: 'File Operations',
    parameters: [
      { name: 'file_path', description: 'Absolute path to the file to write (must be absolute, not relative)', required: true },
      { name: 'content', description: 'The content to write to the file', required: true },
    ],
  },
  {
    name: 'edit_file',
    description: 'Perform exact string replacements in files. Supports fuzzy matching and replace_all option for global replacement.',
    category: 'File Operations',
    parameters: [
      { name: 'file_path', description: 'Absolute path to the file to modify', required: true },
      { name: 'old_string', description: 'The text to replace', required: true },
      { name: 'new_string', description: 'The text to replace it with', required: true },
      { name: 'replace_all', description: 'Replace all occurrences of old_string (default: false)' },
    ],
  },
  {
    name: 'hash_read_file',
    description: 'Read a file with hashline anchors (LINE#ID:content). Used with hash_edit_file for conflict-safe edits. Lines over 2000 chars are truncated.',
    category: 'File Operations',
    parameters: [
      { name: 'file_path', description: 'Absolute path to read', required: true },
      { name: 'offset', description: 'Line number to start from' },
      { name: 'limit', description: 'Number of lines to read' },
    ],
  },
  {
    name: 'hash_edit_file',
    description: 'Precise file edits using LINE#ID anchors from hash_read_file. Supports append, prepend, range replace, delete, and move operations. All edits validated atomically.',
    category: 'File Operations',
    parameters: [
      { name: 'edits', description: 'Array of edit entries with path, loc, content, delete, or move fields', required: true },
    ],
  },
  {
    name: 'glob_file',
    description: 'Fast file pattern matching tool. Find files by glob patterns like "**/*.js" or "src/**/*.ts".',
    category: 'File Operations',
    parameters: [
      { name: 'pattern', description: 'The glob pattern to match files against', required: true },
      { name: 'path', description: 'The directory to search in. If not specified, the current working directory will be used.' },
    ],
  },
  {
    name: 'grep_file',
    description: 'Powerful content search tool built on ripgrep. Supports regex and file pattern filtering. Results with file paths and line numbers.',
    category: 'File Operations',
    parameters: [
      { name: 'pattern', description: 'The regex pattern to search for in file contents', required: true },
      { name: 'path', description: 'The directory to search in. Defaults to the current working directory.' },
      { name: 'include', description: 'File pattern to include in the search (e.g., "*.js", "*.{ts,tsx}")' },
    ],
  },
  {
    name: 'web_fetch',
    description: 'Fetch content from URLs via HTTP. Supports GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS methods. Converts HTML to clean markdown.',
    category: 'Web',
    parameters: [
      { name: 'url', description: 'The URL to fetch content from', required: true },
      { name: 'method', description: 'HTTP method: GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS', required: true },
      { name: 'format', description: 'Output format: markdown (default), text, or html' },
      { name: 'content_type', description: 'Content-Type header (e.g., application/json)' },
      { name: 'body', description: 'Request body for POST/PUT/PATCH requests' },
    ],
  },
  {
    name: 'web_search',
    description: 'Search the web for up-to-date information. Returns formatted results with titles, URLs, and snippets.',
    category: 'Web',
    parameters: [
      { name: 'query', description: 'The search query to use', required: true },
      { name: 'num_results', description: 'Number of search results to return (default: 10)' },
      { name: 'allowed_domains', description: 'Only include results from specific domains' },
      { name: 'blocked_domains', description: 'Exclude results from specific domains' },
    ],
  },
  {
    name: 'run_bash_command',
    description: 'Execute bash commands in a persistent shell session. Supports background execution, timeout, and mode (read/write). OS-aware.',
    category: 'Code Execution',
    parameters: [
      { name: 'workspace', description: 'Working directory for command execution' },
      { name: 'command', description: 'Command string to execute', required: true },
      { name: 'mode', description: '"read" (no modifications) or "write" (modifies files/system)', required: true },
      { name: 'description', description: 'Clear, concise description of what this command does in active voice' },
      { name: 'timeout', description: 'Optional timeout in milliseconds (max 600000)' },
      { name: 'run_in_background', description: 'Set to true to run this command in the background' },
    ],
  },
  {
    name: 'run_python_script',
    description: 'Execute Python scripts and return output (stdout/stderr). Supports inline code or script file path, and async mode for long-running scripts.',
    category: 'Code Execution',
    parameters: [
      { name: 'code', description: 'Python code to execute' },
      { name: 'script_path', description: 'Path to a Python script file to execute' },
      { name: 'async', description: 'Set to true for long-running scripts, use async_task_output to check progress' },
    ],
  },
  {
    name: 'caption_image',
    description: 'Generate a caption for an image based on its content and a query. Uses vision-capable LLM.',
    category: 'Multimodal',
    parameters: [
      { name: 'query', description: 'Query to ask about the image', required: true },
      { name: 'url', description: 'URL of the image', required: true },
    ],
  },
  {
    name: 'summarize_pdf',
    description: 'Read and analyze PDF documents from a URL. Answers questions about PDF content using LLM.',
    category: 'Multimodal',
    parameters: [
      { name: 'query', description: 'The question or instruction about the PDF content', required: true },
      { name: 'url', description: 'URL of the PDF document', required: true },
    ],
  },
  {
    name: 'rag_search',
    description: 'Perform RAG (Retrieval-Augmented Generation) queries. Pipeline includes query rewriting, embedding, similarity search, and reranking.',
    category: 'Multimodal',
    parameters: [
      { name: 'query', description: 'Search query to find relevant documents', required: true },
      { name: 'topK', description: 'Number of top documents to return (default: 5)' },
      { name: 'threshold', description: 'Minimum similarity threshold (default: 0.0)' },
    ],
  },
  {
    name: 'use_skill',
    description: 'Load and use skills by name. Skills are reusable instruction sets with resources. Returns full skill content and resource listings.',
    category: 'Skills',
    parameters: [
      { name: 'name', description: 'Skill qualified name to load', required: true },
    ],
  },
  {
    name: 'read_skill_resource',
    description: 'Read a resource file belonging to a loaded skill (e.g., references/*.md, scripts/*.py). Use after use_skill to load additional resources.',
    category: 'Skills',
    parameters: [
      { name: 'name', description: 'Skill qualified name', required: true },
      { name: 'path', description: 'Resource path relative to skill directory', required: true },
    ],
  },
  {
    name: 'activate_tools',
    description: 'Search and activate additional discoverable tools. In catalog mode (≤30 tools), lists all available tools. In search mode (>30 tools), provides keyword search.',
    category: 'Tool Management',
    parameters: [
      { name: 'tool_names', description: 'Names of tools to activate' },
      { name: 'query', description: 'Search keyword (only in search mode with >30 tools)' },
    ],
  },
  {
    name: 'add_mcp_server',
    description: 'Add and connect to an MCP (Model Context Protocol) server at runtime. Supports STDIO (command + args) and HTTP/SSE (url) connections.',
    category: 'Tool Management',
    parameters: [
      { name: 'name', description: 'Server name identifier', required: true },
      { name: 'command', description: 'Command to launch STDIO server (e.g., npx)' },
      { name: 'args', description: 'Command arguments as space-separated string' },
      { name: 'url', description: 'URL for HTTP/SSE server' },
    ],
  },
  {
    name: 'memory_tool',
    description: 'Save or forget persistent user facts and preferences across sessions. Requires user authorization for execution.',
    category: 'Memory',
    parameters: [
      { name: 'action', description: '"save" to remember a fact, "forget" to remove a memory', required: true },
      { name: 'content', description: 'Fact to remember or keyword to forget', required: true },
    ],
  },
  {
    name: 'search_memory_tool',
    description: 'Search and recall relevant memories about the user. Personalizes responses based on past interactions and preferences.',
    category: 'Memory',
    parameters: [
      { name: 'query', description: 'Search keywords to find relevant memories', required: true },
    ],
  },
  {
    name: 'ask_user',
    description: 'Ask the user a question and wait for their response. Used for clarification, confirmation, or gathering additional information.',
    category: 'User Interaction',
    parameters: [
      { name: 'question', description: 'The question to ask the user', required: true },
    ],
  },
];

const categoryColors: Record<string, string> = {
  Plan: 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300',
  'File Operations': 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300',
  Web: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300',
  'Code Execution': 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-300',
  Multimodal: 'bg-pink-100 text-pink-700 dark:bg-pink-900/30 dark:text-pink-300',
  Skills: 'bg-teal-100 text-teal-700 dark:bg-teal-900/30 dark:text-teal-300',
  'Tool Management': 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300',
  Memory: 'bg-cyan-100 text-cyan-700 dark:bg-cyan-900/30 dark:text-cyan-300',
  'User Interaction': 'bg-rose-100 text-rose-700 dark:bg-rose-900/30 dark:text-rose-300',
};

function ToolCard({ tool }: { tool: Tool }) {
  return (
    <div className="bg-[var(--color-bg-secondary)] border border-[var(--color-border)] rounded-lg p-4">
      <div className="flex items-center gap-2 mb-2">
        <code className="text-sm font-mono bg-[var(--color-bg-tertiary)] px-2 py-0.5 rounded">
          {tool.name}
        </code>
        <span className={`text-xs px-2 py-0.5 rounded-full ${categoryColors[tool.category]}`}>
          {tool.category}
        </span>
      </div>
      <p className="text-sm text-[var(--color-text-secondary)] mb-3">
        {tool.description}
      </p>
      <div className="space-y-1">
        <div className="text-xs font-medium text-[var(--color-text-tertiary)] uppercase tracking-wide">
          Parameters
        </div>
        <div className="space-y-1">
          {tool.parameters.map((param) => (
            <div key={param.name} className="flex items-start gap-2 text-xs">
              <code className="font-mono text-[var(--color-text-secondary)] shrink-0">
                {param.name}
              </code>
              {param.required && (
                <span className="text-red-500 text-[10px]">required</span>
              )}
              <span className="text-[var(--color-text-tertiary)]">
                {param.description}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

export default function BuiltinTools() {
  const categories = [...new Set(tools.map((t) => t.category))];

  return (
    <div className="p-6">
      <div className="mb-6">
        <h1 className="text-2xl font-semibold">Built-in Tools</h1>
        <p className="text-sm mt-1" style={{ color: 'var(--color-text-secondary)' }}>
          {tools.length} tools available across {categories.length} categories
        </p>
      </div>

      {categories.map((category) => (
        <div key={category} className="mb-8">
          <h2 className="text-lg font-medium mb-4 flex items-center gap-2">
            <span className={`px-2 py-0.5 rounded text-sm ${categoryColors[category]}`}>
              {category}
            </span>
            <span className="text-sm font-normal text-[var(--color-text-tertiary)]">
              {tools.filter((t) => t.category === category).length} tools
            </span>
          </h2>
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            {tools
              .filter((t) => t.category === category)
              .map((tool) => (
                <ToolCard key={tool.name} tool={tool} />
              ))}
          </div>
        </div>
      ))}
    </div>
  );
}
