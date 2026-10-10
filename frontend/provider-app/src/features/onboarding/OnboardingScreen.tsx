import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Checkbox,
  CircularProgress,
  FormControlLabel,
  FormGroup,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import MyLocationRoundedIcon from '@mui/icons-material/MyLocationRounded';
import CheckCircleRoundedIcon from '@mui/icons-material/CheckCircleRounded';
import { AppShell } from '@components/AppShell';
import { QueryStateView } from '@components/QueryStateView';
import { watchDevicePosition } from '@features/jobs/deviceLocation';
import { useAuthStore } from '@stores/authStore';
import type { CatalogCategory } from './api';
import { useCatalog, useMyProfile, useSaveProfile } from './hooks';

const RADIUS_OPTIONS = [3, 5, 10, 15, 25];

type LocationState =
  | { kind: 'idle' }
  | { kind: 'locating' }
  | { kind: 'found'; latitude: number; longitude: number }
  | { kind: 'failed'; reason: 'denied' | 'unavailable' };

/**
 * Provider onboarding: name, experience, the services offered and the service
 * area. Saving it is what makes a provider eligible for job offers — dispatch
 * matches the booked service's skill tags and the distance from the provider's
 * base location — so new providers are sent here before anything else, and
 * existing ones reach it from their profile to change what they offer.
 */
