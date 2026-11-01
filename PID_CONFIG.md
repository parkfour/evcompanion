# Custom Vehicle PID Files (`*.csv`)

**EV Companion** supports standard Torque Pro-compatible custom PID CSV files, allowing you to bring telemetry definitions for any electric vehicle (e.g., Hyundai Ioniq 5 / Kia EV6, Kia Niro, Chevy Bolt, Ford Mustang Mach-E, etc.).

You can import PID files directly from the phone app (**Presets tab → Import PID file (CSV)**).

---

## Overview

* **Bring Your Own EV**: If your vehicle's telemetry PIDs are known, you can import a CSV file to monitor battery cells, motor RPM, temperatures, power limits, and custom metrics.
* **Vehicle-Agnostic DTC Diagnostics**: The app's Diagnostic Trouble Code (DTC) scanner automatically derives its target ECU headers (e.g., `7E4`, `7E2`, `7E5`, `7DF`) directly from the headers specified in your active PID file.
* **Dashboard Integration**: The Android Auto dashboard maps its gauges and graphs to PIDs using the `Name` column defined in your CSV.
* **Safe & Non-Destructive**: Malformed rows or formula typos are skipped with line-by-line warnings rather than rejecting the whole file. User-imported presets can be removed anytime with the **Delete** button.

---

## CSV File Format

A valid PID file is a standard comma-separated text file containing an 8-column header row:

```csv
Name,ShortName,ModeAndPID,Equation,Min Value,Max Value,Units,Header
```

### Column Definitions

| Column | Required | Description | Example |
|---|---|---|---|
| **`Name`** | Yes | Full unique name of the PID. Referenced by dashboard layouts and formulas. | `000_Battery Power` or `State of Charge Display` |
| **`ShortName`** | No | Shorter abbreviation for compact displays. | `Power`, `SOC` |
| **`ModeAndPID`** | Yes* | Diagnostic Service Mode and PID in hex (e.g., `0x220101` or `220101`). Can be omitted for calculated (`val{}`) rows. | `0x220101` |
| **`Equation`** | Yes | Mathematical formula to decode raw response bytes into engineering units. | `((SIGNED(a)*256)+b)/10` |
| **`Min Value`** | No | Lower sensor range hint. | `-100` |
| **`Max Value`** | No | Upper sensor range hint. | `300` |
| **`Units`** | No | Display units. | `kW`, `V`, `A`, `°C`, `%`, `psi` |
| **`Header`** | Yes* | Target CAN ECU address in hex (e.g., `7E4` for BMS, `7E2` for MCU). Leave blank or `7DF` for broadcast OBD. | `7E4` |

*\*Lines starting with `~` are treated as comments and ignored.*

---

## Formula Syntax & Functions

EV Companion supports standard Torque equation syntax:

### Raw Byte Access
* **`a`, `b`, `c`, ... `z`, `aa`, `ab`, ...**: 0-indexed byte offsets in the raw diagnostic response payload.
* **`{byte:bit}`**: Extracts a specific bit (0–7) from a byte offset. For example:
  * `{0:0}` extracts bit 0 of byte 0.
  * `{F:1}` extracts bit 1 of byte 15 (hex `F`).

### Operators
* Basic arithmetic: `+`, `-`, `*`, `/`
* Parentheses for precedence: `(a * 256 + b) / 10`
* Bitwise shift: `<` (left shift, e.g. `a < 8 + b`)

### Built-in Functions
* **`SIGNED(x)`**: Interprets single byte `x` as a signed 8-bit integer (-128 to 127).
* **`INT24(a, b, c)`**: Combines three bytes into a 24-bit unsigned integer `((a << 16) | (b << 8) | c)`.
* **`MAX(x, y)`**: Returns the maximum of two values.

### Calculated / Inter-PID References
* **`val{Pid Name}`**: Reads the resolved value of another PID in the preset. Useful for derived calculations (e.g., combining pack voltage and current into kilowatts):
  ```csv
  Calculated Power kW,Power,,val{Battery Voltage} * val{Battery Current} / 1000,-150,300,kW,
  ```

---

## Starter Examples & Resources

* **Bundled Presets**: Sample CSV files are available in [`app/src/main/assets/presets/`](app/src/main/assets/presets/) (e.g. `ioniq5_awd_77kwh.csv`).
* **Torque Documentation**: [Torque OBD2 Wiki — Custom PIDs](https://torque-bhp.com/wiki/PIDs)
* **Community Open Source EV PIDs**: Many EV PID formulas have been cataloged by the EV community on GitHub (such as [JejuSoul/OBD-PIDs-for-HKMC-EVs](https://github.com/JejuSoul/OBD-PIDs-for-HKMC-EVs)).
