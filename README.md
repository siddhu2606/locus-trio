# LOCUS Trio

A mesh of **3 to 6 Bluetooth devices, each one a beacon and a receiver at the same time**. No fixed anchors.
Together they work out the **shape of the layout between them**. Team V/Slash, CodeApex Track 4 PS2.

Any device that can advertise and scan Bluetooth Low Energy can join:

| Device | How it joins | Status |
|---|---|---|
| Android phone | `locus-trio.apk` (Android 8.0 or newer) | built, not yet field-tested |
| Windows laptop | `laptop/trio_node.py` (Python, full node: advertises and scans) | written, logic tested, Bluetooth part not yet run |
| ESP32 board | `esp32/trio_esp32/trio_esp32.ino` (Arduino IDE) | written, not yet run on hardware |
| Any browser | open the layout page served by a phone or the laptop node (view only) | built |

iPhones are not supported: Safari has no Web Bluetooth, and iOS apps cannot put custom data in an advertisement.

Sibling project: [locus-beacon-live](https://github.com/siddhu2606/locus-beacon-live) (fixed beacons + one receiver, with the full LOCUS particle-filter engine).

## The idea

In the first prototype, 3 to 6 phones were beacons and one more phone was the receiver. Here every device does both jobs:

```
        A hears B, C, D      ->  A is a receiver for B, C and D
        B hears A, C, D      ->  B is a receiver for A, C and D
        ...                      and all devices advertise at the same moment
```

With N devices there are N x (N-1) directional readings but only N x (N-1) / 2 distances, because each pair is measured from both ends.
The two readings of a pair are averaged, turned into a distance, and all the distances are laid out in 2D.
Three devices give an exact triangle. With four or more, there are spare readings, so a bad one can be spotted and trusted less.

## How the devices share everything without Wi-Fi

Each device puts **the readings it has made** into its own Bluetooth advertisement (4 + N bytes, so 7 to 10 bytes):

| byte | meaning |
|---|---|
| 0-1 | `'L'` `'T'` (marks a Trio packet; the old beacon app uses `'L'` `'C'` so the two never mix) |
| 2 | sender: 1 = A, 2 = B, ... |
| 3 to 2+N | the sender's latest reading of A, B, ... in dBm (0 = unknown) |
| 3+N | counter |

Device A hears B's packet, so A learns "B hears A at -63 dBm" from the packet's content and "A hears B at -61 dBm" from the packet's signal strength.
After a few seconds every device holds every reading and draws the same layout. The mesh size is read from the packet length, and every device must be set to the same number.

## Using it

### Phones
1. Install the APK on each phone. Allow **Nearby devices** and **Location**. Turn **Bluetooth** and **Location** on.
2. Pick the number of devices in the mesh and give each device a different letter. Press **Start**. Keep the screens on.
3. Place the devices a few metres apart at the same height.
4. Each phone shows every link (both directions), the distances, and the layout. Link colour: green = both directions measured, amber = one direction, red dashed = disagrees with the others.
5. **Calibrate:** measure the real distance between A and B with a tape, set it with the buttons, and press *Calibrate*. Raise *Walls / clutter* if the room has obstacles.

### Laptop (Windows)
```
pip install -r laptop/requirements.txt
python laptop/trio_node.py --scan-test          # checks Bluetooth, takes 6 seconds
python laptop/trio_node.py --id D --n 4         # join a 4-device mesh as device D
```
Open `http://localhost:8080/`. `http://localhost:8080/api/calibrate?d=2.0` calibrates with a real A-B distance of 2 m. On macOS or Linux the node receives and draws, but cannot advertise.

### ESP32
Open `esp32/trio_esp32/trio_esp32.ino` in the Arduino IDE with the board package **esp32 by Espressif 2.0.17**. Set `NODE` and `MESH_SIZE`, upload. The serial monitor (115200) prints the readings. The board measures and shares; phones and the laptop draw the layout.

### Watching from another screen
Every phone and the laptop node serve the layout page. Join the same Wi-Fi (or a phone's hotspot) and open the address shown in the app, for example `http://192.168.1.7:8080/`.

## Does adding devices help?

`tests/SimTrio.java` runs the app's own layout code on simulated rooms (8 x 6 m, random placement, typical published noise: 3 dB shadowing per link, 1.5 dB per-device offsets, 3 dB fast fading, median of 30 samples; **not measured on our hardware**).
Error is the mean error of the pair distances in the final layout, in metres, median / 90th percentile. The A-B pair is left out because it is used for calibration.

| Devices | Clean room | One link blocked (8 dB extra loss) |
|---|---|---|
| 3 | 1.13 / 3.35 | 2.24 / 6.66 |
| 4 | 1.29 / 3.15 | 1.85 / 4.77 |
| 5 | 1.23 / 2.77 | 1.55 / 3.62 |
| 6 | 1.24 / 2.78 | 1.43 / 3.20 |

- **In a clean room, more devices do not make the typical error smaller.** It stays around 1.2 m. The error comes from how signals fade along each path, and extra devices do not remove that.
- **Extra devices protect against a bad link.** With one blocked link the worst-case error roughly halves going from 3 to 6 devices.

## What this can and cannot do

- **It gives the shape of the layout, not a map.** Rotation, position and mirror image are unknown. Which way is north, and where the room is, can't be found.
- **It is rough.** Bluetooth signal strength is a crude distance measure, so expect about 1 m typical error and 3 m in the worst 10% of cases.
- **Three devices have no redundancy.** Exactly three distances, so nothing can notice a wrong one. Use 4 or more when you can.
- **Mixed devices add error.** Phones, laptops and ESP32 boards report signal strength differently. The simulation includes some of this, and calibration helps.
- **Locating other objects needs a tag.** The mesh positions itself; a transmit-only tag could then be located using the mesh as anchors.
- **UWB is the natural upgrade.** The same mesh design works with devices that measure distance by radio timing, which is far more accurate.
- **Not tested on real hardware.** The maths, packet format and the laptop's web server have tests. Bluetooth behaviour on phones, the laptop's Bluetooth, and the ESP32 sketch still need a field trial.

## Tests

```
JAVA_HOME="<path to a JDK>" sh tests/run_tests.sh      # 23 checks on the maths, packets and an exchange between 5 devices
python tests/test_core.py                               # 9 checks: the Python node gives the same bytes and layout as the Java app
```
`tests/vectors.txt` holds the Java reference output the Python test compares against (`tests/DumpVectors.java` regenerates it).

## Build the app

```
cd android
JAVA_HOME="<path to a JDK 17>" ./gradlew assembleDebug
```
Android 8.0 (API 26) or newer, because updatable advertising sets need it. The phone must support BLE advertising (most phones since 2016 do).

## Layout

```
android/app/src/main/java/com/locus/trio/
    TrioMath.java      distance model, layout, packet encode/decode, RSSI window
    TrioMesh.java      the shared picture of the mesh, JSON for the page
    MainActivity.java  advertising + scanning at the same time, calibration, screens
    NetworkView.java   the layout drawing
    TrioServer.java    tiny HTTP server for the page
android/app/src/main/assets/trio.html   the layout page (also used by the laptop node)
laptop/trio_core.py, trio_node.py       the same logic in Python, plus Bluetooth and a web server
esp32/trio_esp32/trio_esp32.ino         ESP32 node
tests/                                  Java tests, the simulation, Python test, reference vectors
```
