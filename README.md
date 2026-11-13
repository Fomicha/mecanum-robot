# Mecanum Robot

A four-wheel Arduino Uno robot with mecanum wheels, BLE control from a custom Android application, and an ultrasonic obstacle sensor.

## Repository structure

```text
mecanum-robot/
├── README.md
├── arduino/
│   └── mecanum_robot/
│       └── mecanum_robot.ino
├── android/
│   ├── app/
│   ├── gradle/
│   ├── IMPROVEMENT_PLAN.md
│   └── ...
└── makerworld/
    └── MAKERWORLD_MODEL_LISTING.md
```

- `arduino/mecanum_robot` contains the Arduino Uno firmware.
- `android` contains the Mecanum Controller Android application.
- `android/IMPROVEMENT_PLAN.md` contains planned application improvements.
- `makerworld/MAKERWORLD_MODEL_LISTING.md` contains the short MakerWorld description for the 3D-printed chassis.

## Hardware

Main components:

- Arduino Uno.
- Motor Shield V2 based on a PCA9685 and two TB6612 drivers, compatible with the Adafruit Motor Shield V2.
- Four yellow TT DC gear motors.
- Four 50 mm mecanum wheels.
- HC-06-compatible Bluetooth module. This particular module operates as a BLE UART using FFE1/FFE2 characteristics rather than standard Classic Bluetooth SPP.
- HC-SR04 ultrasonic distance sensor.
- Separate logic and motor power supplies.
- Arduino Prototype Shield for the BLE module, sensor wiring, and supporting components.

## Motor layout

Top view, with the front of the robot at the top:

```text
                 FRONT

       FL (M1)             FR (M2)
          \                   /
           \                 /

           /                 \
          /                   \
       BL (M4)             BR (M3)

                  BACK
```

Working motor connections:

| Shield output | Position | Wire order in the current build |
|---|---|---|
| M1 | Front Left (FL) | black / red |
| M2 | Front Right (FR) | red / black |
| M3 | Back Right (BR) | black / red |
| M4 | Back Left (BL) | red / black |

The wheel rollers must form a mirrored X pattern. If forward movement and rotation work but strafe causes the robot to rotate or remain in place, check the wheel positions first.

## HC-06-compatible BLE module

The firmware uses `SoftwareSerial bluetooth(10, 11)`. Arduino D10 is RX and D11 is TX.

| BLE module | Arduino Uno | Notes |
|---|---|---|
| VCC | 5V | The module board is marked for 3.6-6 V power |
| GND | GND | Common ground |
| TXD | D10 | The Arduino can safely read the module's 3.3 V signal |
| RXD | D11 through a voltage divider | Do not apply the Arduino's 5 V TX signal directly |

Current voltage divider:

```text
Arduino D11 ---- [1 kOhm] ----+---- BLE RXD
                              |
                         [2.2 kOhm]
                              |
Arduino GND ------------------+
```

This produces approximately 3.44 V at RXD. The upper resistor between D11 and RXD is 1 kOhm; the lower resistor between RXD and GND is 2.2 kOhm.

UART speed: `9600 baud`.

## HC-SR04 connection

| HC-SR04 | Arduino Uno |
|---|---|
| VCC | 5V |
| GND | GND |
| TRIG | D6 |
| ECHO | D7 |

The sensor should point straight ahead. A downward angle can produce false reflections from the floor, while an excessive upward angle can miss low obstacles.

## Power

Current arrangement:

- Arduino Uno, BLE, and sensors are powered by two AA cells through an MT3608 boost converter adjusted to 5.0 V.
- The MT3608 output is connected to the 5V and GND rails on the Prototype Shield.
- The Motor Shield is powered separately by four 1.5 V AA cells.
- The Motor Shield VIN/PWR jumper is removed.
- Motor power must never be connected to the Arduino 5V rail.
- Do not install the VIN/PWR jumper while the Arduino and motors use separate power supplies.

The stacked Motor Shield shares logic ground with the Arduino through its headers. Motor wires connect only to M1-M4; the center GND terminals in the motor output blocks are not used for the motors.

Before connecting the Arduino, verify the MT3608 polarity and adjust its output to approximately 5.0 V with a multimeter. Do not power the board from USB at the same time as the MT3608. The four-cell motor pack can sag when all motors start, so the battery holder, switch, and wiring must be rated for the required current.

## Arduino firmware

Dependencies:

- `Adafruit Motor Shield V2 Library`
- `RoboLink`
- Standard Arduino `Wire` and `SoftwareSerial` libraries

