# Iconography attribution

The 25 SVG files in this directory are sourced from **Google Material Symbols**,
the icon set published by Google as part of [Material Design](https://fonts.google.com/icons).

- **Source:** https://fonts.google.com/icons (Material Symbols, Outlined style, weight 400, grade 0, optical size 24)
- **Upstream repository:** https://github.com/google/material-design-icons
- **Copyright:** Copyright 2014 Google LLC
- **License:** Apache License, Version 2.0 — full text in [`LICENSE-APACHE-2.0.txt`](LICENSE-APACHE-2.0.txt)

The icons are used **unmodified**, only renamed from Material Symbols' default
download filenames (e.g. `add_24dp_1F1F1F_FILL0_wght400_GRAD0_opsz24.svg`) to
the project's canonical kebab-case form (e.g. `plus.svg`) so that the
`:core:core-designsystem:generateIcons` Gradle task can map filenames directly
to generated `FluxItIcons.*` identifiers. Generated outputs (Compose
`ImageVector` declarations + iOS `xcassets` imagesets) are also derivative
works of these SVGs and therefore inherit the Apache-2.0 license.

## File map

| Filename | Material Symbols source | Used for |
|---|---|---|
| `cart.svg` | `shopping_cart` | List category: groceries |
| `home.svg` | `home` | List category: household |
| `briefcase.svg` | `work` | List category: work |
| `plane.svg` | `flight` | List category: travel |
| `fork-knife.svg` | `restaurant` | List category: food |
| `dumbbell.svg` | `exercise` | List category: fitness |
| `star.svg` | `star` (Fill = 0) | Starred tab (inactive), favorited items |
| `star-filled.svg` | `star` (Fill = 1) | Starred tab (active) |
| `more.svg` | `more_horiz` | Row overflow menu |
| `trash.svg` | `delete` | Destructive actions |
| `chevron-right.svg` | `chevron_right` | Row trailing affordance |
| `chevron-left.svg` | `chevron_left` | Back navigation |
| `search.svg` | `search` | `FluxItSearchField` leading icon |
| `plus.svg` | `add` | FAB, inline composer submit |
| `arrow-up.svg` | `arrow_upward` | Inline composer submit (alt), scroll-to-top |
| `check.svg` | `check` | Completed item state |
| `bell.svg` | `notifications` | Reminder editor |
| `camera.svg` | `photo_camera` | Item-detail photo capture |
| `settings.svg` | `settings` | TopBar trailing button |
| `account.svg` | `person` (Fill = 0) | Account tab (inactive) |
| `account-filled.svg` | `person` (Fill = 1) | Account tab (active) |
| `calendar.svg` | `calendar_today` (Fill = 0) | Calendar tab (inactive), date picker |
| `calendar-filled.svg` | `calendar_today` (Fill = 1) | Calendar tab (active) |
| `list.svg` | `format_list_bulleted` (Fill = 0) | Lists tab (inactive) |
| `list-filled.svg` | `format_list_bulleted` (Fill = 1) | Lists tab (active) |

## Adding a new icon

1. Download the SVG from [Material Symbols](https://fonts.google.com/icons) —
   use **Outlined** style, weight **400**, grade **0**, optical size **24**
   for consistency with the existing set.
2. Save it into this directory with the kebab-case filename you want exposed
   as `FluxItIcons.YourIconName` (Compose) and `FluxItTokens.Icons.yourIconName`
   (SwiftUI).
3. Add a row to the table above.
4. Run `./gradlew :core:core-designsystem:generateIcons` (or any
   `compileKotlin`/`scripts/build-ios.sh` invocation will trigger it
   transitively).

The generator constrains the accepted SVG dialect: single `<path>`, no
transforms, no gradients, no arcs (`A`/`a` commands). Material Symbols icons
satisfy these constraints out of the box.

## In-app surfacing

A future "Licenses" screen (target: Phase 17 release hardening) will read
this file and `LICENSE-APACHE-2.0.txt` to surface third-party attributions to
end users. Until then, this directory itself is the authoritative record.
