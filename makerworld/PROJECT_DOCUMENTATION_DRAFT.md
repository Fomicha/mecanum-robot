# MakerWorld Listing Draft

Status: draft. Do not publish until all `[TODO]` items are resolved and the final STL/3MF files are checked.

## Recommended title

**Arduino Mecanum Robot Chassis | Uno + Motor Shield V2 + TT Motors**

Alternative titles:

- **4WD Mecanum Robot Chassis for Arduino Uno and TT Motors**
- **Compact Arduino Mecanum Car Chassis with Dual Battery Mounts**
- **DIY BLE Mecanum Robot Platform for Arduino Uno**

## One-line summary

A printable four-wheel mecanum robot chassis designed around an Arduino Uno, Motor Shield V2, four yellow TT gear motors, separate logic and motor power, BLE control, and a front HC-SR04 sensor.

## Short description

This is the custom 3D-printed chassis used for my Arduino mecanum robot. It supports four common yellow TT gear motors and mecanum wheels, with a stacked Arduino Uno, Motor Shield V2, and prototype shield. The platform can move forward, backward, strafe left and right, and rotate in place. The build also includes BLE phone control and an optional front HC-SR04 ultrasonic sensor.

The upload is primarily the mechanical chassis. Electronics, firmware, and Android controller are separate parts of the complete project.

## Full MakerWorld description

### Overview

This model is a compact four-wheel mecanum robot chassis built around readily available Arduino components. I designed it for experimenting with omnidirectional movement, Bluetooth control, obstacle sensing, and custom robot firmware without using a commercial robot frame.

The electronics are stacked vertically to keep the footprint compact:

1. Arduino Uno at the bottom.
2. PCA9685/TB6612 Motor Shield V2 in the middle.
3. Prototype Shield at the top for the BLE module, HC-SR04 wiring, and supporting components.

Four yellow TT DC gear motors drive four mecanum wheels. With the wheels installed in the correct mirrored X pattern, the robot supports normal forward/reverse movement, sideways strafing, and rotation in place.

### Main features

- Mounting for four common yellow TT DC gear motors.
- Four-wheel mecanum layout.
- Space for an Arduino Uno and stacked shields.
- Motor Shield V2 compatible electronics layout.
- Front mounting position for an HC-SR04 ultrasonic sensor. `[VERIFY ON FINAL MODEL]`
- Separate power arrangements for Arduino logic and the motors.
- Suitable for BLE or other wireless control projects.
- Designed as an experimental robotics platform that can be modified and expanded.

### Electronics used in my build

- 1 × Arduino Uno.
- 1 × Motor Shield V2 compatible board using PCA9685 and two TB6612 drivers.
- 1 × Arduino Prototype Shield.
- 4 × yellow TT DC gear motors.
- 4 × mecanum wheels.
- 1 × HC-06-compatible BLE UART module using FFE1/FFE2 characteristics.
- 1 × HC-SR04 ultrasonic distance sensor.
- 1 × MT3608 boost converter adjusted to 5.0 V for Arduino power.
- 2 × AA battery holder for Arduino logic power.
- 4 × AA battery holder for motor power.
- Power switches, hookup wire, M3 hardware, and resistors.

Electronics, motors, wheels, screws, and batteries are not included in the model files.

### Power arrangement used in my build

The Arduino and motors use separate battery packs:

- Arduino, BLE, and sensors: 2 × AA through an MT3608 adjusted to 5.0 V, connected to the 5V and GND rails.
- Motors: separate 4 × AA pack connected to the external motor power input on the Motor Shield.
- The Motor Shield VIN/PWR jumper is removed when using separate supplies.

Do not power the motors from the Arduino 5V rail. Verify converter polarity and output voltage with a multimeter before connecting the Arduino.

### Motor positions

Viewed from above with the front of the robot pointing upward:

```text
                 FRONT

       FL / M1             FR / M2

       BL / M4             BR / M3

                  BACK
```

Motor Shield mapping used by the firmware:

