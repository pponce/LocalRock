# Camera live view

LocalRock displays the S7 MaxV camera (`roborock.vacuum.a27`) through its existing
MQTT connection and the local server's camera and TURN support. Other models do
not have an enabled video entry.

## Setup

Activate remote viewing in the official Roborock app while connected to Roborock's
servers, before switching to the local server. Keep your existing camera PIN or
pattern. The robot, phone and server must be able to reach the configured TURN endpoint.

Open **More → Video (experimental)** and enter that PIN or pattern. Choose **A · OEM
match** to start. Video is unavailable while the robot is docked: **Start cleaning**
moves it off the dock by starting a cleaning task.

The credential remains in memory only while the screen is in the foreground.
Leaving or backgrounding the app closes the viewer and clears the credential.
Opening the map or returning to the app does not start video automatically.

## Video and cleaning controls

- **Play** starts cleaning, or resumes the current paused task. Room and zone tasks
  retain their scope. During cleaning it becomes **Pause**.
- **Map → Go** sends an idle robot to the selected floor location.
- **Controls** opens the directional pad. Hold a direction to move; release to stop.
- **Stop movement** stops manual driving, navigation started here, or a return to dock.
- **Home** returns an idle robot to its dock.
- **Expand** fills the screen. Turn the phone horizontally for the inset map and
  overlaid controls. Collapse returns to the portrait layout.
- **Stop video** closes the viewer. An independently running cleaning task continues.

Manual driving and Go require a connected viewer and an idle robot. Leaving video
stops a held drive and Go navigation started here; a requested return to dock continues.

Video quality is SD. Quality switching, microphone calls and call volume are
unavailable. **Activate Remote Viewing** and **Deactivate Remote Viewing** are
visible but disabled and send no commands. Stopping a viewer does not deactivate
remote viewing. LocalRock does not reset the PIN or take over another viewer.

## Connection and retry

The screen shows the connection stage and requires decoded frames before reporting
live video. **Direct** means neither selected media candidate is a TURN relay;
**Relay** means at least one is. A connection can change between these paths.

If another viewer owns the camera, close that viewer and retry. LocalRock releases
only its own preview session. Wait for cleanup to finish before retrying. If release
cannot be confirmed, wait for the robot's preview timeout.

The current experimental interface also exposes these signaling choices:

| Choice | Behavior |
| --- | --- |
| A · OEM match | Default signaling approach. |
| B · Escaped JSON | Standard escaped JSON line breaks in the offer envelope. |
| C · Compact offer | Opus audio and baseline H.264 video with RTX. |
| D · Answer first | Requests robot ICE after applying its SDP answer. |

All choices request SD and create no phone microphone or camera capture source.
Stop the current viewer before selecting another choice.
