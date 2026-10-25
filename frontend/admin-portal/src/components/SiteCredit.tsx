import Link from '@mui/material/Link';
import Typography, { type TypographyProps } from '@mui/material/Typography';

const YEAR = new Date().getFullYear();

/** The footer credit line shown on every screen's footer. */
export default function SiteCredit({ sx, variant = 'caption', ...rest }: TypographyProps) {
  return (
    <Typography variant={variant} sx={{ color: 'text.secondary', ...sx }} {...rest}>
      © {YEAR} Ji-Hajoor. Verified Help. Anytime. Anywhere. Powered by{' '}
      <Link
        href="https://www.condigence.com/"
        target="_blank"
        rel="noopener noreferrer"
        underline="hover"
        color="inherit"
        sx={{ fontWeight: 600 }}
      >
        Condigence LLP.
      </Link>
    </Typography>
  );
}
