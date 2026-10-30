"""Builds the shareable PDFs in docs/pdf/:

- one PDF per user guide and business document (Markdown rendered with the Ji-Hajoor style),
- one PDF per deck (converted with LibreOffice),
- JiHajoor_Documentation_Pack.pdf: cover, contents, guides, documents and the four business decks.

Usage, from the repository root:
    python3 docs/source/build_pdfs.py [--soffice "<command to run LibreOffice>"]
Needs: markdown, pypdf, playwright (Chromium) and LibreOffice.
"""
import argparse
import html
import re
import shlex
import shutil
import subprocess
import tempfile
from pathlib import Path

import markdown
from playwright.sync_api import sync_playwright
from pypdf import PdfReader, PdfWriter

DOCS = Path(__file__).resolve().parent.parent
OUT = DOCS / 'pdf'
DATE = '30 October 2026'

GUIDES = [
    ('guides/CUSTOMER_GUIDE.md', 'JiHajoor_Customer_Guide.pdf', 'Customer Guide', 'For customers'),
    ('guides/WORKER_GUIDE.md', 'JiHajoor_Worker_Guide.pdf', 'Worker Guide (Ji-Hajoor Pro)', 'For workers'),
    ('guides/AGENCY_GUIDE.md', 'JiHajoor_Agency_Guide.pdf', 'Agency Guide', 'For agencies'),
    ('guides/ADMIN_GUIDE.md', 'JiHajoor_Admin_Guide.pdf', 'Staff Guide (Ji-Hajoor Admin)', 'For Ji-Hajoor staff'),
    ('REVENUE_MODEL.md', 'JiHajoor_Revenue_Model_Doc.pdf', 'Revenue Model', 'Business'),
    ('BRAND_AND_MARKETING.md', 'JiHajoor_Brand_and_Marketing_Doc.pdf', 'Brand, Naming and Marketing', 'Business'),
]
DECKS = [
    ('JiHajoor_Pitch_Deck.pptx', 'Pitch Deck', True),
    ('JiHajoor_Revenue_Model.pptx', 'Revenue Model Deck', True),
    ('JiHajoor_Brand_and_Marketing.pptx', 'Brand and Marketing Deck', True),
    ('JiHajoor_Tech_Stack.pptx', 'Technology and Security Deck', True),
    ('JiHajoor_Architecture.pptx', 'Architecture Deck', False),
    ('JiHajoor_Design_Deck.pptx', 'Design Review Deck', False),
]

CSS = """
@page { size: A4; margin: 18mm 18mm 20mm 18mm; }
* { box-sizing: border-box; }
body { font-family: 'Carlito', 'Calibri', 'Segoe UI', Arial, sans-serif; color: #1D2939; font-size: 11pt;
       line-height: 1.5; margin: 0; }
h1, h2, h3 { font-family: 'Caladea', 'Cambria', Georgia, serif; color: #0F2A6E; line-height: 1.2; }
h1 { font-size: 24pt; margin: 0 0 4pt; }
h2 { font-size: 15pt; margin: 18pt 0 6pt; break-after: avoid; }
h3 { font-size: 12.5pt; margin: 12pt 0 4pt; break-after: avoid; }
p, li { orphans: 3; widows: 3; }
em { color: #5F6B7E; }
a { color: #2251D1; text-decoration: none; }
code { font-family: 'DejaVu Sans Mono', monospace; font-size: 9pt; background: #F2F4F7; padding: 0 3px;
       border-radius: 3px; }
pre { background: #F2F5FC; border-radius: 6px; padding: 8pt 10pt; font-size: 9pt; break-inside: avoid; }
pre code { background: none; padding: 0; }
blockquote { margin: 8pt 0; padding: 6pt 12pt; background: #FFF6E2; border-radius: 6px; color: #6B4100; }
blockquote p { margin: 0; }
table { border-collapse: collapse; width: 100%; margin: 8pt 0; font-size: 9.5pt; break-inside: auto; }
th { background: #0F2A6E; color: #fff; text-align: left; padding: 4pt 6pt; }
td { padding: 4pt 6pt; border-bottom: 1px solid #E4E7EC; vertical-align: top; }
tr:nth-child(even) td { background: #F7F8FA; }
hr { border: none; border-top: 1px solid #E4E7EC; margin: 14pt 0; }
.band { background: #0F2A6E; color: #fff; border-radius: 8px; padding: 14pt 16pt; margin-bottom: 14pt; }
.band .kicker { color: #F2A516; font-weight: bold; font-size: 9pt; letter-spacing: 1px; text-transform: uppercase; }
.band h1 { color: #fff; margin: 4pt 0 2pt; }
.band .tag { color: #D6E0FA; font-size: 10pt; }
"""

FOOTER = ('<div style="font-family:Carlito,Calibri,Arial;font-size:8px;color:#98A2B3;width:100%;'
          'padding:0 18mm;display:flex;justify-content:space-between">'
          '<span>Ji-Hajoor · Verified Help. Anytime. Anywhere. · Powered by Condigence LLP</span>'
          '<span><span class="pageNumber"></span> / <span class="totalPages"></span></span></div>')


