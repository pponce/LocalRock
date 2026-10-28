# Experimental camera live view

LocalRock can act as the WebRTC viewer for an S7 MaxV (`roborock.vacuum.a27`). It uses
its existing MQTT connection and the local server's camera/TURN support. No separate
viewer application or video proxy is needed. The S7 (`a15`) has no supported remote
video entry. Additional models require separate compatibility verification.

## Requirements and use

- Remote viewing is already activated on the robot, with a known PIN or pattern.
- The robot, phone and local server can communicate, including the server's configured
  TURN endpoint. Keep TURN available even when media ultimately takes a direct path.
- iOS builds link WebRTC and inject `WebRtcNativeFactory` through `ContentView`.
  The Xcode target includes `WebRtcPeer.swift` through its synchronized source folder.

Open the vacuum's **More → Video (experimental)** screen. Enter the existing credential,
choose numeric PIN or pattern, then tap **Start live view**. Opening the map or returning
from the background never starts a stream. This screen keeps the credential in memory
only and clears it when the app becomes inactive. The Start button remains above the keyboard;
**Hide keyboard** dismisses the numeric pad. Pattern gestures collect dots crossed between
pointer events, including the release position. An incomplete new gesture clears the old entry.

LocalRock checks the PIN and camera availability before starting. An invalid or unknown
PIN reply fails closed. A rejected credential and an unrecognized robot response have distinct errors. If the camera belongs to another viewer, close that viewer and
retry. There is no takeover control, PIN setup/reset or camera activation control here.
Driving and microphone transmission are outside this feature.

## Connection status

The screen identifies the current step: PIN validation, camera availability, preview
start, TURN configuration, offer/answer exchange, connection, and waiting for video.
**LIVE** requires decoded video frames, not just a successful WebRTC handshake.
Connection setup has a deadline; failures offer an explicit retry.

The live label samples the selected candidate pair on the inbound video RTP transport:

| Label | Evidence |
| --- | --- |
| Direct | Both selected candidate types are known and neither is `relay`. |
| Relay | At least one candidate in the selected pair has type `relay`. |
| Connection path not yet verified | Stats do not identify enough of the selected pair. |

Candidate types (`host`, `srflx`, `prflx`, `relay`) and transport (`udp`, `tcp`) are
allowlisted. A gathered candidate, successful TURN lookup or nominated candidate is
not sufficient evidence. Direct means no TURN relay in the selected pair; it does not
necessarily mean both endpoints are on the same subnet. The label can change if the
selected path changes. No IP addresses, ports, SDP, ICE strings or TURN credentials
are included in these diagnostics.

## Stopping and retrying

Stop live view, leave the screen or background the app to close the peer. Each attempt
has a unique preview client id. Cleanup reads the robot's owner and sends a stop only
for that attempt's id; it never stops an id belonging to another client.

A start already in flight is awaited for a bounded interval before cleanup. Retry is
blocked while cleanup runs. If the robot is unreachable or its release cannot be
confirmed, the screen reports that explicitly. Wait for the robot's preview timeout
before retrying; LocalRock does not force release an unknown owner. An app termination
or loss of network can prevent remote cleanup even though the local connection closes.

## Device verification

Record the app build, model/firmware and running server version without credentials.
Keep the existing server, pairing, activation and TURN settings unchanged during testing.

1. Open the map and verify that video does not start. The S7 must have no video entry.
2. On S7 MaxV, try an incorrect PIN once: viewing must fail without changing the PIN.
3. Start with the correct PIN. Confirm moving video and record the live connection label.
4. Stop, reconnect, leave the screen, reconnect, then background the app. Each explicit
   reconnection should work, and returning to the app should require another Start action.
5. With the OEM viewer already streaming, try LocalRock: it should report camera-in-use,
   while the OEM video continues. Close the OEM viewer, then retry LocalRock.
6. After leaving LocalRock video, confirm the OEM viewer can connect again.
7. Cancel during setup and retry. Confirm it waits for cleanup and creates one fresh session.

Share the exact sanitized step/error and connection label if a test fails. Do not share
PINs, hashes, tokens, TURN secrets, raw MQTT, SDP or ICE. Authorization behavior varies
by model and firmware; a refusal does not by itself establish a need to re-pair.
