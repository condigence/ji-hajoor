import { Card, CardContent, Stack, Typography } from '@mui/material';
import { ModuleScreen } from '@components/ModuleScreen';
import { QueryStateView } from '@components/QueryStateView';
import { DataTable, type Column } from '@components/DataTable';
import type { AgencyEarningEntry } from '@features/tenants/model';
import { formatCurrency, formatDateTime } from '@lib/format';
import { useAgencyEarnings } from './hooks';

const columns: Column<AgencyEarningEntry>[] = [
  { key: 'when', header: 'Credited', render: (row) => formatDateTime(row.creditedAt) },
  { key: 'booking', header: 'Booking', render: (row) => row.bookingReference ?? '—' },
  { key: 'worker', header: 'Worker', render: (row) => row.workerName ?? '—' },
  {
    key: 'net',
    header: "Worker's net",
    align: 'right',
    render: (row) => formatCurrency(row.workerNet),
  },
  { key: 'share', header: 'Share', align: 'right', render: (row) => `${row.sharePercent}%` },
  {
    key: 'amount',
    header: 'Agency earned',
    align: 'right',
    render: (row) => formatCurrency(row.amount),
  },
];

/**
 * Earnings for the agency: the share of each worker's net earning that Ji-Hajoor
 * credits to the agency (set by Ji-Hajoor per agency), the running total and the
 * latest jobs. Workers see the same deduction itemised in their own earnings.
 */
export function EarningsScreen() {
  const query = useAgencyEarnings();
  const data = query.data;

  return (
    <ModuleScreen
      title="Earnings"
      description="Your agency's share of the jobs your workers complete. The share is agreed with Ji-Hajoor; contact support to change it."
    >
      <QueryStateView
        isLoading={query.isLoading}
        isError={query.isError}
        error={query.error}
        onRetry={() => void query.refetch()}
      >
        {data ? (
          <Stack spacing={2}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <Card sx={{ flex: 1 }}>
                <CardContent>
                  <Typography variant="overline" sx={{ color: 'text.secondary' }}>
                    Agency share
                  </Typography>
                  <Typography variant="h4">{data.sharePercent}%</Typography>
                  <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                    of each worker&apos;s net earning (after the platform fee)
                  </Typography>
                </CardContent>
              </Card>
              <Card sx={{ flex: 1 }}>
                <CardContent>
                  <Typography variant="overline" sx={{ color: 'text.secondary' }}>
                    Total earned
                  </Typography>
                  <Typography variant="h4">{formatCurrency(data.totalEarned)}</Typography>
                  <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                    across all jobs since the share was set
                  </Typography>
                </CardContent>
              </Card>
            </Stack>
            {data.entries.length === 0 ? (
              <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                {data.sharePercent > 0
                  ? 'No earnings yet. They appear here when your workers complete paid jobs.'
                  : 'Your agency share is 0%, so your workers keep their whole net earning.'}
              </Typography>
            ) : (
              <DataTable columns={columns} rows={data.entries} rowKey={(row) => row.bookingId} />
            )}
          </Stack>
        ) : null}
      </QueryStateView>
    </ModuleScreen>
  );
}
