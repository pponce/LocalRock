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
choose numeric PIN or pattern, then choose one of the four video test tiles. Opening the map or returning
from the background never starts a stream. This screen keeps the credential in memory
only and clears it when the app becomes inactive. The test tiles scroll on smaller screens;
**Hide keyboard** dismisses the numeric pad. Pattern gestures collect dots crossed between
pointer events, including the release position. An incomplete new gesture clears the old entry.

LocalRock checks the PIN and camera availability before starting. The S7 MaxV's
single-value `["ok"]` PIN acknowledgement is supported. An invalid or unknown
PIN reply fails closed, and an RPC error takes precedence over any success value.
A rejected credential and an unrecognized robot response have distinct errors. If the camera belongs to another viewer, close that viewer and
retry. There is no takeover control, PIN setup/reset or camera activation control here.
Driving and microphone transmission are outside this feature.

## Temporary video comparison tiles

This test build offers four explicit choices under **More → Video (experimental)**.
The running screen and retry button retain the chosen letter. Stop and wait for camera
release before choosing another tile; a failure offers **Choose another video test**.
The PIN stays in memory while this screen remains in the foreground, so it need not
be re-entered between tests. There is no automatic fallback or simultaneous viewing.

| Tile | Difference from A |
| --- | --- |
| A · OEM match | Closest supported match to the captured OEM signaling. |
| B · Escaped JSON | Uses standard escaped JSON line breaks in the offer envelope. |
| C · Compact offer | Keeps Opus audio and baseline H.264 video with RTX. |
| D · Answer first | Requests robot ICE only after applying its SDP answer. |

All four use SD preview, audio before video, send/receive capability declarations,
a named audio stream, and a fresh eight-character hexadecimal preview client id.
No variant creates a microphone/camera capture source or local capture track.

A selects the native codecs present in the OEM offer: Opus, audio RED and 48 kHz
telephone-event; H.264 constrained high/baseline with packetization mode 1, VP8,
VP9 profile 0, AV1 profile 0, video RED, ULPFEC and associated RTX. The native engine
assigns payload numbers and supported H.264 levels; certificates, ICE credentials,
SSRCs and stream identifiers belong to the new session. This is a compatibility
match, not a replay of another client's session.

The offer envelope places `sdp` before `type`. A, C and D use the literal CRLF
string encoding observed in the OEM capture; B uses ordinary escaped JSON. Answers
in either form are accepted without changing their SDP. A, B and C poll robot ICE
while waiting for the answer and buffer candidates until the native peer has applied
it. D tests answer-first polling. PIN checks, TURN, ownership, deadlines and cleanup
apply to every tile.

These are new compatibility experiments. Test A–D with diagnostic capture already
running, waiting for the capture's next heading before starting each tile. Report
moving frames or the exact failure stage. Remove the extra choices after hardware
verification identifies the working method.

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

