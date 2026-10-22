# LOCUS Trio

Three Android phones, each one a **beacon and a receiver at the same time**. No fixed anchors, no second set of devices.
Together they work out the **shape of the triangle between them**. Team V/Slash, CodeApex Track 4 PS2.

Sibling project: [locus-beacon-live](https://github.com/siddhu2606/locus-beacon-live) (fixed beacons + one receiver, with the full LOCUS particle-filter engine).

## The idea

In the first prototype, 3 to 6 phones were beacons and one more phone was the receiver. Here every phone does both jobs:

```
        A hears B, A hears C        ->  A is a receiver for B and C
        B hears A, B hears C        ->  B is a receiver for A and C
        C hears A, C hears B        ->  C is a receiver for A and B
        A, B and C all advertise    ->  all three are beacons, at the same moment
```

That gives 6 directional readings but only 3 distances (A-B, A-C, B-C), because each pair is measured from both ends.
The two readings of a pair are averaged, turned into a distance, and the three distances are laid out as a triangle.

## How three phones share everything without Wi-Fi

Each phone puts **the readings it has made** into its own Bluetooth advertisement (7 bytes):

| byte | meaning |
|---|---|
| 0-1 | `'L'` `'T'` (marks a Trio packet; the old beacon app uses `'L'` `'C'` so the two never mix) |
| 2 | sender: 1 = A, 2 = B, 3 = C |
| 3-5 | the sender's latest reading of A, B and C in dBm (0 = unknown) |
| 6 | counter |

Phone A hears B's packet, so A learns "B hears A at -63 dBm" from the packet's content and "A hears B at -61 dBm" from the packet's signal strength. After a few seconds every phone holds all six readings and draws the same triangle.
The advertisement is updated about every 0.7 s using Android's updatable advertising set, with a restart-mode fallback for phones that refuse it.

## Using it

1. Install the APK on three phones (see Releases). Allow **Nearby devices** and **Location**.
2. Turn **Bluetooth** and **Location** on. Open the app and give each phone a different letter, A, B and C.
3. Press **Start** on all three. Keep the screens on. Place the phones a few metres apart at the same height.
4. Each phone shows the three links (both directions), the three distances, and the triangle. Link colour: green = both directions measured, amber = one direction.
5. **Calibrate:** measure the real distance between A and B with a tape, set it with the buttons, and press *Calibrate*. Raise *Walls / clutter* if the room has obstacles, because signals fade faster there.
6. **Laptop view:** join the same Wi-Fi (or a phone's hotspot) and open one of the addresses shown in the app, for example `http://192.168.1.7:8080/`.

Location turned on after pressing Start is picked up automatically. There is no need to go back and reopen the screen.

## What this can and cannot do

- **It gives the shape of the triangle, not a map.** Three distances fix the triangle up to rotation, position and mirror image. Which way is north, and where the room is, are unknown.
- **It is rough.** Bluetooth signal strength is a crude distance measure. Our simulation (`scripts/sim_trio.js`, typical published noise values, not measured on our phones) gives a median error of about 1 m on a 3 m side, and about 2.5 to 4 m in the worst 10% of cases. A quiet room roughly halves that.
- **There is no redundancy.** With exactly three phones there are exactly three distances. If one reading is wrong, nothing can notice, so the trust scoring in the main LOCUS engine cannot work here. It needs four or more readings to compare. The app reports when the three distances cannot form a triangle and when the two directions of a link disagree.
- **Locating other objects needs a tag.** Once the three phones know their positions, they can act as three anchors for a fourth, transmit-only tag. That is the minimum for a 2D fix, and it has the same lack of redundancy.
- **UWB is the natural upgrade.** The same mesh design works with phones or modules that measure distance by radio timing. Timing is far more accurate than signal strength.
- **Not yet tested on real phones.** The maths and packet format have laptop tests; the Bluetooth behaviour (advertising and scanning at once, signal strength between real phones) still needs a three-phone trial.

## Tests

```
JAVA_HOME="<path to a JDK>" sh tests/run_tests.sh      # 19 checks on the maths, packet format and three-phone exchange
node scripts/sim_trio.js                               # accuracy simulation
```

## Build the app

```
cd android
JAVA_HOME="<path to a JDK 17>" ./gradlew assembleDebug
```

Android 8.0 (API 26) or newer, because updatable advertising sets need it. The phone must support BLE advertising (most phones since 2016 do).

## Layout

```
android/app/src/main/java/com/locus/trio/
    TrioMath.java      distance model, triangle layout, packet encode/decode, RSSI window
    TrioMesh.java      the shared picture of the three phones, JSON for the laptop page
    MainActivity.java  advertising + scanning at the same time, calibration, screens
    TriangleView.java  the triangle drawing
    TrioServer.java    tiny HTTP server for the laptop page
android/app/src/main/assets/trio.html   laptop page
tests/TrioTest.java, scripts/sim_trio.js
```