Upload procedure:

1. Open `arduino/mecanum_robot/mecanum_robot.ino` in Arduino IDE.
2. Select `Arduino Uno` as the board.
3. Install the required libraries.
4. Select the Arduino USB port.
5. Close Serial Monitor before uploading.
6. Click **Upload**.

Serial Monitor speed: `115200 baud`.

The HC-SR04 measurements pass through a rolling median of the seven most recent samples. Obstacle blocking starts only after at least five valid readings have been collected, reducing short spikes caused by the floor, chassis edges, and parasitic reflections. `sonar_raw_cm` is the raw echo measurement, while `filtered_cm` is the value used by auto-stop and sent to the Android application.

The current calibration accounts for a floor reflection around `44-50 cm` caused by the sensor's slight downward angle. Values below `8 cm` are rejected as false near echoes, and short sequences of `-1` readings do not immediately clear the median window.

The firmware:

- receives control commands through RoboLink over BLE UART;
- calculates the four mecanum motor speeds;
- stops all motors if commands are missing for more than 700 ms;
- polls the HC-SR04 approximately every 60 ms;
- blocks forward movement after three consecutive filtered readings below 32 cm;
- clears the block after three consecutive filtered readings above 40 cm;
- sends `distance` and `blocked` telemetry to the application approximately twice per second;
- allows ultrasonic auto-stop to be enabled or disabled from the application.

> [!WARNING]
> The ultrasonic auto-stop is still experimental and is not a safety system. Keep direct access to motor power while testing it.

## Control protocol

The Android application sends a newline-terminated text command:

```text
throttle:100,steer:0,rot_left:0,rot_right:0,speed:160,sonar:1
```

Fields:

| Field | Meaning |
|---|---|
| `throttle` | `-100` reverse, `0` stop, `100` forward |
| `steer` | `-100` strafe right, `0` straight, `100` strafe left for the current motor wiring |
| `rot_left` | `1` to rotate left, otherwise `0` |
| `rot_right` | `1` to rotate right, otherwise `0` |
| `speed` | PWM limit, currently approximately `90..230` |
| `sonar` | `1` enables ultrasonic auto-stop, `0` disables it |

Movement buttons use press-and-hold control: the robot moves while a button is held and receives a stop command after release. Multi-touch allows the user to hold forward, briefly press rotate, and continue moving forward after releasing rotate.

## Android application

Current version: `1.5.2 (32)`.

Main features:

- BLE scan and connection to the HC-06-compatible FFE1 module.
- Forced landscape orientation.
- Menu opens only from the menu button; edge-swipe opening is disabled.
- Central ultrasonic display showing distance and `CLEAR / BLOCKED` state.
- `AUTO STOP ON/OFF` control while distance telemetry remains visible.
- Thumb-oriented landscape controller.
- Forward, reverse, strafe left/right, and rotate left/right controls.
- Slow, Normal, and Turbo speed modes.
- Stop-on-release and command timeout fail-safe.
- Multi-touch command handling.
- Haptic feedback for controls, speed selection, and the menu button.
- Separate Devices and Logs screens.
- BLE connection survives Android configuration changes.

Requirements:

- Android Studio with the Android SDK.
- JDK 11 or the compatible JDK bundled with Android Studio.
- Android 7.0 or newer (`minSdk 24`).
- Bluetooth and Nearby Devices/Location permissions as required by the Android version.

Build the debug APK:

```bash
cd android
./gradlew :app:assembleDebug
```

The APK is created at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Open the `android` directory itself in Android Studio. `local.properties` is intentionally excluded from version control; Android Studio creates it with the local SDK path.

## Current status

Working:

- BLE connection and control.
- Forward, reverse, strafe, and rotation.
- Press-and-hold movement and multi-touch.
- Slow, Normal, and Turbo modes.
- Rotation using the currently selected speed mode.
- HC-SR04 distance and block-state telemetry in the application.
- Runtime `AUTO STOP ON/OFF` control.
- Corrected Left/Right controls for the current motor and wheel arrangement.
- Forced landscape mode without losing the BLE connection.

Planned improvements:

- Final mechanical positioning and calibration of the HC-SR04.
- BLE auto-reconnect.
- Individual motor speed calibration.
- Automated tests and a signed Android release build.

## Safe first test

For the first powered test, lift the chassis so the wheels cannot touch the floor. Verify every motor direction before placing the robot down. Keep immediate access to the motor power switch during all ultrasonic auto-stop tests.
