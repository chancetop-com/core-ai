import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, type DatasetView } from '../../api/client';
import DatasetList from './DatasetList';

const DATASETS: DatasetView[] = Array.from({ length: 25 }, (_, index) => ({
  id: `dataset-${index}`,
  name: `Dataset ${index}`,
  description: '',
  type: 'GENERAL',
  schema: [],
  created_at: '',
  created_by: 'tester',
  updated_at: '',
}));

function DetailPage() {
  const navigate = useNavigate();
  return (
    <div>
      <span>Dataset detail</span>
      <button onClick={() => navigate(-1)}>Back</button>
    </div>
  );
}

function renderList() {
  render(
    <MemoryRouter initialEntries={['/datasets']}>
      <Routes>
        <Route path="/datasets" element={<DatasetList />} />
        <Route path="/datasets/:id" element={<DetailPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('DatasetList state restoration', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
    vi.spyOn(api.datasets, 'list').mockImplementation(async (_q, offset = 0, limit = 10) => ({
      datasets: DATASETS.slice(offset, offset + limit),
      total: DATASETS.length,
    }));
  });

  it('returns to the same search and page after visiting a detail page', async () => {
    renderList();
    await screen.findByText('Showing 1-10 of 25');

    fireEvent.change(screen.getByPlaceholderText('Search datasets...'), { target: { value: 'foo' } });
    await waitFor(() => expect(api.datasets.list).toHaveBeenCalledWith('foo', 0, 10), { timeout: 2000 });

    fireEvent.click(screen.getByRole('button', { name: /next/i }));
    await screen.findByText('Showing 11-20 of 25');
    await waitFor(() => expect(screen.getByText('Dataset 10')).toBeTruthy(), { timeout: 2000 });

    fireEvent.click(screen.getByText('Dataset 10'));
    await screen.findByText('Dataset detail');
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));

    expect(await screen.findByDisplayValue('foo')).toBeTruthy();
    expect(await screen.findByText('Showing 11-20 of 25')).toBeTruthy();
    await waitFor(() => expect(screen.getByText('Dataset 10')).toBeTruthy(), { timeout: 2000 });
    expect(api.datasets.list).toHaveBeenLastCalledWith('foo', 10, 10);
  });
});
