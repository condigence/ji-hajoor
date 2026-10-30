// Shared building blocks for the Ji-Hajoor decks: theme, layouts, icons and slide patterns.
// Run `node docs/source/build_decks.js` from the repository root (needs pptxgenjs, react,
// react-dom, react-icons and sharp; see docs/source/README.md).
const pptxgen = require('pptxgenjs');
const React = require('react');
const ReactDOMServer = require('react-dom/server');
const sharp = require('sharp');

const THEME = {
  name: 'Ji-Hajoor',
  headFontFace: 'Cambria',
  bodyFontFace: 'Calibri',
  colors: {
    dk1: '1D2939', // body text
    lt1: 'FFFFFF',
    dk2: '0F2A6E', // Ji-Hajoor deep blue
    lt2: 'F2F5FC', // soft canvas for cards
    accent1: '2251D1', // Ji-Hajoor Blue
    accent2: 'F2A516', // Marigold
    accent3: '0F766E', // Pro Teal
    accent4: '12875A', // Success green
    accent5: 'D92D20', // Alert red
    accent6: '5F6B7E', // Muted
    hlink: '2251D1',
    folHlink: '1A3FA6',
  },
};
const HEX = THEME.colors;
const FOOTER = 'Ji-Hajoor · Verified Help. Anytime. Anywhere. · Powered by Condigence LLP';

function newDeck(title) {
  const pres = new pptxgen();
  pres.layout = 'LAYOUT_16x9';
  pres.title = title;
  pres.author = 'Ji-Hajoor';
  pres.company = 'Condigence LLP';
  pres.theme = { headFontFace: THEME.headFontFace, bodyFontFace: THEME.bodyFontFace };
  const C = pres.SchemeColor;

  pres.defineSlideMaster({
    title: 'JH_TITLE',
    background: { color: C.text2 },
    objects: [
      { placeholder: { options: { name: 'title', type: 'title', align: 'left', x: 0.6, y: 1.35, w: 5.9, h: 1.2,
        fontSize: 44, bold: true, color: C.background1, valign: 'bottom', margin: 0 }, text: '' } },
      { placeholder: { options: { name: 'subtitle', type: 'body', align: 'left', x: 0.6, y: 2.7, w: 5.9, h: 0.9,
        fontSize: 20, color: 'F2A516', valign: 'top', margin: 0 }, text: '' } },
      { placeholder: { options: { name: 'meta', type: 'body', align: 'left', x: 0.6, y: 4.55, w: 6.5, h: 0.45,
        fontSize: 12, color: 'D6E0FA', valign: 'middle', margin: 0 }, text: '' } },
    ],
  });
  pres.defineSlideMaster({
    title: 'JH_SECTION',
    background: { color: C.accent1 },
    objects: [
      { placeholder: { options: { name: 'kicker', type: 'body', align: 'left', x: 0.6, y: 1.55, w: 8.8, h: 0.5,
        fontSize: 16, bold: true, color: 'F2A516', margin: 0 }, text: '' } },
      { placeholder: { options: { name: 'title', type: 'title', align: 'left', x: 0.6, y: 2.05, w: 8.8, h: 1.45,
        fontSize: 40, bold: true, color: C.background1, valign: 'top', margin: 0 }, text: '' } },
      { placeholder: { options: { name: 'body', type: 'body', align: 'left', x: 0.6, y: 3.6, w: 8.0, h: 0.9,
        fontSize: 16, color: C.background1, valign: 'top', margin: 0 }, text: '' } },
    ],
    slideNumber: { x: 9.0, y: 5.2, w: 0.5, h: 0.3, fontSize: 10, color: 'D6E0FA', align: 'right' },
  });
  pres.defineSlideMaster({
    title: 'JH_CONTENT',
    background: { color: C.background1 },
    margin: [0.5, 0.5, 0.55, 0.5],
    objects: [
      { placeholder: { options: { name: 'title', type: 'title', align: 'left', x: 0.5, y: 0.3, w: 9.0, h: 0.7,
        fontSize: 30, bold: true, color: C.text2, valign: 'bottom', margin: 0 }, text: '' } },
      { placeholder: { options: { name: 'sub', type: 'body', align: 'left', x: 0.5, y: 1.02, w: 9.0, h: 0.4,
        fontSize: 14, color: C.accent6, valign: 'top', margin: 0 }, text: '' } },
      { text: { text: FOOTER, options: { x: 0.5, y: 5.22, w: 7.5, h: 0.28, fontSize: 9,
        color: '98A2B3', margin: 0 } } },
    ],
    slideNumber: { x: 9.0, y: 5.22, w: 0.5, h: 0.28, fontSize: 9, color: '98A2B3', align: 'right' },
  });
  pres.defineSlideMaster({
    title: 'JH_CLOSING',
    background: { color: C.text2 },
    objects: [
      { placeholder: { options: { name: 'title', type: 'title', align: 'left', x: 0.6, y: 1.2, w: 8.8, h: 1.3,
        fontSize: 36, bold: true, color: C.background1, valign: 'bottom', margin: 0 }, text: '' } },
      { placeholder: { options: { name: 'body', type: 'body', align: 'left', x: 0.6, y: 2.7, w: 8.8, h: 1.6,
        fontSize: 16, color: C.background1, valign: 'top', margin: 0 }, text: '' } },
      { text: { text: '© 2026 Ji-Hajoor. Verified Help. Anytime. Anywhere. Powered by Condigence LLP.',
        options: { x: 0.6, y: 4.85, w: 8.8, h: 0.35, fontSize: 11, color: 'D6E0FA', margin: 0 } } },
    ],
  });
  return { pres, C };
}

