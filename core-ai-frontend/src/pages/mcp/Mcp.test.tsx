import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { api, type ToolRegistryView } from '../../api/client';
import Mcp from './Mcp';

const SECRET_VALUE = '{"Authorization":"Bearer top-secret-token"}';

function renderPage(server?: ToolRegistryView) {
  vi.spyOn(api.tools, 'list').mockResolvedValue({ tools: server ? [server] : [], total: server ? 1 : 0 });
  vi.spyOn(api.tools, 'categories').mockResolvedValue({ categories: [] });
  render(<MemoryRouter><Mcp /></MemoryRouter>);
}

describe('MCP server modal', () => {
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
      enabled: false,
    });

    await screen.findByText('meta-ads-mcp');
    await userEvent.click(screen.getByTitle('Edit'));

    const dialog = screen.getByRole('dialog', { name: 'Edit MCP Server' });
    expect(within(dialog).queryByText(SECRET_VALUE)).toBeNull();
    expect(within(dialog).getByText('••••••••••••••••')).toBeTruthy();

    await userEvent.click(within(dialog).getByRole('button', { name: 'Reveal headers value' }));
    expect(within(dialog).getByText(SECRET_VALUE)).toBeTruthy();
    expect(within(dialog).getByRole('button', { name: 'Hide headers value' })).toBeTruthy();
  });

  it('validates imports before sending them to the server', async () => {
    const importRequest = vi.spyOn(api.tools, 'importMcpServers');
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'New MCP Server' }));
    const dialog = screen.getByRole('dialog', { name: 'New MCP Server' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Import JSON' }));
    fireEvent.change(within(dialog).getByLabelText('mcpServers JSON'), { target: { value: '{"mcpServers":{}}' } });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Import' }));

    expect(await within(dialog).findByText("MCP configuration must contain a non-empty 'mcpServers' object.")).toBeTruthy();
    expect(importRequest).not.toHaveBeenCalled();
  });

  it('hides sensitive raw JSON for dynamic MCP servers', async () => {
    renderPage({
      id: 'dynamic',
      name: 'dynamic-mcp',
      description: '',
      type: 'MCP',
      category: '',
      config: { transport: 'sandbox_hosted', command: 'npx' },
      raw_config: '{"command":"npx","env":{"API_TOKEN":"dynamic-secret"}}',
      enabled: false,
    });

    await screen.findByText('dynamic-mcp');
    await userEvent.click(screen.getByTitle('Edit'));
    const dialog = screen.getByRole('dialog', { name: 'Edit MCP Server' });

    expect(within(dialog).queryByDisplayValue(/dynamic-secret/)).toBeNull();
    await userEvent.click(within(dialog).getByRole('button', { name: 'Reveal and edit configuration' }));
    expect(within(dialog).getByDisplayValue(/dynamic-secret/)).toBeTruthy();
  });
});
