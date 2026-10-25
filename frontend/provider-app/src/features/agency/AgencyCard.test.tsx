import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/render';
import { acceptInvitation, fetchMyAgency, type WorkerAgency } from './api';
import { AgencyCard } from './AgencyCard';

vi.mock('./api', () => ({
  fetchMyAgency: vi.fn(),
  acceptInvitation: vi.fn(),
  declineInvitation: vi.fn(),
  leaveAgency: vi.fn(),
}));

const INVITED: WorkerAgency = {
  agency: null,
  invitations: [
    {
      id: 'inv-1',
      tenantId: 't1',
      agencyName: 'Ara Home Services',
      status: 'PENDING',
      createdAt: '2026-10-25T10:00:00Z',
      agencySharePercent: 10,
    },
  ],
};

describe('AgencyCard', () => {
  it('shows the invitation with its share and joins only when the worker accepts', async () => {
    vi.mocked(fetchMyAgency).mockResolvedValue(INVITED);
    vi.mocked(acceptInvitation).mockResolvedValue({
      agency: { tenantId: 't1', name: 'Ara Home Services', agencySharePercent: 10 },
      invitations: [],
    });
    renderWithProviders(<AgencyCard />);

    expect(await screen.findByText(/10% of your net earning/)).toBeInTheDocument();
    expect(acceptInvitation).not.toHaveBeenCalled();

    await userEvent.setup().click(screen.getByRole('button', { name: 'Accept' }));

    await waitFor(() => expect(acceptInvitation).toHaveBeenCalledWith('inv-1'));
    expect(await screen.findByRole('button', { name: 'Leave agency' })).toBeInTheDocument();
  });

  it('stays hidden on the dashboard when nothing waits for an answer', async () => {
    vi.mocked(fetchMyAgency).mockResolvedValue({ agency: null, invitations: [] });
    const { container } = renderWithProviders(<AgencyCard invitationsOnly />);
    await waitFor(() => expect(fetchMyAgency).toHaveBeenCalled());
    expect(container.textContent).toBe('');
  });
});