| Position | Shield output |
|---|---|
| Front Left | M1 |
| Front Right | M2 |
| Back Right | M3 |
| Back Left | M4 |

### Mecanum wheel orientation

The roller directions must form a mirrored X pattern when viewed from above:

```text
                 FRONT

          FL \           / FR

          BL /           \ BR

                  BACK
```

Diagonal pairs use the same wheel orientation:

- Front Left and Back Right: `\`
- Front Right and Back Left: `/`

If forward/reverse works but sideways movement causes rotation or no translation, check the wheel positions first.

### Assembly notes

1. Print and clean all chassis parts.
2. Install the four TT motors without crushing or deforming their plastic gearbox housings.
3. Tighten the motor hardware only enough to prevent movement. Over-tightening can increase gearbox friction.
4. Install the mecanum wheels in the X pattern shown above.
5. Confirm that all four wheels touch a flat surface evenly. Mecanum movement is sensitive to uneven wheel loading.
6. Install the Arduino Uno, Motor Shield, and Prototype Shield stack.
7. Install the battery holders, switches, BLE module, and optional HC-SR04.
8. Route wires away from the wheels and motor shafts.
9. Test each motor with the chassis lifted before driving on the floor.
10. Verify forward, reverse, strafe, and rotation directions before full-speed testing.

### Surface and calibration notes

Mecanum wheels behave differently depending on the floor surface. Carpet provides more grip, while smooth wood or laminate may cause the rollers to slip, especially during rotation. Make sure:

- all four wheels carry similar weight;
- the chassis is not twisted;
- the rollers are clean and free of grease;
- the motor mounts are not over-tightened;
- motor speed is calibrated if the gear motors differ significantly.

### Optional electronics connections

HC-SR04:

| HC-SR04 | Arduino Uno |
|---|---|
| VCC | 5V |
| GND | GND |
| TRIG | D6 |
| ECHO | D7 |

BLE UART module:

| BLE module | Arduino Uno |
|---|---|
| VCC | 5V |
| GND | GND |
| TXD | D10 |
| RXD | D11 through voltage divider |

Voltage divider for the BLE RXD input:

```text
Arduino D11 ---- [1 kOhm] ----+---- BLE RXD
                              |
                           [2.2 kOhm]
                              |