// ---------- icons ----------
const iconCache = new Map();
async function icon(IconComponent, hex, size = 256) {
  const key = `${IconComponent.name}-${hex}`;
  if (iconCache.has(key)) return iconCache.get(key);
  const svg = ReactDOMServer.renderToStaticMarkup(
    React.createElement(IconComponent, { color: `#${hex}`, size: String(size) }));
  const buf = await sharp(Buffer.from(svg)).png().toBuffer();
  const data = 'image/png;base64,' + buf.toString('base64');
  iconCache.set(key, data);
  return data;
}

let objectCounter = 0;
const nm = (p) => `${p}-${++objectCounter}`;

// A filled circle with a white (or given) icon centred in it.
async function iconCircle(pres, slide, Icon, x, y, d, fill, iconHex = 'FFFFFF') {
  slide.addShape(pres.shapes.OVAL, { x, y, w: d, h: d, fill: { color: fill }, line: { color: fill },
    objectName: nm('icon-circle') });
  const pad = d * 0.26;
  slide.addImage({ data: await icon(Icon, iconHex), x: x + pad, y: y + pad, w: d - 2 * pad,
    h: d - 2 * pad, objectName: nm('icon') });
}

function shadow() {
  return { type: 'outer', color: '0F2A6E', blur: 8, offset: 2, angle: 90, opacity: 0.12 };
}

// A soft card: tinted background, optional icon, title and body.
async function card(pres, slide, C, { x, y, w, h, title, body, Icon, color, fill, titleSize = 18,
  bodySize = 15, kicker }) {
  slide.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y, w, h, rectRadius: 0.08,
    fill: { color: fill || C.background2 }, line: { color: fill || C.background2 },
    objectName: nm('card') });
  let ty = y + 0.2;
  if (Icon) {
    await iconCircle(pres, slide, Icon, x + 0.2, y + 0.2, 0.55, color || C.accent1);
    ty = y + 0.9;
  }
  if (kicker) {
    slide.addText(kicker, { x: x + 0.2, y: ty, w: w - 0.4, h: 0.3, fontSize: 11, bold: true,
      color: color || C.accent1, margin: 0, isTextBox: true, objectName: nm('kicker') });
    ty += 0.3;
  }
  const perLine = Math.max(1, Math.floor(((w - 0.4) * 72) / (titleSize * 0.55)));
  const titleLines = Math.ceil(title.length / perLine);
  const th = 0.12 + titleLines * titleSize * 1.2 / 72;
  slide.addText(title, { x: x + 0.2, y: ty, w: w - 0.4, h: th, fontSize: titleSize, bold: true,
    color: C.text2, margin: 0, valign: 'top', isTextBox: true, fontFace: THEME.headFontFace,
    objectName: nm('card-title') });
  if (body) {
    slide.addText(body, { x: x + 0.2, y: ty + th + 0.05, w: w - 0.4, h: y + h - (ty + th + 0.05) - 0.15,
      fontSize: bodySize, color: C.text1, margin: 0, valign: 'top', isTextBox: true,
      objectName: nm('card-body') });
  }
}