export function OnboardingScreen() {
  const navigate = useNavigate();
  const user = useAuthStore((state) => state.user);
  const profile = useMyProfile();
  const catalog = useCatalog();
  const save = useSaveProfile();

  const isNew = profile.data === null;
  const [displayName, setDisplayName] = useState('');
  const [years, setYears] = useState('1');
  const [radius, setRadius] = useState(10);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [location, setLocation] = useState<LocationState>({ kind: 'idle' });
  const [touched, setTouched] = useState(false);
  const stopWatch = useRef<(() => void) | null>(null);

  // Pre-fill from a saved profile once it arrives.
  useEffect(() => {
    const p = profile.data;
    if (!p) {
      if (user?.displayName) setDisplayName((current) => current || user.displayName || '');
      return;
    }
    setDisplayName(p.displayName ?? '');
    setYears(String(p.yearsExperience ?? 0));
    setRadius(p.serviceRadiusKm || 10);
    setSelected(new Set(p.categories.flatMap((c) => c.subcategoryIds)));
    if (p.baseLatitude != null && p.baseLongitude != null) {
      setLocation({ kind: 'found', latitude: p.baseLatitude, longitude: p.baseLongitude });
    }
  }, [profile.data, user?.displayName]);

  useEffect(() => () => stopWatch.current?.(), []);

  const locate = () => {
    stopWatch.current?.();
    setLocation({ kind: 'locating' });
    stopWatch.current = watchDevicePosition({
      onPosition: (latitude, longitude) => {
        setLocation({ kind: 'found', latitude, longitude });
        stopWatch.current?.();
        stopWatch.current = null;
      },
      onFailure: (reason) => {
        setLocation({ kind: 'failed', reason });
        stopWatch.current?.();
        stopWatch.current = null;
      },
    });
  };

  const toggle = (subcategoryId: string) => {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(subcategoryId)) next.delete(subcategoryId);
      else next.add(subcategoryId);
      return next;
    });
  };

  const yearsNumber = Number.parseInt(years, 10);
  const errors = {
    name: displayName.trim().length < 2 ? 'Enter the name customers will see.' : null,
    years:
      Number.isNaN(yearsNumber) || yearsNumber < 0 || yearsNumber > 60 ? 'Enter 0 to 60.' : null,
    services: selected.size === 0 ? 'Pick at least one service you offer.' : null,
    location:
      location.kind !== 'found' ? 'Set your base location so we can send you nearby jobs.' : null,
  };
  const valid = Object.values(errors).every((e) => e === null);

  const payload = useMemo(
    () => buildPayload(catalog.data ?? [], selected),
    [catalog.data, selected],
  );

  const submit = () => {
    setTouched(true);
    if (!valid || location.kind !== 'found') return;
    save.mutate(
      {
        displayName: displayName.trim(),
        yearsExperience: yearsNumber,
        serviceRadiusKm: radius,
        baseLatitude: location.latitude,
        baseLongitude: location.longitude,
        skillTags: payload.skillTags,
        categories: payload.categories,
      },
      { onSuccess: () => navigate(isNew ? '/verification' : '/profile', { replace: true }) },
    );
  };

  return (
    <AppShell title={isNew ? 'Set up your profile' : 'Services & area'}>
      <QueryStateView
        isLoading={profile.isLoading || catalog.isLoading}
        isError={profile.isError || catalog.isError}
        error={profile.error ?? catalog.error}
        onRetry={() => {
          void profile.refetch();
          void catalog.refetch();
        }}
      >
        <Stack spacing={2}>
          {isNew ? (
            <Alert severity="info">
              Tell us what you do and where you work. Next you&apos;ll upload your documents; once
              an admin approves them you&apos;ll start receiving jobs near you.
            </Alert>
          ) : null}

          <Card>
            <CardContent>
              <Stack spacing={2}>
                <Typography variant="subtitle1" fontWeight={700}>
                  About you
                </Typography>
                <TextField
                  label="Name shown to customers"
                  value={displayName}
                  onChange={(e) => setDisplayName(e.target.value)}
                  error={touched && !!errors.name}
                  helperText={touched ? errors.name : ' '}
                  fullWidth
                />
                <TextField
                  label="Years of experience"
                  value={years}
                  onChange={(e) => setYears(e.target.value.replace(/\D/g, '').slice(0, 2))}
                  inputProps={{ inputMode: 'numeric' }}
                  error={touched && !!errors.years}
                  helperText={touched ? errors.years : ' '}
                  fullWidth
                />
              </Stack>
            </CardContent>
          </Card>

          <Card>
            <CardContent>
              <Typography variant="subtitle1" fontWeight={700}>
                Services you offer
              </Typography>
              {touched && errors.services ? (
                <Typography variant="body2" color="error" sx={{ mt: 0.5 }}>
                  {errors.services}
                </Typography>
              ) : null}
              <Stack spacing={1.5} sx={{ mt: 1 }}>
                {(catalog.data ?? []).map((category) => (
                  <Box key={category.id}>
                    <Typography variant="body2" color="text.secondary" fontWeight={600}>
                      {category.name}
                    </Typography>
                    <FormGroup>
                      {category.subcategories.map((sub) => (
                        <FormControlLabel
                          key={sub.id}
                          control={
                            <Checkbox
                              checked={selected.has(sub.id)}
                              onChange={() => toggle(sub.id)}
                            />
                          }
                          label={sub.name}
                        />
                      ))}
                    </FormGroup>
                  </Box>
                ))}
              </Stack>
            </CardContent>
          </Card>

          <Card>
            <CardContent>
              <Stack spacing={2}>
                <Typography variant="subtitle1" fontWeight={700}>
                  Where you work
                </Typography>
                <Typography variant="body2" color="text.secondary">
                  Stand where you usually start your day (home or shop) and set it as your base.
                  We&apos;ll offer you jobs within your chosen distance from here.
                </Typography>
                <Button
                  variant={location.kind === 'found' ? 'outlined' : 'contained'}
                  startIcon={
                    location.kind === 'locating' ? (
                      <CircularProgress size={18} color="inherit" />
                    ) : location.kind === 'found' ? (
                      <CheckCircleRoundedIcon />
                    ) : (
                      <MyLocationRoundedIcon />
                    )
                  }
                  onClick={locate}
                  disabled={location.kind === 'locating'}
                >
                  {location.kind === 'found'
                    ? 'Base location set — update'
                    : 'Use my current location'}
                </Button>
                {location.kind === 'found' ? (
                  <Typography variant="caption" color="text.secondary">
                    {location.latitude.toFixed(5)}, {location.longitude.toFixed(5)}
                  </Typography>
                ) : null}
                {location.kind === 'failed' ? (
                  <Alert severity="warning">
                    {location.reason === 'denied'
                      ? 'Location permission was refused. Allow location for this app in your settings, then try again.'
                      : 'Could not get your location. Turn on location services and try again.'}
                  </Alert>
                ) : null}
                {touched && errors.location && location.kind !== 'failed' ? (
                  <Typography variant="body2" color="error">
                    {errors.location}
                  </Typography>
                ) : null}
                <TextField
                  select
                  label="How far will you travel?"
                  value={radius}
                  onChange={(e) => setRadius(Number(e.target.value))}
                  fullWidth
                >
                  {RADIUS_OPTIONS.map((km) => (
                    <MenuItem key={km} value={km}>
                      Up to {km} km
                    </MenuItem>
                  ))}
                </TextField>
              </Stack>
            </CardContent>
          </Card>

          {save.isError ? <Alert severity="error">{save.error.message}</Alert> : null}

          <Button
            variant="contained"
            size="large"
            fullWidth
            onClick={submit}
            disabled={save.isPending}
          >
            {save.isPending ? 'Saving…' : isNew ? 'Save and continue' : 'Save changes'}
          </Button>
        </Stack>
      </QueryStateView>
    </AppShell>
  );
}

/** Groups the chosen services by category and collects their dispatch skill tags. */
function buildPayload(catalog: CatalogCategory[], selected: Set<string>) {
  const categories: { categoryId: string; subcategoryIds: string[] }[] = [];
  const tags = new Set<string>();
  for (const category of catalog) {
    const chosen = category.subcategories.filter((s) => selected.has(s.id));
    if (chosen.length === 0) continue;
    categories.push({ categoryId: category.id, subcategoryIds: chosen.map((s) => s.id) });
    chosen.forEach((s) => s.skillTags.forEach((t) => tags.add(t)));
  }
  return { categories, skillTags: [...tags].sort() };
}
