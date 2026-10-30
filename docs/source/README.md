# Rebuilding the decks and PDFs

The decks and PDFs in `docs/` are generated from the scripts here, so they can be rebuilt after any
change to the content. Edit the text in the script (decks) or the Markdown file (guides and
documents), then run the steps below from the repository root.

| Output | Source |
|---|---|
| `JiHajoor_Pitch_Deck.pptx`, `JiHajoor_Revenue_Model.pptx`, `JiHajoor_Tech_Stack.pptx`, `JiHajoor_Brand_and_Marketing.pptx` | `build_decks.js` (slides) and `lib.js` (theme, layouts, patterns) |
| Slide 2 of `JiHajoor_Architecture.pptx` and `JiHajoor_Design_Deck.pptx` ("What changed on 30 October 2026") | `update_existing_decks.py` (runs once per deck) |
| `pdf/*.pdf` and `pdf/JiHajoor_Documentation_Pack.pdf` | `build_pdfs.py`, from `guides/*.md`, `REVENUE_MODEL.md`, `BRAND_AND_MARKETING.md` and the decks |

```bash
# 1. Decks (Node 20+, with pptxgenjs, react, react-dom, react-icons and sharp installed)
PPTX_SKILL=<folder holding scripts/apply_theme.js> node docs/source/build_decks.js   # or: ... build_decks.js pitch

# 2. Update slide on the two older decks (python-pptx)
python3 docs/source/update_existing_decks.py

# 3. PDFs (markdown, pypdf, playwright with Chromium, and LibreOffice)
python3 docs/source/build_pdfs.py --soffice soffice
```

Follow [`../BRAND_AND_MARKETING.md`](../BRAND_AND_MARKETING.md) for names and colours: Ji-Hajoor,
Ji-Hajoor Pro, Ji-Hajoor Admin; Ji-Hajoor Blue `#2251D1`, Deep Blue `#0F2A6E`, Marigold `#F2A516`,
Pro Teal `#0F766E`; Cambria headings and Calibri body text.
