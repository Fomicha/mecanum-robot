# Mecanum Robot Chassis for TT Motors and Arduino Uno

This is the chassis from my custom four-wheel mecanum robot.

It is designed around four common yellow TT DC gear motors and 50 mm mecanum wheels. The central platform provides space for the robot electronics, battery holders, wiring, and optional sensors. My build uses an Arduino Uno with stacked shields, but the chassis itself is not tied to one specific controller.

With the wheels installed in the correct mirrored pattern, the platform can:

- move forward and backward;
- strafe left and right;
- rotate in place;
- combine movement directions for omnidirectional driving.

The model has been assembled and tested as a working robot chassis.

## Compatible hardware

The chassis was designed around:

- 4 × yellow TT gear motors;
- 4 × 50 mm mecanum wheels compatible with TT motor shafts;
- Arduino Uno or a similarly sized controller;
- compact battery holders and supporting electronics;
- M3 × 30 mm mounting hardware.

Electronics, motors, wheels, batteries, and fasteners are not included in the model files.

## Complete robot project

The complete robot build documentation, wiring details, Arduino firmware, and custom Android controller app are available on GitHub. The app connects over BLE to the HC-06-compatible FFE1 UART module and provides landscape controls for driving, strafing, rotation, speed selection, and ultrasonic auto-stop.

[View the complete Mecanum Robot project on GitHub](https://github.com/Fomicha/mecanum-robot)