// n cards side by side across the content width.
async function cardsRow(pres, slide, C, items, { y = 1.6, h = 3.3, x = 0.5, w = 9.0, gap = 0.3 } = {}) {
  const cw = (w - gap * (items.length - 1)) / items.length;
  for (let i = 0; i < items.length; i++) {
    await card(pres, slide, C, { x: x + i * (cw + gap), y, w: cw, h, ...items[i] });
  }
}

// A 2 x 2 grid of icon rows (icon left, text right), no card fill.
async function iconGrid(pres, slide, C, items, { y = 1.65, rowH = 1.6, cols = 2, x = 0.5, w = 9.0,
  gap = 0.4, color } = {}) {
  const cw = (w - gap * (cols - 1)) / cols;
  for (let i = 0; i < items.length; i++) {
    const cx = x + (i % cols) * (cw + gap);
    const cy = y + Math.floor(i / cols) * rowH;
    const it = items[i];
    await iconCircle(pres, slide, it.Icon, cx, cy, 0.6, it.color || color || C.accent1);
    slide.addText(it.title, { x: cx + 0.8, y: cy, w: cw - 0.8, h: 0.4, fontSize: 17, bold: true,
      color: C.text2, margin: 0, fontFace: THEME.headFontFace, isTextBox: true,
      objectName: nm('grid-title') });
    slide.addText(it.body, { x: cx + 0.8, y: cy + 0.42, w: cw - 0.8, h: rowH - 0.55, fontSize: 14,
      color: C.text1, margin: 0, valign: 'top', isTextBox: true, objectName: nm('grid-body') });
  }
}

// Numbered steps joined by arrows.
function flow(pres, slide, C, steps, { y = 1.75, x = 0.5, w = 9.0, color, textH = 1.9 } = {}) {
  const n = steps.length;
  const sw = w / n;
  const d = 0.7;
  for (let i = 0; i < n; i++) {
    const cx = x + i * sw + (sw - d) / 2;
    if (i < n - 1) {
      slide.addShape(pres.shapes.LINE, { x: cx + d + 0.08, y: y + d / 2, w: sw - d - 0.16, h: 0,
        line: { color: 'B4C3EE', width: 2, endArrowType: 'triangle' }, objectName: nm('flow-arrow') });
    }
    slide.addShape(pres.shapes.OVAL, { x: cx, y, w: d, h: d, fill: { color: color || C.accent1 },
      line: { color: color || C.accent1 }, objectName: nm('flow-dot') });
    slide.addText(String(i + 1), { x: cx, y, w: d, h: d, fontSize: 20, bold: true,
      color: C.background1, align: 'center', valign: 'middle', margin: 0, isTextBox: true,
      objectName: nm('flow-num') });
    slide.addText(steps[i].title, { x: x + i * sw + 0.05, y: y + d + 0.15, w: sw - 0.1, h: 0.4,
      fontSize: 17, bold: true, color: C.text2, align: 'center', margin: 0,
      fontFace: THEME.headFontFace, isTextBox: true, objectName: nm('flow-title') });
    slide.addText(steps[i].body, { x: x + i * sw + 0.08, y: y + d + 0.6, w: sw - 0.16, h: textH,
      fontSize: 14, color: C.text1, align: 'center', valign: 'top', margin: 0, isTextBox: true,
      objectName: nm('flow-body') });
  }
}

