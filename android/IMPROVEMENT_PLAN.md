# Mecanum Controller Improvement Plan

Working loop:

1. Implement one small feature.
2. Build APK.
3. Install on phone.
4. Test on robot.
5. Adjust before moving to the next feature.

## Backlog

### 1. Control Feel

- [Done in 1.3.1] Add explicit-amplitude haptic feedback on control button press.
- Add separate speed profiles for drive, strafe, and rotate.
- Add precision mode for slow room navigation.
- Tune rotate speed per speed mode instead of one fixed value.

### 2. Safety

- Add emergency stop action in the controller header.
- [Done in 1.4.0] Integrate ultrasonic distance display in the app.
- [Done, needs robot calibration] Add ultrasonic false-positive filtering on Arduino side.
- [Done, needs robot calibration] Auto-stop only when obstacle distance is stable for several readings.

### 3. UI / UX

- Move controller controls closer to thumb zones in landscape if needed.
- Replace center `Connected` text with a compact status dot.
- Polish Devices and Logs screens after controller behavior is stable.
- Add clearer connection states: scanning, connecting, ready, disconnected.

### 4. Bluetooth Stability

- Add reconnect button or auto-reconnect.
- Move BLE connection state into a ViewModel or service-like holder.
- Reduce command size if BLE stutters continue.

### 5. Arduino Protocol / Telemetry

- [Done in 1.4.0] Send distance telemetry from Arduino to app.
- [Done in 1.4.0] Show obstacle state in the app.
- Consider shorter commands such as `F160`, `R125`, `S`.
- Later: add battery voltage telemetry if hardware supports it.

## Current Test

- App locked to sensor landscape.
- Drawer edge gestures disabled.
- Larger touch targets and minimum 150 ms command pulse for short taps.
- Stable multi-touch control stack and click haptic.
- HC-SR04 calibration: ignore echoes below 8 cm, stop after three filtered readings below 32 cm, clear after three readings above 40 cm.
- Sonic distance and obstacle state are shown in the center of the controller.

## Current Next Feature

- Test control responsiveness and false obstacle stops on the robot.