Arduino GND ------------------+
```

### Software used in my build

The robot is controlled by custom Arduino firmware and a custom Android application.

Controller features include:

- BLE scanning and connection.
- Landscape thumb controls.
- Press-and-hold movement with stop-on-release.
- Multi-touch control.
- Slow, Normal, and Turbo speed modes.
- Forward, reverse, strafe, and rotate commands.
- Optional ultrasonic auto-stop toggle.
- Live HC-SR04 distance display.

Current Android controller version in the project: `1.5.2 (32)`.

`[ADD PROJECT OR SOURCE LINK BEFORE PUBLISHING, IF PUBLIC]`

### Safety

- Perform the first powered test with all wheels lifted from the table.
- Do not leave stalled motors powered.
- Do not connect motor power to the Arduino 5V rail.
- Check converter voltage and polarity before connecting electronics.
- Keep wires clear of rotating wheels and shafts.
- The ultrasonic auto-stop is experimental and must not be treated as a safety system.

## Print profile draft

These values must be replaced with the settings used for the successful physical print.

- Printer: `[TODO]`
- Material/brand: `[TODO]`
- Nozzle: `[TODO]`
- Layer height: `[TODO]`
- Wall loops: `[TODO]`
- Top/bottom layers: `[TODO]`
- Infill type and percentage: `[TODO]`
- Supports: `[TODO: none / build plate only / required regions]`
- Brim: `[TODO]`
- Print orientation: `[TODO]`
- Number of plates: `[TODO]`
- Total print time: `[TODO]`
- Total filament weight: `[TODO]`
- Largest part dimensions: `[TODO]`
- A1 mini compatible: `[TODO: verify all parts fit 180 × 180 mm]`

Suggested profile title format after verification:

**0.20 mm layer, 3 walls, [XX]% infill – tested chassis profile**

## Bill of materials to verify

| Item | Quantity | Exact specification / status |
|---|---:|---|
| Printed chassis parts | `[TODO]` | List every uploaded STL/STEP file |
| Yellow TT gear motor | 4 | Verify voltage and gear ratio |
| Mecanum wheel | 4 | Add diameter and axle type |
| Arduino Uno | 1 | Uno-compatible board |
| Motor Shield V2 | 1 | PCA9685 + dual TB6612 compatible |
| Prototype Shield | 1 | Optional depending on build |
| HC-06-compatible BLE module | 1 | Optional |
| HC-SR04 | 1 | Optional |
| MT3608 boost converter | 1 | Optional logic supply |
| 2 × AA holder | 1 | Logic power |
| 4 × AA holder | 1 | Motor power |
| Power switches | 2 | Add exact mounting dimensions |
| M3 screws | `[TODO]` | Add lengths and quantities |
| M3 nuts / locknuts | `[TODO]` | Add quantities |
| M3 washers | `[TODO]` | Recommended around motor mounts |
| Standoffs | `[TODO]` | Add dimensions and quantities |
| 1 kOhm resistor | 1 | BLE RX voltage divider |
| 2.2 kOhm resistor | 1 | BLE RX voltage divider |

## Uploaded file checklist

- `[TODO]` Main chassis STL/STEP.
- `[TODO]` Motor brackets.
- `[TODO]` Arduino mount or standoffs.
- `[TODO]` Battery holder mounts.
- `[TODO]` HC-SR04 mount.
- `[TODO]` Switch mounts.
- `[TODO]` Complete assembly STEP file, if available.
- `[TODO]` Tested 3MF print profile.
- `[TODO]` Exploded-view image or assembly diagram.

## Tags

Suggested tags:

`arduino`, `robot`, `robotics`, `mecanum`, `omnidirectional`, `robot car`, `TT motor`, `Arduino Uno`, `Motor Shield`, `BLE`, `HC-SR04`, `DIY electronics`, `RC car`, `STEM`

## Upload classification and license

- Upload type: choose **Original** only if the chassis was designed from scratch by you.
- If any geometry was adapted from another model, choose the appropriate remix/import option and credit the source.
- License: `[TODO: choose license]`.

Possible license choices to consider:

- CC BY: reuse and commercial use allowed with attribution.
- CC BY-NC: reuse allowed with attribution, no commercial use.
- CC BY-NC-SA: non-commercial reuse with attribution; derivatives use the same license.
- Standard Digital File License: use MakerWorld-specific restrictions.

## Photo and video shot list

Required before publishing:

1. Hero photo: completed robot, front three-quarter view, clean background.
2. Top view showing the Arduino/shield stack and battery placement.
3. Bottom view showing motor mounts and wire routing.
4. Front close-up of the HC-SR04 mount.
5. Side close-up showing TT motor mounting and wheel clearance.
6. Photo of all printed parts before assembly.
7. Photo clearly showing the mecanum X wheel pattern.
8. Photo of the actual successful print for the print profile.
9. Optional short video/GIF: forward, reverse, strafe, and rotate.

Photo cleanup goals for later:

- neutral background;
- consistent white balance;
- remove distracting cables and tools;
- preserve the real printed texture and assembly details;
- no fake components or altered geometry;
- crop one landscape hero image and several detail images.

## Information still needed from the designer

- Exact chassis dimensions.
- Final list and names of model files.
- Printer model.
- Filament type and brand.
- Actual slicer settings.
- Whether supports were required.
- Screw lengths, nut quantities, and standoff dimensions.
- Mecanum wheel diameter and axle specification.
- TT motor voltage and gear ratio.
- Exact switch cutout dimensions.
- Whether the HC-SR04 mount and battery mounts are included in the uploaded model.
- CAD software used and whether STEP/source files will be shared.
- License choice.
- Public source-code link, if the firmware and Android app will be released.
