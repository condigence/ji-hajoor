import { Link as RouterLink } from 'react-router';
import { Box, Button, CircularProgress, Container, Stack, Typography } from '@mui/material';
import ArrowForwardRoundedIcon from '@mui/icons-material/ArrowForwardRounded';
import PlumbingRoundedIcon from '@mui/icons-material/PlumbingRounded';
import ElectricalServicesRoundedIcon from '@mui/icons-material/ElectricalServicesRounded';
import CleaningServicesRoundedIcon from '@mui/icons-material/CleaningServicesRounded';
import AcUnitRoundedIcon from '@mui/icons-material/AcUnitRounded';
import CarpenterRoundedIcon from '@mui/icons-material/CarpenterRounded';
import FormatPaintRoundedIcon from '@mui/icons-material/FormatPaintRounded';
import VerifiedUserRoundedIcon from '@mui/icons-material/VerifiedUserRounded';
import CurrencyRupeeRoundedIcon from '@mui/icons-material/CurrencyRupeeRounded';
import LocationOnRoundedIcon from '@mui/icons-material/LocationOnRounded';
import SupportAgentRoundedIcon from '@mui/icons-material/SupportAgentRounded';
import { BrandLogo } from '@components/BrandLogo';
import { HomeRedirect } from '@components/HomeRedirect';
import { useAuthStore } from '@stores/authStore';
import { brand } from '@lib/theme';
import SiteCredit from '../../components/SiteCredit';

/** Read once at load: the footer year needs no re-render to stay current within a session. */

const SERVICES = [
  { icon: PlumbingRoundedIcon, label: 'Plumbing' },
  { icon: ElectricalServicesRoundedIcon, label: 'Electrical' },
  { icon: CleaningServicesRoundedIcon, label: 'Cleaning' },
  { icon: AcUnitRoundedIcon, label: 'AC repair' },
  { icon: CarpenterRoundedIcon, label: 'Carpentry' },
  { icon: FormatPaintRoundedIcon, label: 'Painting' },
];

const PROMISES = [
  {
    icon: VerifiedUserRoundedIcon,
    title: 'Verified professionals',
    body: 'Every provider is identity-checked and reviewed before taking a job.',
  },
  {
    icon: CurrencyRupeeRoundedIcon,
    title: 'Upfront pricing',
    body: 'See the estimate before you book. No surprises at the door.',
  },
  {
    icon: LocationOnRoundedIcon,
    title: 'Live tracking',
    body: 'Follow your professional on the map from acceptance to arrival.',
  },
  {
    icon: SupportAgentRoundedIcon,
    title: 'Support that answers',
    body: 'Raise a complaint in the app and a real person picks it up.',
  },
];

/**
 * The public `/` page. Visitors see the Ji-Hajoor home page; a signed-in
 * staff member is sent straight on to their landing module, as before.
 */
