---
name: Lumina Lists
colors:
  background-dark: '#101822'
  surface-card: '#1e2632'
  surface-search: '#1e2632'
  text-primary: '#ffffff'
  text-muted: '#9da8b9'
  accent-orange: '#f97316'
  accent-emerald: '#10b981'
  accent-rose: '#f43f5e'
  accent-indigo: '#6366f1'
typography:
  display-lg:
    fontFamily: Inter
    fontSize: 32px
    fontWeight: '700'
    lineHeight: '1.2'
    letterSpacing: -0.02em
  title-md:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '600'
    lineHeight: '1.4'
  body-md:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: '1.5'
  label-sm:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: '1.4'
  caption-xs:
    fontFamily: Inter
    fontSize: 10px
    fontWeight: '500'
    lineHeight: '1'
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  container-padding: 1rem
  stack-gap: 0.5rem
  item-padding-x: 1rem
  item-padding-y: 0.75rem
  fab-offset: 2rem
---

## Brand & Style

Lumina Lists is defined by a **Corporate / Modern** aesthetic with subtle **Glassmorphic** influences. It targets high-performance individuals who require a tool that is both efficient and aesthetically calming. The brand personality is organized, dependable, and sophisticated, avoiding the playfulness of typical consumer apps in favor of a focused, utility-first experience.

The visual style utilizes a dark-mode-first approach where depth is created through varying levels of surface luminosity rather than traditional heavy shadows. It prioritizes clarity, utilizing a generous vertical rhythm and clear iconography to reduce cognitive load during list management.

## Colors
The palette is rooted in a deep slate-based dark mode (`#101822`).

- **Primary:** A vibrant Blue (`#2b7cee`) serves as the main action color for FABs and active states.
- **Surface Strategy:** Cards and inputs use a slightly lifted navy-grey (`#1e2632`) to distinguish them from the background.
- **Semantic Accents:** A spectrum of high-chroma semantic colors (Orange, Emerald, Rose, Indigo) is used exclusively for category iconography, providing immediate visual scannability without overwhelming the interface.
- **Typography:** Pure white is reserved for high-contrast headlines, while a cool grey-blue (`#9da8b9`) is used for secondary metadata to establish clear information hierarchy.

## Typography
The system uses **Inter** exclusively to lean into a systematic and utilitarian feel.

Headlines are bold and tightly tracked to feel impactful and grounded. Body text relies on standard weights for maximum readability, while metadata (labels) uses a reduced font size and a muted color token. The navigation system uses a specialized 10px caption for icon labels to maintain a compact vertical footprint in the tab bar.

## Layout & Spacing
The layout follows a **Fixed Grid** model optimized for mobile-first views (max-width: 448px/28rem) with centered alignment on larger screens.

- **Margins:** A consistent 16px (1rem) safe area is maintained on the horizontal axis.
- **Rhythm:** List items are separated by a 4px gap but housed within a vertical stack that prioritizes 8px or 12px increments.
- **Sticky Elements:** The header and navigation bar are fixed with a backdrop-blur effect (80% opacity) to create a sense of continuity as the user scrolls.

## Elevation & Depth
Elevation is achieved through a mix of **Tonal Layers** and **Ambient Shadows**:

- **Level 0 (Base):** The main application background (`#101822`).
- **Level 1 (Surface):** Cards and Search bars utilize `#1e2632`. List items have a subtle 50% opacity in their resting state, brightening to 100% on interaction.
- **Level 2 (Floating):** The Floating Action Button (FAB) uses a high-spread ambient shadow tinted with the primary color (`shadow-primary/40`) to suggest physical height above the list.
- **Blur Effects:** Navigation bars and headers use a `backdrop-blur-md` (approx 12px-16px blur) to maintain context of the content passing beneath them.

## Shapes
The shape language is consistently **Rounded**.

- **Primary Containers:** Search bars and list items use a 12px (`rounded-xl`) corner radius.
- **Interactive Small Elements:** Icons and profile containers use a 10px or fully circular (`rounded-full`) radius for a softer touch.
- **Buttons:** Utility buttons (like delete) are housed in circular hit areas, while the primary FAB is a perfect circle to denote its unique status as the main action.

## Components

- **List Items:** Feature a three-slot layout: a 56px (`size-14`) leading icon container with a 20% opacity background tint, a central title/subtitle stack, and a trailing action/indicator slot.
- **Floating Action Button (FAB):** A 64px (`size-16`) circle centered at the bottom, utilizing the primary brand color and a heavy shadow.
- **Search Input:** A full-width component with integrated leading icons, no border, and a subtle inner shadow or flat fill.
- **Tab Bar:** An iOS-inspired bottom navigation with a height of 80px, utilizing `backdrop-blur` and centered vertical stacks for icon/label pairs.
- **Iconography:** Uses Material Symbols Outlined, maintaining a weight of 400 and an optical size of 24px, with specific fills used only for active navigation states.
