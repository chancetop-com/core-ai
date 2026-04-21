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
    description: 'Perform exact string replacements in files. Supports fuzzy matching and replace_all option.',
    category: 'File Operations',
    parameters: [
      { name: 'file_path', description: 'Absolute path to the file to modify', required: true },
      { name: 'old_string', description: 'The text to replace', required: true },
      { name: 'new_string', description: 'The text to replace it with', required: true },
      { name: 'replace_all', description: 'Replace all occurrences of old_string (default: false)' },
    ],
  },
  {
    name: 'glob_file',
    description: 'Fast file pattern matching tool. Find files by glob patterns like "**/*.js" or "src/**/*.ts".',
    category: 'File Operations',
    parameters: [
      { name: 'pattern', description: 'The glob pattern to match files against', required: true },
      { name: 'path', description: 'The directory to search in (defaults to current working directory)', required: true },
    ],
  },
  {
    name: 'grep_file',
    description: 'Powerful content search tool built on ripgrep. Supports regex, glob filtering, and multiple output modes.',
    category: 'File Operations',
    parameters: [
      { name: 'pattern', description: 'The regular expression pattern to search for', required: true },
      { name: 'path', description: 'File or directory to search in', required: true },
      { name: 'glob', description: 'Glob pattern to filter files (e.g., "*.js", "**/*.tsx")' },
      { name: 'output_mode', description: '"content" (matching lines), "files_with_matches" (paths), or "count"', required: true },
      { name: '-n', description: 'Show line numbers in output (default: true for content mode)' },
      { name: '-i', description: 'Case insensitive search' },
      { name: 'type', description: 'File type to search (js, py, rust, go, java, etc.)' },
      { name: '-C', description: 'Number of context lines before and after match' },
      { name: 'head_limit', description: 'Limit output to first N results' },
      { name: 'multiline', description: 'Enable multiline mode where . matches newlines' },
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
    description: 'Execute bash commands or shell scripts. Supports async mode for long-running commands (up to 10 minutes).',
    category: 'Code Execution',
    parameters: [
      { name: 'workspace_dir', description: 'Working directory for command execution' },
      { name: 'command', description: 'Command string to execute', required: true },
      { name: 'script_path', description: 'Path to a shell script file to execute' },
      { name: 'mode', description: '"read" (no modifications) or "write" (modifies files/system)' },
      { name: 'async', description: 'Set to true for long-running commands, use async_task_output to check progress' },
    ],
  },
  {
    name: 'run_python_script',
    description: 'Execute Python scripts and return output (stdout/stderr). Supports async mode for long-running scripts.',
    category: 'Code Execution',
    parameters: [
      { name: 'code', description: 'Python code to execute', required: true },
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
];

const categoryColors: Record<string, string> = {
  Plan: 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300',
  'File Operations': 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300',
  Web: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300',
  'Code Execution': 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-300',
  Multimodal: 'bg-pink-100 text-pink-700 dark:bg-pink-900/30 dark:text-pink-300',
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

export default function Tools() {
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
