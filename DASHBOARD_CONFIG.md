# Customizing Your Android Auto Dashboard (`config.json`)

**EV Companion** allows you to fully customize what appears on your car's screen by importing a custom dashboard layout JSON file directly from the phone app (**Preset tab → Import dashboard JSON**).

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

Every field below is optional unless marked required. PID names are matched case-insensitively
as substrings, and a list of names is tried in order, so `["Battery Power", "000_Battery Power"]`
works with presets that number their rows and ones that don't.

### Tiles (`pages[].tiles[]`)

| Field | Type | Description |
|---|---|---|
| `id` *(required)* | String | Unique identifier (e.g. `"power"`). `"diagnostics"` and `"lights"` get special built-in drawing. |
| `label` | String | Heading shown on the tile. |
| `values` | Array | The values to show (see below). One value is a readout, or a ring gauge if `gaugeRange` is set; several values form a small grid. |
| `gaugeRange` | `[min, max]` | Gauge limits, e.g. `[0, 100]` for SoC or `[-50, 150]` for kW. |
| `gaugeColorRules` | Object | Gauge ring color by value (see Color rules). |
| `backgroundColorRules` | Object | Card background color, e.g. red when a fault flag is set (see Color rules). |
| `hero` | Object | What the tile shows when promoted to the big slot (see Hero). |
| `detail` | String | Id of a `detailViews` entry, opened by tapping the tile once it is already the hero. |
| `showUnits` | Boolean | Show units next to numbers (default `true`). |
| `extraNeedles` | Array of names | Extra PIDs to poll while this tile's page is showing, even though the tile doesn't display them. Use it when the app needs a value for logic, e.g. the Diagnostics tile polls speed and gear so clearing codes can check the car is parked. |

### Values (`values[]`)

| Field | Type | Description |
|---|---|---|
| `pidNames` *(required)* | Array of names | Which PID to show; the first name that matches wins. |
| `sublabel` | String | Small caption under the number (e.g. `"MIN"`). |
| `negate` | Boolean | Flip the sign for display only, e.g. to show charging current as positive. |
| `showIfPidName` / `showIfEquals` | String / Number | Hide this value unless that other PID currently equals `showIfEquals` (default `1`), e.g. a heater temperature shown only while the heater is on. |

### Hero (`hero`)

| Field | Type | Description |
|---|---|---|
| `type` | `"value"`, `"dial"` or `"graph"` | `value`/`dial` show the tile's first value large; `graph` shows a live line graph. Default `"value"`. |
| `series` | Array | Graph lines: `{ "pidNames": [...], "label": "...", "color": "#RRGGBB", "negate": false }`. |
| `windowSeconds` | Number | How many seconds of history the graph shows (default `300`). History is kept in memory only. |

### Color rules (`gaugeColorRules`, `backgroundColorRules`)

```json
{ "rules": [ { "compare": "gt", "threshold": 45, "color": "#DC3C3C" } ], "default": "#3CC864" }
```

The first rule that matches wins; `default` applies otherwise. `compare` is `"lt"`, `"gt"` or
`"eq"`. A rule without `pidNames` compares the tile's own value; a rule with `pidNames` compares
that other PID instead (e.g. turn the card green while a "charging" flag equals 1).

### Detail views (`detailViews`)

A map of id to `{ "label": "...", "panels": [...] }`. Each panel has a `type` (`"value"`,
`"dial"` or `"graph"`) and takes the same `pidNames`, `label`, `negate`, `showUnits`,
`gaugeRange`, `gaugeColorRules`, `series` and `windowSeconds` fields as above. Graph panels also
take `normalizePerSeries`: `false` (default) scales all lines to one shared range, which is right
when they share a unit; `true` scales each line to its own range, for overlaying different units
such as SoC %, kW and °C.

### Custom PIDs (`customPids`)

Defines a PID the vehicle preset doesn't have. It is added to the active preset's rows and polled
like any other, and tiles reference it by `name`. The bundled default uses one:

```json
{
  "name": "Requested Charge Power",
  "shortName": "Req Power",
  "header": "7E4",
  "modeAndPid": "0x220101",
  "formula": "(((m<8)+n)/10 * ((h<8)+i)/10) / 1000",
  "unit": "kW",
  "min": 0,
  "max": 360
}
```

`header` is the ECU address, `modeAndPid` the request, and `formula` uses the same syntax as PID
CSV files: response bytes are letters `a`, `b`, ... `z`, `aa`, `ab`, ... (see
[PID_CONFIG.md](PID_CONFIG.md)). Here `m`/`n` is the pack voltage and `h`/`i` the requested
charge current, both in tenths.

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