def md_to_html(md_text, title, kicker):
    # The first H1 becomes the coloured band; the rest renders as written.
    body = re.sub(r'^# .*\n', '', md_text, count=1)
    tag = ''
    m = re.match(r'\s*\*([^*\n]+)\*\s*\n', body)
    if m:
        tag, body = m.group(1), body[m.end():]
    # GitHub accepts 3-space nested lists; Python-Markdown needs 4.
    body = re.sub(r'^ {2,3}(?=[-*] |\d+\. )', '    ', body, flags=re.M)
    content = markdown.markdown(body, extensions=['tables', 'fenced_code', 'sane_lists'])
    # Links between guides point at the Markdown files; in a PDF show them as plain text.
    content = re.sub(r'<a href="(?!https?:)[^"]*">([^<]*)</a>', r'\1', content)
    band = (f'<div class="band"><div class="kicker">{html.escape(kicker)} · {DATE}</div>'
            f'<h1>{html.escape(title)}</h1><div class="tag">{html.escape(tag)}</div></div>')
    return f'<!doctype html><html><head><meta charset="utf-8"><style>{CSS}</style></head><body>{band}{content}</body></html>'


def render(page, html_text, out):
    page.set_content(html_text, wait_until='load')
    page.pdf(path=str(out), format='A4', print_background=True, display_header_footer=True,
             header_template='<span></span>', footer_template=FOOTER,
             margin={'top': '18mm', 'bottom': '20mm', 'left': '18mm', 'right': '18mm'})


def cover_html(entries):
    rows = ''.join(f'<tr><td>{html.escape(t)}</td><td>{html.escape(k)}</td><td style="text-align:right">{p}</td></tr>'
                   for t, k, p in entries)
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>{CSS}
    .cover {{ background:#0F2A6E; color:#fff; border-radius:10px; padding:60pt 36pt; margin-bottom:24pt; }}
    .cover h1 {{ color:#fff; font-size:38pt; margin:0; }}
    .cover .sub {{ color:#F2A516; font-size:16pt; margin-top:6pt; }}
    .cover .meta {{ color:#D6E0FA; margin-top:36pt; font-size:11pt; }}
    </style></head><body>
    <div class="cover"><h1>Ji-Hajoor</h1><div class="sub">Documentation Pack</div>
    <div class="meta">Guides for customers, workers, agencies and staff · Revenue model · Brand and marketing ·
    Pitch, revenue, brand and technology decks<br>Updated {DATE} · Powered by Condigence LLP</div></div>
    <h2>Contents</h2><table><tr><th>Document</th><th>For</th><th style="text-align:right">Page</th></tr>{rows}</table>
    <p><em>Source files live in the repository's <code>docs/</code> folder; each document is also in
    <code>docs/pdf/</code> on its own. The architecture and design review decks are in <code>docs/pdf/</code> too.</em></p>
    </body></html>"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--soffice', default='soffice', help='command that runs LibreOffice')
    args = ap.parse_args()
    OUT.mkdir(exist_ok=True)

    with sync_playwright() as pw:
        browser = pw.chromium.launch()
        page = browser.new_page()
        for src, out, title, kicker in GUIDES:
            render(page, md_to_html((DOCS / src).read_text(), title, kicker), OUT / out)
            print('wrote', out)

        with tempfile.TemporaryDirectory() as tmp:
            for deck, _, _ in DECKS:
                shutil.copy(DOCS / deck, tmp)
                subprocess.run(shlex.split(args.soffice) + ['--headless', '--convert-to', 'pdf', '--outdir', tmp,
                                                            str(Path(tmp) / deck)], check=True,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                shutil.copy(Path(tmp) / deck.replace('.pptx', '.pdf'), OUT / deck.replace('.pptx', '.pdf'))
                print('wrote', deck.replace('.pptx', '.pdf'))

        parts = [(title, kicker, OUT / out) for _, out, title, kicker in GUIDES]
        parts += [(title, 'Deck', OUT / deck.replace('.pptx', '.pdf')) for deck, title, inpack in DECKS if inpack]
        # Two passes: the cover's page numbers depend on the cover's own length (one page).
        page_no, entries = 2, []
        for title, kicker, pdf in parts:
            entries.append((title, kicker, page_no))
            page_no += len(PdfReader(pdf).pages)
        cover = OUT / '_cover.pdf'
        render(page, cover_html(entries), cover)
        browser.close()

    writer = PdfWriter()
    writer.append(str(cover))
    for (title, _, pdf), (_, _, start) in zip(parts, entries):
        writer.append(str(pdf), outline_item=title)
    writer.add_metadata({'/Title': 'Ji-Hajoor Documentation Pack', '/Author': 'Ji-Hajoor · Condigence LLP'})
    with open(OUT / 'JiHajoor_Documentation_Pack.pdf', 'wb') as fh:
        writer.write(fh)
    cover.unlink()
    print('wrote JiHajoor_Documentation_Pack.pdf')


if __name__ == '__main__':
    main()
