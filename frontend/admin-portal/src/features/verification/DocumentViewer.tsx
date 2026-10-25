import { useEffect, useMemo, useState } from 'react';
import { Alert, Box, Button, CircularProgress, Stack, Tab, Tabs, Typography } from '@mui/material';
import DownloadRoundedIcon from '@mui/icons-material/DownloadRounded';
import OpenInNewRoundedIcon from '@mui/icons-material/OpenInNewRounded';
import type { VerificationDocument } from './api';
import { useVerificationDocumentContent } from './hooks';

interface DocumentViewerProps {
  documents: readonly VerificationDocument[];
}

/** Image types every supported browser renders in an <img>. */
const RENDERABLE_IMAGES = new Set(['image/jpeg', 'image/png', 'image/webp', 'image/gif']);

/** The bare, lower-case MIME type ("image/png"), or '' when absent. */
function mimeOf(contentType: string | null | undefined): string {
  return (contentType ?? '').split(';')[0]?.trim().toLowerCase() ?? '';
}

/** A file name for saving: the original upload name, else one made from the type. */
function downloadName(doc: VerificationDocument, mime: string): string {
  if (doc.fileName) return doc.fileName;
  const ext = mime === 'application/pdf' ? 'pdf' : (mime.split('/')[1] ?? 'bin');
  return `${doc.type.replace(/[^\w-]+/g, '-').toLowerCase()}.${ext}`;
}

const frameSx = {
  border: 1,
  borderColor: 'divider',
  borderRadius: 1,
  overflow: 'hidden',
  bgcolor: 'grey.100',
  minHeight: 480,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
} as const;

/**
 * Document viewer for the verification review (Requirement 19.3). Each
 * submitted document's bytes come from the Verification Service
 * (GET /admin/verification/{id}/documents/{documentId}, the document's `url`),
 * which needs the bearer header, so they are fetched through the API client as
 * a blob and shown from an object URL, released when the document changes or
 * the dialog closes. Images render inline; a PDF is offered to open in a new
 * tab or to download, as is anything else the server serves as a plain file.
 * A tab strip switches between documents when there is more than one.
 *
 * A document whose file was never stored (uploaded before document storage
 * was enabled: no `url`, or a 404) is named and marked as such, so the
 * reviewer knows it exists but cannot be checked here.
 */
export function DocumentViewer({ documents }: DocumentViewerProps) {
  const [active, setActive] = useState(0);

  if (documents.length === 0) {
    return (
      <Typography
        variant="body2"
        sx={{
          color: 'text.secondary',
          py: 4,
          textAlign: 'center',
        }}
      >
        No documents were submitted.
      </Typography>
    );
  }

  const current = documents[Math.min(active, documents.length - 1)];
  if (!current) return null;

  return (
    <Stack spacing={1.5}>
      {documents.length > 1 ? (
        <Tabs
          value={Math.min(active, documents.length - 1)}
          onChange={(_event, value: number) => setActive(value)}
          variant="scrollable"
          scrollButtons="auto"
          aria-label="Submitted documents"
        >
          {documents.map((doc, index) => (
            <Tab key={doc.id} label={doc.type} id={`doc-tab-${index}`} />
          ))}
        </Tabs>
      ) : (
        <Typography variant="subtitle2">{current.type}</Typography>
      )}

      {/* Keyed by document so each one starts from a clean fetch and object URL. */}
      <DocumentContent key={current.id} doc={current} />

      {current.fileName ? (
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          {current.fileName}
        </Typography>
      ) : null}
    </Stack>
  );
}

function NotStored() {
  return (
    <Box sx={frameSx}>
      <Stack spacing={0.5} sx={{ p: 3, textAlign: 'center', maxWidth: 420 }}>
        <Typography variant="subtitle2">File not stored</Typography>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          This document was uploaded before document storage was enabled, so there is no file to
          show. Ask the worker to submit it again if you need to check it.
        </Typography>
      </Stack>
    </Box>
  );
}

function DocumentContent({ doc }: { doc: VerificationDocument }) {
  const url = doc.url ?? null;
  const content = useVerificationDocumentContent(url);
  const blob = content.data;
  // One object URL per fetched blob, released when the blob changes or this unmounts.
  const objectUrl = useMemo(() => (blob ? URL.createObjectURL(blob) : null), [blob]);
  useEffect(
    () => () => {
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    },
    [objectUrl],
  );

  if (!url || content.error?.status === 404) {
    return <NotStored />;
  }

  if (content.isError) {
    return (
      <Box sx={frameSx}>
        <Alert
          severity="error"
          action={
            <Button color="inherit" size="small" onClick={() => void content.refetch()}>
              Retry
            </Button>
          }
        >
          {content.error.message || 'This document could not be loaded.'}
        </Alert>
      </Box>
    );
  }

  if (!blob || !objectUrl) {
    return (
      <Box sx={frameSx} aria-busy="true">
        <CircularProgress size={32} aria-label="Loading document" />
      </Box>
    );
  }

  // The server's type wins over the listed one: it serves anything it will not
  // let a browser render as application/octet-stream.
  const mime = mimeOf(blob.type) || mimeOf(doc.contentType);
  const label = doc.fileName ? `${doc.type} — ${doc.fileName}` : doc.type;
  const name = downloadName(doc, mime);

  if (RENDERABLE_IMAGES.has(mime)) {
    return (
      <Stack spacing={1}>
        <Box sx={frameSx}>
          <Box
            component="img"
            src={objectUrl}
            alt={label}
            sx={{ maxWidth: '100%', maxHeight: 560, objectFit: 'contain' }}
          />
        </Box>
        <Stack direction="row" spacing={1} sx={{ justifyContent: 'flex-end' }}>
          <Button size="small" startIcon={<DownloadRoundedIcon />} href={objectUrl} download={name}>
            Download
          </Button>
        </Stack>
      </Stack>
    );
  }

  const isPdf = mime === 'application/pdf';
  return (
    <Box sx={frameSx}>
      <Stack spacing={1.5} sx={{ p: 3, alignItems: 'center', textAlign: 'center' }}>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          {isPdf
            ? 'PDF document. Open it in a new tab to review it, or download it.'
            : 'This file cannot be previewed here. Download it to review it.'}
        </Typography>
        <Stack direction="row" spacing={1}>
          {isPdf ? (
            <Button
              variant="contained"
              startIcon={<OpenInNewRoundedIcon />}
              href={objectUrl}
              target="_blank"
              rel="noopener noreferrer"
            >
              Open PDF
            </Button>
          ) : null}
          <Button
            variant={isPdf ? 'outlined' : 'contained'}
            startIcon={<DownloadRoundedIcon />}
            href={objectUrl}
            download={name}
          >
            Download
          </Button>
        </Stack>
      </Stack>
    </Box>
  );
}