export function HomeScreen() {
  const isAuthenticated = useAuthStore((state) => state.isAuthenticated);
  const isHydrating = useAuthStore((state) => state.isHydrating);

  if (isHydrating) {
    return (
      <Box sx={{ display: 'grid', placeItems: 'center', minHeight: '100dvh' }}>
        <CircularProgress size={28} aria-label="Loading" />
      </Box>
    );
  }

  if (isAuthenticated) {
    return <HomeRedirect />;
  }

  return (
    <Box sx={{ minHeight: '100dvh', bgcolor: 'background.paper', color: 'text.primary' }}>
      {/* Hero */}
      <Box
        component="header"
        sx={{
          position: 'relative',
          overflow: 'hidden',
          color: '#FFFFFF',
          background: `linear-gradient(155deg, ${brand.accent} 0%, ${brand.accentDark} 52%, #172554 100%)`,
        }}
      >
        <Box
          aria-hidden
          sx={{
            position: 'absolute',
            inset: 0,
            background:
              'radial-gradient(760px circle at 12% 8%, rgba(255,255,255,0.16), transparent 45%),' +
              'radial-gradient(560px circle at 88% 92%, rgba(56,189,248,0.22), transparent 50%)',
          }}
        />
        <Container maxWidth="lg" sx={{ position: 'relative', py: { xs: 3, md: 4 } }}>
          <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
            <BrandLogo size={40} inverted />
            <Button
              component={RouterLink}
              to="/login"
              variant="outlined"
              sx={{
                color: '#FFFFFF',
                borderColor: 'rgba(255,255,255,0.5)',
                '&:hover': { borderColor: '#FFFFFF', bgcolor: 'rgba(255,255,255,0.08)' },
              }}
            >
              Staff sign in
            </Button>
          </Stack>

          <Box sx={{ py: { xs: 8, md: 12 }, maxWidth: 680 }}>
            <Typography
              variant="h2"
              component="h1"
              sx={{
                fontWeight: 800,
                letterSpacing: '-0.03em',
                lineHeight: 1.1,
                fontSize: { xs: '2.25rem', md: '3.5rem' },
              }}
            >
              Trusted help at home,
              <Box component="span" sx={{ display: 'block', color: 'rgba(255,255,255,0.7)' }}>
                just a tap away.
              </Box>
            </Typography>
            <Typography
              sx={{ mt: 2.5, maxWidth: 520, fontSize: '1.125rem', color: 'rgba(255,255,255,0.8)' }}
            >
              Ji-Hajoor connects you with verified plumbers, electricians, cleaners and more —
              booked in minutes, priced upfront, tracked to your door.
            </Typography>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mt: 4 }}>
              <Button
                href="#services"
                size="large"
                variant="contained"
                endIcon={<ArrowForwardRoundedIcon />}
                sx={{
                  bgcolor: '#FFFFFF',
                  color: brand.accentDark,
                  '&:hover': { bgcolor: '#EFF4FF' },
                }}
              >
                Explore services
              </Button>
              <Button
                href="#providers"
                size="large"
                sx={{ color: '#FFFFFF', '&:hover': { bgcolor: 'rgba(255,255,255,0.08)' } }}
              >
                Become a provider
              </Button>
            </Stack>
          </Box>
        </Container>
      </Box>

      {/* Services */}
      <Container id="services" component="section" maxWidth="lg" sx={{ py: { xs: 7, md: 10 } }}>
        <Typography variant="h4" component="h2" sx={{ fontWeight: 800, letterSpacing: '-0.02em' }}>
          What we fix
        </Typography>
        <Typography sx={{ mt: 1, color: 'text.secondary' }}>
          Everyday jobs around the house, handled by people who do them every day.
        </Typography>
        <Box
          sx={{
            mt: 4,
            display: 'grid',
            gap: 2,
            gridTemplateColumns: {
              xs: 'repeat(2, 1fr)',
              sm: 'repeat(3, 1fr)',
              md: 'repeat(6, 1fr)',
            },
          }}
        >
          {SERVICES.map(({ icon: Icon, label }) => (
            <Stack
              key={label}
              spacing={1.25}
              sx={{
                alignItems: 'center',
                p: 2.5,
                borderRadius: 3,
                border: '1px solid',
                borderColor: 'divider',
                bgcolor: 'background.default',
              }}
            >
              <Box
                aria-hidden
                sx={{
                  width: 48,
                  height: 48,
                  borderRadius: 2.5,
                  display: 'grid',
                  placeItems: 'center',
                  bgcolor: brand.accentSoft,
                  color: brand.accent,
                }}
              >
                <Icon />
              </Box>
              <Typography sx={{ fontWeight: 600 }}>{label}</Typography>
            </Stack>
          ))}
        </Box>
      </Container>

      {/* Promises */}
      <Box component="section" sx={{ bgcolor: 'background.default', py: { xs: 7, md: 10 } }}>
        <Container maxWidth="lg">
          <Typography
            variant="h4"
            component="h2"
            sx={{ fontWeight: 800, letterSpacing: '-0.02em' }}
          >
            Why Ji-Hajoor
          </Typography>
          <Box
            sx={{
              mt: 4,
              display: 'grid',
              gap: 3,
              gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', md: 'repeat(4, 1fr)' },
            }}
          >
            {PROMISES.map(({ icon: Icon, title, body }) => (
              <Box key={title}>
                <Icon sx={{ color: brand.accent }} />
                <Typography variant="subtitle1" sx={{ mt: 1, fontWeight: 700 }}>
                  {title}
                </Typography>
                <Typography variant="body2" sx={{ mt: 0.5, color: 'text.secondary' }}>
                  {body}
                </Typography>
              </Box>
            ))}
          </Box>
        </Container>
      </Box>

      {/* Providers */}
      <Container id="providers" component="section" maxWidth="lg" sx={{ py: { xs: 7, md: 10 } }}>
        <Stack
          direction={{ xs: 'column', md: 'row' }}
          spacing={3}
          sx={{
            alignItems: { md: 'center' },
            justifyContent: 'space-between',
            p: { xs: 3, md: 5 },
            borderRadius: 4,
            bgcolor: brand.navBg,
            color: '#FFFFFF',
          }}
        >
          <Box sx={{ maxWidth: 560 }}>
            <Typography variant="h5" component="h2" sx={{ fontWeight: 800 }}>
              Skilled with your hands? Earn with Ji-Hajoor.
            </Typography>
            <Typography sx={{ mt: 1, color: brand.navText }}>
              Get steady jobs near you, set your own hours and get paid straight to your wallet.
              Agencies can bring their whole team on board.
            </Typography>
          </Box>
          <Typography sx={{ color: brand.navText, flexShrink: 0 }}>
            Download the Ji-Hajoor Pro app to sign up.
          </Typography>
        </Stack>
      </Container>

      {/* Footer */}
      <Box component="footer" sx={{ borderTop: '1px solid', borderColor: 'divider' }}>
        <Container
          maxWidth="lg"
          sx={{
            py: 3,
            display: 'flex',
            flexWrap: 'wrap',
            gap: 2,
            alignItems: 'center',
            justifyContent: 'space-between',
          }}
        >
          <BrandLogo size={28} />
          <SiteCredit variant="body2" />
        </Container>
      </Box>
    </Box>
  );
}
