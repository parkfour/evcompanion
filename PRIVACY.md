# EV Companion Privacy Policy

Effective date: November 2, 2026

The published copy of this policy is at https://parkfour.com/apps/evcompanion/privacy.html.

EV Companion ("the app") is developed by Park Four, LLC ("we", "us"). This policy explains
what information the app handles, where it goes, and the choices you have.

**The short version:** the app has no accounts, no ads, no analytics and no server of its own.
Your vehicle data stays on your phone. The only data that leaves your phone is (1) your
approximate location, sent to a public map service to find nearby EV chargers, and (2) a
feedback or debug log, and only when you choose to send one.

## Information the app handles

### Vehicle data (stays on your device)
When you connect the app to an OBD-II Bluetooth adapter in your car, it reads vehicle
information such as battery state of charge, voltage, current, power, temperatures, charging
status, speed and odometer. This data is shown on your phone and on your car's display through
Android Auto. It is processed on your device and is not sent to us or to anyone else unless
you choose to share a debug log (see below). The app does not read your vehicle
identification number (VIN).

### Location
With your permission, the app uses your device's location to show EV charging stations near
you on the Android Auto screen. To do this, it sends your location (latitude and longitude)
to the public **Overpass API** (overpass-api.de), a service that answers questions about
**OpenStreetMap** map data, asking for charging stations within about 35 km. That service sees
the location in the query and your device's IP address, as with any internet request. We do
not receive or store your location. Overpass API is operated independently of us; see
https://wiki.openstreetmap.org/wiki/Overpass_API for information about the service.

If you tap a charger to navigate to it, the app passes that charger's location to the
navigation app you use (for example Google Maps or Waze), which handles it under its own
privacy policy.

You can deny or revoke location permission at any time in Android Settings. The rest of the
app keeps working without it; only the nearby-charger list will be unavailable.

### Bluetooth
The app uses Bluetooth only to find and connect to your OBD-II adapter. It lists nearby and
paired Bluetooth devices so that you can pick your adapter; it does not store or transmit
that list.

### Debug logs and feedback (only if you choose to send them)
If you turn on debug logging, the app saves logs of the data exchanged with your adapter in
the app's private storage on your phone. It keeps only the most recent few sessions. These
logs may contain vehicle data and the locations of chargers you navigated to.

When you tap "Share debug log" or "Feedback", the app prepares a message containing the logs
and basic technical details (app version, Android version and phone model) and opens your
phone's share sheet. Nothing is sent until you pick an app (for example, your email app) and
send it yourself. Feedback emails go to apps@parkfour.com. We use them only to diagnose
problems and improve the app, and will delete them on request.

### Settings
Your app settings (chosen vehicle preset, dashboard layout, last-used adapter) are stored only
on your device.

## What we don't do
- We don't sell or rent your information.
- We don't use advertising or analytics SDKs, and we don't track you across apps or websites.
- We don't require an account or collect your name, email address or contact list.

## Data retention and deletion
Everything the app stores is on your device. You can delete it at any time by clearing the
app's storage (Android Settings > Apps > EV Companion > Storage > Clear storage) or by
uninstalling the app. To have a feedback email you sent us deleted, write to
apps@parkfour.com.

## Children
The app is not directed at children under 13, and we do not knowingly collect information
from children.

## Security
Data stored by the app is kept in its private storage, which other apps can't access.
Connections to the Overpass API use HTTPS.

## Changes to this policy
If the app's data practices change, we will update this policy and its effective date above.

## Contact
Questions about this policy: apps@parkfour.com
