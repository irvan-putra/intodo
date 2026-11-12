# itdweb

Authored source for the itdweb review-canvas page: an interactive canvas where every
change to the intodo project becomes a card you can read in under a minute, with
review, test and proof markers connected spatially to their evidence.

- `src/App.tsx` - the page: a bounded, pannable/zoomable canvas of review cards with
  a minimap; card positions and camera persist locally on the viewer's device.
- `src/main.tsx` - entry point.
- `src/style.css` - canvas and card styles.
- `src/photos/review-concept-hero.png` - concept illustration shown above the canvas.

The page is written in React + TypeScript against the hosted-page component kit it
runs on (`@instinct/files`: Header, Group, Text, Photos, Caption, local-state hooks).
That kit is not part of this repository; this directory holds the authored page code
itself, published for source transparency alongside itdapk (app/) and itdxdc (webxdc/).
