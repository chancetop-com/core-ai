import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthContext, type AuthUser } from '../../api/auth';
import { api, type ReportView } from '../../api/client';
import ReportList from './ReportList';

const REPORTS: ReportView[] = Array.from({ length: 25 }, (_, index) => ({
  id: `report-${index}`,
  title: `Report ${index}`,
  description: index === 0 ? 'Daily collection' : '',
  created_by: 'Tester',
  owned: true,
  revision: 1,
  spec_version: 1,
  engine_version: 1,
  dataset_id: 'dataset-1',
  dataset_name: 'HR简历库',
  display_type: 'BAR',
  created_at: '2026-11-19T00:00:00Z',
  updated_at: '2026-11-19T00:00:00Z',
  pin_id: index === 0 ? 'pin-1' : null,
}));

function DetailPage() {
  const navigate = useNavigate();
  return (
    <div>
      <span>Report detail</span>
      <button onClick={() => navigate(-1)}>Back</button>
    </div>
  );
}

function renderList(permissions: string[]) {
  const user: AuthUser = {
    apiKey: 'key',
    userId: 'tester@example.com',
    name: 'Tester',
    permissions,
  };
  render(
    <AuthContext.Provider value={{ user, login: () => {}, logout: () => {} }}>
      <MemoryRouter initialEntries={['/datasets/reports']}>
        <Routes>
          <Route path="/datasets/reports" element={<ReportList />} />
          <Route path="/datasets/reports/:id" element={<DetailPage />} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe('ReportList', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
    vi.restoreAllMocks();
    vi.spyOn(api.reports, 'list').mockImplementation(async (offset = 0, limit = 10) => ({
      reports: REPORTS.slice(offset, offset + limit),
      total: REPORTS.length,
    }));
  });

  it('paginates reports and restores the page after visiting a detail', async () => {
    renderList(['report.view', 'report.manage']);

    await screen.findByText('Showing 1-10 of 25');
    expect(screen.getAllByText('Tester').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Dataset').length).toBeGreaterThan(0);
    expect(screen.getAllByText(/HR简历库/).length).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole('button', { name: /next/i }));
    await screen.findByText('Showing 11-20 of 25');
    await waitFor(() => expect(screen.getByText('Report 10')).toBeTruthy());

    fireEvent.click(screen.getByText('Report 10'));
    await screen.findByText('Report detail');
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));

    expect(await screen.findByText('Showing 11-20 of 25')).toBeTruthy();
    await waitFor(() => expect(api.reports.list).toHaveBeenLastCalledWith(10, 10));
  });

  it('hides manage actions for view-only users', async () => {
    renderList(['report.view']);

    await screen.findByText('Showing 1-10 of 25');
    expect(screen.queryByRole('button', { name: /delete/i })).toBeNull();
    expect(screen.queryByRole('button', { name: /^pin$/i })).toBeNull();
    expect(screen.getAllByRole('button', { name: /view/i }).length).toBeGreaterThan(0);
  });

  it('pins an unpinned report with the current layout revision', async () => {
    const pin = vi.spyOn(api.reports.pins, 'pin').mockResolvedValue({
      id: 'pin-2',
      report_id: 'report-1',
      position: 1,
      width: 'HALF',
    });
    vi.spyOn(api.reports.pins, 'layout').mockResolvedValue({ layout_revision: 3, pins: [] });

    renderList(['report.view', 'report.manage']);
    await screen.findByText('Showing 1-10 of 25');

    const pinButtons = screen.getAllByRole('button', { name: /^pin$/i });
    fireEvent.click(pinButtons[0]);

    await waitFor(() => expect(pin).toHaveBeenCalledWith({
      report_id: 'report-1',
      width: 'HALF',
      expected_layout_revision: 3,
    }));
  });
});
