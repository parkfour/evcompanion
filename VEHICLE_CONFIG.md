# Vehicle Profiles (`vehicle.json`)

EV Companion uses three configuration files, each with one job:

| File | What it describes | How-to |
|---|---|---|
| **PID preset** (`*.csv`) | *How to read* each value from the car: ECU, command, byte formula, unit | [PID_CONFIG.md](PID_CONFIG.md) |
| **Dashboard layout** (`*.json`) | *What to show* on the Android Auto screen | [DASHBOARD_CONFIG.md](DASHBOARD_CONFIG.md) |
| **Vehicle profile** (`*.json`) | *How this particular car behaves*: which value means what, how fast each module may be polled, adapter quirks, charging detection | this page |

A vehicle profile is what makes the app's own features (efficiency, trip distance, automatic switch to
the Charging page, the diagnostics scan) work on a car other than the one a preset was written for.

Import one from the phone app: **Preset tab → Vehicle profile → Import vehicle profile JSON**. It
applies to the **currently selected preset**, and stays until you tap **Reset to default**.

---

## Which profile is used

For the selected preset, the first of these that exists wins:

1. A profile you imported for that preset.
2. The profile bundled with the app for that preset (e.g. `vehicles/ioniq5_awd_77kwh.json`).
3. The bundled generic profile (`vehicles/default.json`): standard OBD-II only, default polling
   speeds, no automatic charging detection.

Every field is optional. Anything you leave out keeps its generic default, so a profile only needs
to state what is different about your car.

---

## Safe & non-destructive

* **Lenient parsing**: a bad entry (for example an ECU without a `header`) is skipped and shown as
  a warning under the import button; the rest of the file still applies.
* **Invalid JSON is rejected** and not saved; the previous profile stays in effect.
* **One-tap reset** back to the bundled profile.

---

## File structure