// Big number with a label underneath.
function stat(slide, C, { x, y, w, value, label, color, valueSize = 40 }) {
  slide.addText(value, { x, y, w, h: 0.75, fontSize: valueSize, bold: true, color: color || C.accent1,
    margin: 0, fontFace: THEME.headFontFace, valign: 'bottom', isTextBox: true, objectName: nm('stat') });
  slide.addText(label, { x, y: y + 0.8, w, h: 0.6, fontSize: 14, color: C.text1, margin: 0,
    valign: 'top', isTextBox: true, objectName: nm('stat-label') });
}

// A styled table; rows[0] is the header.
function table(slide, C, rows, { x = 0.5, y = 1.6, w = 9.0, colW, fontSize = 13, rowH = 0.36 } = {}) {
  const data = rows.map((r, ri) => r.map((cell) => {
    const t = typeof cell === 'string' ? cell : cell.text;
    const extra = typeof cell === 'string' ? {} : cell.options || {};
    return {
      text: t,
      options: ri === 0
        ? { bold: true, color: C.background1, fill: { color: C.text2 }, fontSize: fontSize, ...extra }
        : { color: C.text1, fill: { color: ri % 2 ? C.background1 : C.background2 }, fontSize, ...extra },
    };
  }));
  slide.addTable(data, { x, y, w, colW, rowH, border: { type: 'solid', pt: 0.5, color: 'E4E7EC' },
    margin: [0.04, 0.08, 0.04, 0.08], valign: 'middle', fontFace: THEME.bodyFontFace,
    objectName: nm('table') });
}

// A rounded box with text, used for diagrams.
function box(pres, slide, C, { x, y, w, h, text, fill, color, fontSize = 14, bold = true, line }) {
  slide.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y, w, h, rectRadius: 0.08,
    fill: { color: fill || C.background2 }, line: { color: line || fill || C.background2, width: 1 },
    objectName: nm('box') });
  slide.addText(text, { x: x + 0.08, y, w: w - 0.16, h, fontSize, bold, color: color || C.text2,
    align: 'center', valign: 'middle', margin: 0, isTextBox: true, objectName: nm('box-text') });
}

function arrow(pres, slide, { x, y, w, h, color = '8EA4E6', flipH = false, flipV = false }) {
  slide.addShape(pres.shapes.LINE, { x, y, w, h, flipH, flipV,
    line: { color, width: 2, endArrowType: 'triangle' }, objectName: nm('arrow') });
}

// A highlighted call-out strip.
function callout(pres, slide, C, text, { x = 0.5, y = 4.35, w = 9.0, h = 0.65, fill = 'FFF6E2',
  color = '6B4100', fontSize = 14 } = {}) {
  slide.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y, w, h, rectRadius: 0.08, fill: { color: fill },
    line: { color: fill }, objectName: nm('callout') });
  slide.addText(text, { x: x + 0.25, y, w: w - 0.5, h, fontSize, color, valign: 'middle', margin: 0,
    isTextBox: true, objectName: nm('callout-text') });
}

function content(pres, section, title, sub, notes) {
  const s = pres.addSlide({ masterName: 'JH_CONTENT', sectionTitle: section });
  s.addText(title, { placeholder: 'title' });
  if (sub) s.addText(sub, { placeholder: 'sub' });
  if (notes) s.addNotes(notes);
  return s;
}

async function save(pres, file) {
  const { applyTheme } = require(process.env.PPTX_SKILL + '/scripts/apply_theme.js');
  await pres.writeFile({ fileName: file });
  await applyTheme(file, THEME);
  console.log('wrote', file);
}

module.exports = { newDeck, THEME, HEX, icon, iconCircle, card, cardsRow, iconGrid, flow, stat, table,
  box, arrow, callout, content, save, shadow };
