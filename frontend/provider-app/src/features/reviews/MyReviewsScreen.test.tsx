import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderWithProviders } from '@/test/render';
import type * as ApiModule from './api';
import {
  fetchClientConfig,
  fetchMySummary,
  fetchReceivedReviews,
  profileLink,
  replyToReview,
  type ReceivedReview,
} from './api';
import { MyReviewsScreen } from './MyReviewsScreen';

vi.mock('./api', async (importOriginal) => {
  const actual = await importOriginal<typeof ApiModule>();
  return {
    ...actual,
    fetchReceivedReviews: vi.fn(),
    replyToReview: vi.fn(),
    fetchMySummary: vi.fn(),
    fetchClientConfig: vi.fn(),
  };
});

const REVIEW: ReceivedReview = {
  id: 'r1',
  overall: 3,
  text: 'Fixed the tap but came late.',
  submittedAt: '2026-10-20T10:00:00Z',
  reply: null,
};

describe('MyReviewsScreen', () => {
  it('lets the worker reply publicly to a review', async () => {
    vi.mocked(fetchReceivedReviews).mockResolvedValue([REVIEW]);
    vi.mocked(fetchMySummary).mockResolvedValue({ average: 3, count: 1, distribution: {} });
    vi.mocked(fetchClientConfig).mockResolvedValue({});
    vi.mocked(replyToReview).mockResolvedValue({ ...REVIEW, reply: 'Sorry for the delay!' });
    renderWithProviders(<MyReviewsScreen />);

    expect(await screen.findByText('Fixed the tap but came late.')).toBeInTheDocument();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Reply publicly'), '  Sorry for the delay!  ');
    await user.click(screen.getByRole('button', { name: 'Post reply' }));

    await waitFor(() => expect(replyToReview).toHaveBeenCalledWith('r1', 'Sorry for the delay!'));
    expect(await screen.findByText('Sorry for the delay!')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit reply' })).toBeInTheDocument();
  });

  it('builds the public profile link on the customer site', () => {
    expect(profileLink('https://jihajoor.com/', 'abc')).toBe('https://jihajoor.com/workers/abc');
  });
});
