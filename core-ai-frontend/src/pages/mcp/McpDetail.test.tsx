import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, type ToolRegistryView } from '../../api/client';
import McpDetail from './McpDetail';

const SECRET_VALUE = '{"Authorization":"Bearer top-secret-token"}';

function renderPage(server: ToolRegistryView) {
  vi.spyOn(api.tools, 'get').mockResolvedValue(server);
  vi.spyOn(api.tools, 'listMcpServerTools').mockResolvedValue({
    server_id: server.id,
    server_name: server.name,
    tools: [],
  });
  vi.spyOn(api.tools, 'getMcpServerStatus').mockResolvedValue({
    server_id: server.id,
    state: 'FAILED',
    message: 'Connection failed. Check the server URL and credentials, save changes, then retry.',
  });
  render(
    <MemoryRouter initialEntries={[`/mcp/${server.id}`]}>
      <Routes>
        <Route path="/mcp/:id" element={<McpDetail />} />
      </Routes>
    </MemoryRouter>,
  );
}

afterEach(() => vi.restoreAllMocks());

describe('MCP server detail', () => {
  it('masks sensitive configuration values until explicitly revealed', async () => {
    renderPage({
      id: 'meta-ads',
      name: 'meta-ads-mcp',
      description: '',
      type: 'MCP',
      category: '',
      config: {
        url: 'https://mcp.facebook.com',
        endpoint: '/ads',
        headers: SECRET_VALUE,
      },
      enabled: true,
    });

    await screen.findByText('meta-ads-mcp');
    expect(screen.queryByText(SECRET_VALUE)).toBeNull();
    expect(screen.getByText('••••••••••••••••')).toBeTruthy();

    await userEvent.click(screen.getByRole('button', { name: 'Reveal headers value' }));
    expect(screen.getByText(SECRET_VALUE)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Hide headers value' })).toBeTruthy();
  });

  it('hides sensitive raw JSON for dynamic MCP servers', async () => {
    const rawConfig = '{"command":"npx","env":{"API_TOKEN":"dynamic-secret"}}';
    renderPage({
      id: 'dynamic',
      name: 'dynamic-mcp',
      description: '',
      type: 'MCP',
      category: '',
      config: { transport: 'sandbox_hosted', command: 'npx' },
      raw_config: rawConfig,
      enabled: true,
    });

    await screen.findByText('dynamic-mcp');
    expect(screen.queryByText(/dynamic-secret/)).toBeNull();

    const configuration = screen.getByText('Configuration').parentElement;
    expect(configuration).not.toBeNull();
    await userEvent.click(within(configuration!).getByRole('button', { name: 'Reveal raw configuration' }));
    expect(screen.getByText(/dynamic-secret/)).toBeTruthy();
  });
});
