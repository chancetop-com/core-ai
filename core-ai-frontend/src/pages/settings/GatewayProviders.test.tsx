import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, type GatewayModel, type GatewayProvider } from '../../api/client';
import GatewayProviders from './GatewayProviders';

const providers: GatewayProvider[] = [
  { id: 'p-alpha', name: 'Alpha', type: 'openai-compatible', baseUrl: 'https://alpha.example.com' },
  { id: 'p-beta', name: 'Beta', type: 'openai-compatible', baseUrl: 'https://beta.example.com' },
  { id: 'p-gamma', name: 'Gamma', type: 'openai-compatible', baseUrl: 'https://gamma.example.com' },
];

// server order deliberately matches neither provider nor official-model order
const models: GatewayModel[] = [
  { id: 'm1', modelId: 'model-beta', providerId: 'p-beta', providerName: 'Beta', upstreamModel: 'aaa-official' },
  { id: 'm2', modelId: 'model-gamma', providerId: 'p-gamma', providerName: 'Gamma', upstreamModel: 'mmm-official' },
  { id: 'm3', modelId: 'model-alpha', providerId: 'p-alpha', providerName: 'Alpha', upstreamModel: 'zzz-official' },
];

function mockLoad() {
  vi.spyOn(api.gateway, 'listProviders').mockResolvedValue({ providers });
  vi.spyOn(api.gateway, 'listModels').mockResolvedValue({ models });
}

function renderedModelIds() {
  return screen.getAllByRole('row')
    .slice(1)
    .map(row => row.querySelector('td span')?.textContent || '');
}

async function openModelsTab() {
  render(<GatewayProviders />);
  await userEvent.click(screen.getByRole('button', { name: 'Models' }));
  return screen.findByRole('combobox', { name: 'Sort models' });
}

describe('Gateway models sorting', () => {
  beforeEach(() => {
    sessionStorage.clear();
    mockLoad();
  });

  it('keeps the server order by default', async () => {
    await openModelsTab();

    expect(renderedModelIds()).toEqual(['model-beta', 'model-gamma', 'model-alpha']);
  });

  it('sorts by provider name in both directions', async () => {
    const sort = await openModelsTab();

    await userEvent.selectOptions(sort, 'provider_asc');
    expect(renderedModelIds()).toEqual(['model-alpha', 'model-beta', 'model-gamma']);

    await userEvent.selectOptions(sort, 'provider_desc');
    expect(renderedModelIds()).toEqual(['model-gamma', 'model-beta', 'model-alpha']);
  });

  it('sorts by official model name in both directions', async () => {
    const sort = await openModelsTab();

    await userEvent.selectOptions(sort, 'official_asc');
    expect(renderedModelIds()).toEqual(['model-beta', 'model-gamma', 'model-alpha']);

    await userEvent.selectOptions(sort, 'official_desc');
    expect(renderedModelIds()).toEqual(['model-alpha', 'model-gamma', 'model-beta']);
  });

  it('restores the selected sort after remounting', async () => {
    const sort = await openModelsTab();
    await userEvent.selectOptions(sort, 'official_desc');

    cleanup();
    render(<GatewayProviders />);
    await userEvent.click(screen.getByRole('button', { name: 'Models' }));

    expect(await screen.findByRole('combobox', { name: 'Sort models' })).toHaveProperty('value', 'official_desc');
    expect(renderedModelIds()).toEqual(['model-alpha', 'model-gamma', 'model-beta']);
  });
});