```json
{
  "name": "Hyundai IONIQ 5 (E-GMP)",
  "roles": { ... },
  "packCurrentPositiveIsDischarge": true,
  "charging": { ... },
  "ecus": [ ... ]
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `name` | String | `"Generic"` | Shown in the phone app so you can tell which profile is active. |
| `roles` | Object | see below | Which PID plays which part in the app's calculations. |
| `packCurrentPositiveIsDischarge` | Boolean | `true` | `true` if your BMS reports current as **positive while driving** and negative while charging. Set `false` if it's the other way round. |
| `charging` | Object | see below | Thresholds for the automatic switch to the Charging page. |
| `ecus` | Array | `[]` | Per-module settings: display name, polling limits, adapter quirks. |
| `chargerLimits` | Object | no limits | What your car can actually draw from nearby chargers, and which networks to hide. |
| `notes` | String | | Free text for your own comments; ignored by the app. |

### Roles (`roles`)

Each role is a **list of PID names**, tried in order. A name matches a preset row whose name
*contains* it (case-insensitive), the same rule the dashboard uses, so `"Battery Current"` matches a
row called `000_Battery Current`.

| Role | Default | Used for |
|---|---|---|
| `speed` | `["Vehicle Speed"]` | Instant efficiency. km/h or mph, taken from the preset row's unit. |
| `odometer` | `["Odometer"]` | Trip distance and trip efficiency. km or mi, from the row's unit. |
| `packPower` | `["Battery Power"]` | Instant efficiency. Can be a calculated row (e.g. current × voltage). |
| `packCurrent` | `["Battery Current"]` | Charging detection. |
| `energyDischarged` | `["Cumulative Energy Discharged"]` | Trip energy and trip efficiency. |
| `energyCharged` | `["Cumulative Energy Charged"]` | Trip energy (regen) and trip efficiency. |
| `motorSpeeds` | `[]` | **One entry per drive motor.** Charging detection only counts as "charging" when every listed motor is stopped, so regenerative braking never triggers it. Leave empty to turn charging detection off. |

### Charging detection (`charging`)

The dashboard jumps to the Charging page (the page holding the `charging_power` tile) once per
charging session, when current has been flowing **into** the pack with every motor stopped for
`holdSeconds`.

| Field | Default | Description |
|---|---|---|
| `minChargeCurrentAmps` | `1.5` | Current into the pack that counts as charging. Low enough for a 120 V charger; high enough to ignore the pack's small idle readings. |
| `maxMotorRpm` | `50` | A motor below this counts as stopped. |
| `holdSeconds` | `30` | How long charging must last before switching. |

### Charger limits (`chargerLimits`)

The nearby-charger list shows the power **your car will actually get**, not the charger's
nameplate: a 250 kW Tesla Supercharger gives an IONIQ 5 about 96 kW. The low-battery fast-charger
filter uses the same figure, so a charger only counts as "fast" if it is fast *for your car*.

| Field | Type | Description |
|---|---|---|
| `maxDcKw` | Number | Your car's peak DC fast-charge rate. Faster chargers are shown at this rate. |
| `maxAcKw` | Number | Your car's onboard AC charger rate. Applied to chargers rated 22 kW or less (AC in practice). |
| `networks` | Array | Rules per charging network, see below. |

Each `networks` rule matches a charger whose **operator or name contains** `match` (case-insensitive):

| Field | Type | Description |
|---|---|---|
| `match` *(required)* | String | Text to look for, e.g. `"Tesla"`, `"Electrify America"`. |
| `maxKw` | Number | The most your car gets on this network. If several rules match, the first one with a `maxKw` wins. |
| `exclude` | Boolean | `true` hides matching chargers entirely, for sites your car can't use. Any matching `exclude` rule wins. |

Charger data comes from [Open Charge Map](https://openchargemap.org). Its operator names are what
you match against, e.g. Superchargers open to all cars are `Tesla (Including non-tesla)` and closed
ones `Tesla (Tesla-only charging)`. E-GMP cars (IONIQ 5/6, EV6, EV9, GV60) generally top out around
125 kW on Superchargers, since those run at 400 V.

### ECUs (`ecus[]`)

| Field | Type | Description |
|---|---|---|
| `header` *(required)* | String | The ECU's request address, as used in the preset (e.g. `"7E4"`). |
| `name` | String | Shown in the Diagnostics scan instead of `ECU 7E4`. |
| `minIntervalMs` | Number | Minimum time between requests to this ECU. **Slow body modules can drop off the bus, or upset the car's own instrument cluster, if polled too fast**, so give them a generous value. ECUs not listed use 500 ms. |
| `pidIntervalsMs` | Object | Per-command overrides of `minIntervalMs`, keyed by command without `0x`, e.g. `{ "220105": 5000 }` for a slowly changing value on an otherwise fast ECU. |
| `receiveFilter` | String | For an ECU that **doesn't reply on its header + 8** (e.g. requests to `770` answered from `778`): the CAN ID to listen on. Sent to the adapter as `ATCRA`. If a module's PIDs all come back as `NO DATA` while others work, this is a likely cause. |

---

## Finding the right values for your car

* **Current sign**: watch the pack current in **Live data** while driving. Positive while accelerating
  means `packCurrentPositiveIsDischarge: true`.
* **Motor speeds**: list every motor PID your preset has (one for a single-motor car, two for dual-motor).
  Check they read about 0 when parked.
* **Polling limits**: start slow (2000 ms or more) for body, climate, tyre-pressure and cluster modules,
  and only speed up a module if its values look laggy. Battery and motor modules usually cope with 100 ms.
* **Turn on debugging** (phone app → *Enable debugging features*) and share a drive log if something
  doesn't behave; it shows every request and reply.

---

## Complete example (IONIQ 5)

This is the profile bundled with the app for the IONIQ 5 preset; copy it as a starting point.

```json
{
  "name": "Hyundai IONIQ 5 (E-GMP)",
  "roles": {
    "speed": ["Real Vehicle Speed", "Vehicle Speed"],
    "odometer": ["002_Odometer", "Odometer"],
    "packPower": ["Battery Power"],
    "packCurrent": ["Battery Current"],
    "energyDischarged": ["Cumulative Energy Discharged"],
    "energyCharged": ["Cumulative Energy Charged"],
    "motorSpeeds": ["Drive Motor Speed Front", "Drive Motor Speed Rear"]
  },
  "packCurrentPositiveIsDischarge": true,
  "charging": { "minChargeCurrentAmps": 1.5, "maxMotorRpm": 50, "holdSeconds": 30 },
  "chargerLimits": {
    "maxDcKw": 235,
    "maxAcKw": 10.9,
    "networks": [
      { "match": "Tesla-only", "exclude": true },
      { "match": "Tesla", "maxKw": 96 }
    ]
  },
  "ecus": [
    { "header": "7E4", "name": "BMS (Battery)", "minIntervalMs": 100,
      "pidIntervalsMs": { "220105": 5000, "220102": 3000, "220103": 3000, "220104": 3000, "22010A": 3000, "22010B": 3000, "22010C": 3000 } },
    { "header": "7E2", "name": "MCU (Motor/Inverter)", "minIntervalMs": 100 },
    { "header": "7E5", "name": "VCU (Vehicle Control)" },
    { "header": "7D1", "name": "ICCU (Charger/LDC)" },
    { "header": "7A4", "name": "IEB (Brakes)" },
    { "header": "7B3", "name": "FATC (Climate)", "minIntervalMs": 8000 },
    { "header": "770", "name": "BCM (Body Control)", "minIntervalMs": 1500, "receiveFilter": "778" },
    { "header": "7C6", "name": "IBU (Integrated Body)", "minIntervalMs": 1500 },
    { "header": "7A0", "name": "TPMS (Tires)", "minIntervalMs": 25000 },
    { "header": "730", "name": "CLU (Cluster)", "minIntervalMs": 30000 }
  ]
}
```

A minimal profile for a single-motor car whose BMS reports charging as positive current:

```json
{
  "name": "My EV",
  "packCurrentPositiveIsDischarge": false,
  "roles": { "motorSpeeds": ["Motor RPM"] }
}
```
