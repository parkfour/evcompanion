# Customizing Your Android Auto Dashboard (`config.json`)

**EV Companion** allows you to fully customize what appears on your car's screen by importing a custom dashboard layout JSON file directly from the phone app (**Presets tab → Import dashboard JSON**).

---

## Overview

* **Pages & Organization**: Define any number of dashboard pages (e.g., *Driving*, *Charging*, *Diagnostics*, *Other*), each with its own title and tile layout.
* **Tile Placement & Metrics**: Place 1–8 tiles per page. Index 0 is your promoted driver-side "Hero" tile; the remaining tiles automatically arrange into a clean, static 2-row secondary grid.
* **Gauges & Styles**: Configure swept-arc dials, digital readouts, or live time-series line graphs with custom ranges and dynamic color thresholds (e.g., green at low temps, turning red when hot).
* **Deep-Dive Detail Views**: Tap an already-promoted hero tile to switch into multi-gauge or multi-line graph detail panels.
* **Vehicle Portability**: Tiles reference metrics by PID name with fallback support (e.g., `["Battery Power", "000_Battery Power"]`), so layouts work seamlessly across different vehicle presets.

---

## Safe & Non-Destructive

* **Lenient Parsing**: If there's a typo in a single tile or color rule, the app skips just that item and displays a helpful warning under the import button rather than crashing or rejecting the whole file.
* **One-Tap Reset**: You can revert back to the factory layout anytime by tapping **"Reset to default"**.

---

## File Structure

A dashboard JSON file contains three top-level sections:
1. `pages` *(required)*: The array of dashboard pages and tiles.
2. `detailViews` *(optional)*: Full-screen multi-gauge / graph layouts triggered by tapping a promoted hero tile.
3. `customPids` *(optional)*: Extra calculated or OBD PIDs defined directly in the layout file.

### Tile Definition Attributes

| Field | Type | Description |
|---|---|---|
| `id` | String | Unique identifier for the tile (e.g. `"power"`, `"efficiency"`). |
| `label` | String | Primary label displayed on the tile or gauge. |
| `values` | Array | Array of PID references to display. Multi-value tiles render as a clean grid. |
| `gaugeRange` | `[min, max]` | Defines the gauge limits (e.g. `[0, 100]` for SoC or `[-50, 150]` for kW). |
| `gaugeColorRules` | Object | Dynamic color rules for the gauge ring based on value thresholds. |
| `backgroundColorRules` | Object | Dynamic background color for the tile card (e.g., alert states). |
| `hero` | Object | Defines hero behavior when promoted: `{"type": "dial"}` or `{"type": "graph"}`. |
| `detail` | String | References an ID in `detailViews` for secondary hero taps. |
| `showUnits` | Boolean | Whether to display units (`kW`, `V`, `psi`, `°C`) next to numbers (default: `true`). |

---

## Starter Template

The easiest way to build your own is to start with the bundled default: [`app/src/main/assets/dashboards/default.json`](app/src/main/assets/dashboards/default.json).

```json
{
  "pages": [
    {
      "title": "Driving",
      "label": "Driving",
      "tiles": [
        {
          "id": "power",
          "label": "POWER",
          "values": [
            { "pidNames": ["Battery Power", "000_Battery Power"] }
          ],
          "gaugeRange": [-50, 150],
          "gaugeColorRules": {
            "rules": [
              { "compare": "lt", "threshold": 0.0, "color": "#00E5FF" },
              { "compare": "gt", "threshold": 100.0, "color": "#DC3C3C" }
            ],
            "default": "#3CC864"
          },
          "hero": { "type": "dial" }
        },
        {
          "id": "soc",
          "label": "SOC",
          "values": [
            { "pidNames": ["State of Charge Display", "SOC Display", "SOC"] }
          ],
          "gaugeRange": [0, 100],
          "hero": { "type": "dial" }
        },
        {
          "id": "battery_temps",
          "label": "PACK TEMPS",
          "values": [
            { "pidNames": ["Battery Min Temperature", "000_Battery Min Temperature"], "sublabel": "MIN" },
            { "pidNames": ["Battery Max Temperature", "000_Battery Max Temperature"], "sublabel": "MAX" }
          ]
        }
      ]
    }
  ]
}
```
