# Camera live view

LocalRock displays the S7 MaxV camera (`roborock.vacuum.a27`) through its existing
MQTT connection and the local server's camera and TURN support. Other models do
not have an enabled video entry.

## Setup

Activate remote viewing in the official Roborock app while connected to Roborock's
servers, before switching to the local server. Keep your existing camera PIN or
pattern. The robot, phone and server must be able to reach the configured TURN endpoint.

Open **More → Video (experimental)** and enter that PIN or pattern. **Start video**
uses the OEM matching connection and requests HD by default. While docked, choose a
destination on the map and tap **Go**. Video connects once the robot leaves the dock.

The credential remains in memory only while the screen is in the foreground.
Leaving or backgrounding the app closes the viewer and clears the credential.
Opening the map or returning to the app does not start video automatically.

## Video and cleaning controls

- **Pause** and **Resume** control an existing cleaning task while off the dock.
  Room and zone tasks retain their scope. The viewer does not offer Start cleaning;
  use **Map → Go** to move an idle or docked robot.
- **Map → Go** sends an idle robot to the selected floor location.
- **Controls** opens the directional pad. Hold a direction to move; release to stop.
- **Stop movement** stops manual driving, navigation started here, or a return to dock.
- **Home** offers to end video and return the robot to its dock.
- **Expand** fills the screen. Turn the phone horizontally for the inset map and
  overlaid controls. Tap the inset map to expand it for Pin-and-Go, then close it to
  return to video. Controls fade after five seconds without interaction; tap video
  to show them again. Robot status, battery, the map, call icon and **Exit** remain visible.
  The call icon stays beside the right-side controls, clear of the expanded map's **Go** button.
  **Exit** leaves fullscreen without ending the video session in either orientation.
  Landscape video fills the screen by default. Choose **Fit** in video settings to
  show the whole camera frame with black margins, or **Fill** to cover the display
  while trimming edges. Portrait video always fits the whole frame.
- **End video** closes the viewer. A cleaning task already running when you opened
  video continues. If you started movement here, the exit prompt also offers to
  return to dock; ending video alone stops viewer-owned movement.

Manual driving requires a connected viewer and an idle robot. Go works before video
connects, including while docked. Leaving video stops a held drive and Go navigation
started here; a requested return to dock continues.

Tap **HD/SD** to change the requested quality. Video settings also show the received
frame dimensions when available, updating while the settings dialog is open.
The quality label confirms a request; received dimensions describe the actual stream.
If HD cannot connect, choose **Retry in SD**.
## Voice calls on iPhone

Audio is off when video starts. Tap the **green microphone icon** after video connects to hear the
robot and speak through its speaker. Allow microphone access if iOS asks. The call
uses the existing video connection; audio is not saved. The icon becomes a red
hang-up button during a call; tap it to stop audio while keeping video open.
The same Start/End call actions are also available as labeled buttons in video settings.

Video settings have independent **Microphone** and **Robot audio** switches. Use
your phone's volume buttons for incoming sound. The **Robot speaker volume** presets
change the volume heard at the robot only when selected; starting a call does not
change that volume. Exiting or backgrounding video ends the call. An interruption
mutes the microphone; unmute it explicitly to resume speaking.

Voice calls are experimental and require device validation. They are currently
available in the iPhone build; Android call controls remain unavailable.

**Activate Remote Viewing** and **Deactivate Remote Viewing** remain disabled and
send no commands. Stopping a viewer does not deactivate remote viewing. LocalRock
does not reset the PIN or take over another viewer.

## Connection and retry

The screen shows the connection stage and requires decoded frames before reporting
live video.

If another viewer owns the camera, close that viewer and retry. LocalRock releases
only its own preview session. Wait for cleanup to finish before retrying. If release
cannot be confirmed, wait for the robot's preview timeout.

The **Connection tests (SD)** section keeps these diagnostic choices available:

| Choice | Behavior |
| --- | --- |
| A · OEM match | Default signaling approach. |
| B · Escaped JSON | Standard escaped JSON line breaks in the offer envelope. |
| C · Compact offer | Opus audio and baseline H.264 video with RTX. |
| D · Answer first | Requests robot ICE after applying its SDP answer. |

All choices request SD and create no phone microphone or camera capture source.
Stop the current viewer before selecting another choice.

