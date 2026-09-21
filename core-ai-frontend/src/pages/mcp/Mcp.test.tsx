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
    fireEvent.change(within(dialog).getByLabelText('MCP configuration JSON'), { target: { value: '{"mcpServers":{}}' } });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Import' }));

    expect(await within(dialog).findByText("MCP configuration must contain a non-empty 'mcpServers' object.")).toBeTruthy();
    expect(importRequest).not.toHaveBeenCalled();
  });

  it('imports a single server config copied from an existing server', async () => {
    vi.spyOn(window, 'alert').mockImplementation(() => {});
    const importRequest = vi.spyOn(api.tools, 'importMcpServers').mockResolvedValue({ servers: [], total: 1 });
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'New MCP Server' }));
    const dialog = screen.getByRole('dialog', { name: 'New MCP Server' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Import JSON' }));
    fireEvent.change(within(dialog).getByLabelText('MCP configuration JSON'), {
      target: { value: '{"command":"uvx","args":["mcp-atlassian"],"env":{"JIRA_API_TOKEN":"secret"}}' },
    });

    const nameInput = await within(dialog).findByLabelText('Name *') as HTMLInputElement;
    expect(nameInput.value).toBe('mcp-atlassian');

    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, 'jira-atlassian');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Import' }));

    expect(importRequest).toHaveBeenCalledTimes(1);
    expect(importRequest.mock.calls[0][0]).toMatchObject({
      name: 'jira-atlassian',
      config: '{"command":"uvx","args":["mcp-atlassian"],"env":{"JIRA_API_TOKEN":"secret"}}',
    });
  });

  it('requires a name when the payload holds a single wrapped server', async () => {
    const importRequest = vi.spyOn(api.tools, 'importMcpServers');
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'New MCP Server' }));
    const dialog = screen.getByRole('dialog', { name: 'New MCP Server' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Import JSON' }));
    fireEvent.change(within(dialog).getByLabelText('MCP configuration JSON'), {
      target: { value: '{"mcpServers":{"meta-ads":{"url":"https://mcp.facebook.com"}}}' },
    });

    const nameInput = await within(dialog).findByLabelText('Name *') as HTMLInputElement;
    expect(nameInput.value).toBe('meta-ads');

    await userEvent.clear(nameInput);
    const importButton = within(dialog).getByRole('button', { name: 'Import' }) as HTMLButtonElement;
    expect(importButton.disabled).toBe(true);

    await userEvent.click(importButton);
    expect(importRequest).not.toHaveBeenCalled();
  });

  it('leaves names to the JSON keys when importing several servers', async () => {
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'New MCP Server' }));
    const dialog = screen.getByRole('dialog', { name: 'New MCP Server' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Import JSON' }));
    fireEvent.change(within(dialog).getByLabelText('MCP configuration JSON'), {
      target: { value: '{"mcpServers":{"a":{"command":"npx"},"b":{"command":"npx"}}}' },
    });

    const nameInput = await within(dialog).findByLabelText('Name') as HTMLInputElement;
    expect(nameInput.disabled).toBe(true);
    expect(nameInput.placeholder).toBe('Names come from the mcpServers keys');
    expect(within(dialog).getByText("2 servers detected. Existing server names are skipped.")).toBeTruthy();
  });

  it('reports an import that was skipped because the name already exists', async () => {
    const alertSpy = vi.spyOn(window, 'alert').mockImplementation(() => {});
    vi.spyOn(api.tools, 'importMcpServers').mockResolvedValue({ servers: [], total: 0 });
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: 'New MCP Server' }));
    const dialog = screen.getByRole('dialog', { name: 'New MCP Server' });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Import JSON' }));
    fireEvent.change(within(dialog).getByLabelText('MCP configuration JSON'), {
      target: { value: '{"mcpServers":{"meta-ads":{"url":"https://mcp.facebook.com"}}}' },
    });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Import' }));

    expect(alertSpy).toHaveBeenCalledWith('No MCP server was imported: every name already exists.');
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
