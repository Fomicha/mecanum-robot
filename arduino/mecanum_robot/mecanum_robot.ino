#include <Wire.h>
#include <Adafruit_MotorShield.h>
#include <SoftwareSerial.h>
#include <RoboLink.h>

SoftwareSerial bluetooth(10, 11); // RX, TX
RoboLinkSerial robolink;

Adafruit_MotorShield shield = Adafruit_MotorShield();

Adafruit_DCMotor *FL = shield.getMotor(1); // M1
Adafruit_DCMotor *FR = shield.getMotor(2); // M2
Adafruit_DCMotor *BR = shield.getMotor(3); // M3
Adafruit_DCMotor *BL = shield.getMotor(4); // M4

const int TRIG_PIN = 6;
const int ECHO_PIN = 7;

const int DEADZONE = 10;
const int MIN_VALID_DISTANCE_CM = 8;
const int OBSTACLE_CM = 32;
const int OBSTACLE_CLEAR_CM = 40;
const int OBSTACLE_HITS_REQUIRED = 3;
const int OBSTACLE_CLEAR_HITS_REQUIRED = 3;
const int SONAR_FILTER_SIZE = 7;
const int SONAR_FILTER_MIN_SAMPLES = 5;
const int SONAR_INVALID_LIMIT = 20;
const unsigned long SONAR_INTERVAL_MS = 60;
const unsigned long SONAR_PULSE_TIMEOUT_US = 12000;
const unsigned long TELEMETRY_INTERVAL_MS = 500;
const unsigned long DEBUG_INTERVAL_MS = 500;

float rawDistanceCm = -1;
float distanceCm = -1;
float sonarSamples[SONAR_FILTER_SIZE];
int sonarSampleCount = 0;
int sonarSampleIndex = 0;
int sonarInvalidCount = 0;
unsigned long lastSonarAt = 0;
unsigned long lastDebugAt = 0;
int obstacleHits = 0;
int obstacleClearHits = 0;
bool forwardBlocked = false;
bool obstacleAvoidanceEnabled = true;

void setMotor(Adafruit_DCMotor *motor, int speed) {
  speed = constrain(speed, -255, 255);

  if (speed > 0) {
    motor->setSpeed(speed);
    motor->run(FORWARD);
  } else if (speed < 0) {
    motor->setSpeed(-speed);
    motor->run(BACKWARD);
  } else {
    motor->run(RELEASE);
  }
}

void drive(int fl, int fr, int br, int bl) {
  setMotor(FL, fl);
  setMotor(FR, fr);
  setMotor(BR, br);
  setMotor(BL, bl);
}

void stopAll() {
  drive(0, 0, 0, 0);
}

float readDistanceCm() {
  digitalWrite(TRIG_PIN, LOW);
  delayMicroseconds(2);

  digitalWrite(TRIG_PIN, HIGH);
  delayMicroseconds(10);
  digitalWrite(TRIG_PIN, LOW);

  unsigned long duration = pulseIn(ECHO_PIN, HIGH, SONAR_PULSE_TIMEOUT_US);
  if (duration == 0) return -1;

  return duration * 0.0343 / 2.0;
}

float filterDistanceCm(float measuredCm) {
  if (measuredCm < MIN_VALID_DISTANCE_CM) {
    sonarInvalidCount++;
    if (sonarInvalidCount >= SONAR_INVALID_LIMIT) {
      sonarSampleCount = 0;
      sonarSampleIndex = 0;
      return -1;
    }
    return distanceCm;
  }

  sonarInvalidCount = 0;
  sonarSamples[sonarSampleIndex] = measuredCm;
  sonarSampleIndex = (sonarSampleIndex + 1) % SONAR_FILTER_SIZE;
  if (sonarSampleCount < SONAR_FILTER_SIZE) {
    sonarSampleCount++;
  }

  float sorted[SONAR_FILTER_SIZE];
  for (int i = 0; i < sonarSampleCount; i++) {
    sorted[i] = sonarSamples[i];
  }

  for (int i = 1; i < sonarSampleCount; i++) {
    float value = sorted[i];
    int j = i - 1;
    while (j >= 0 && sorted[j] > value) {
      sorted[j + 1] = sorted[j];
      j--;
    }
    sorted[j + 1] = value;
  }

  return sorted[sonarSampleCount / 2];
}

void updateObstacleState() {
  float measuredCm = readDistanceCm();
  rawDistanceCm = measuredCm;
  distanceCm = filterDistanceCm(measuredCm);

  bool filterReady = sonarSampleCount >= SONAR_FILTER_MIN_SAMPLES;
  bool valid = filterReady && distanceCm >= MIN_VALID_DISTANCE_CM;
  if (!obstacleAvoidanceEnabled) {
    obstacleHits = 0;
    obstacleClearHits = 0;
    forwardBlocked = false;
  } else if (!valid) {
    obstacleHits = 0;
    obstacleClearHits = 0;
  } else if (distanceCm < OBSTACLE_CM) {
    obstacleClearHits = 0;
    if (!forwardBlocked && obstacleHits < OBSTACLE_HITS_REQUIRED) {
      obstacleHits++;
    }

    if (!forwardBlocked && obstacleHits >= OBSTACLE_HITS_REQUIRED) {
      forwardBlocked = true;
      Serial.print("Forward obstacle block: ON distance=");
      Serial.println(distanceCm);
    }
  } else {
    obstacleHits = 0;

    if (distanceCm > OBSTACLE_CLEAR_CM) {
      if (forwardBlocked && obstacleClearHits < OBSTACLE_CLEAR_HITS_REQUIRED) {
        obstacleClearHits++;
      }

      if (forwardBlocked && obstacleClearHits >= OBSTACLE_CLEAR_HITS_REQUIRED) {
        forwardBlocked = false;
        obstacleClearHits = 0;
        Serial.print("Forward obstacle block: OFF distance=");
        Serial.println(distanceCm);
      }
    } else {
      obstacleClearHits = 0;
    }
  }

  int telemetryDistance = valid ? (int)(distanceCm + 0.5f) : -1;
  robolink.setSensor("distance", telemetryDistance);
  robolink.setSensor("blocked", forwardBlocked ? 1 : 0);
  robolink.setSensor("sonar", obstacleAvoidanceEnabled ? 1 : 0);
}

int normalizeAxis(int value) {
  value = constrain(value, -255, 255);

  if (abs(value) <= DEADZONE) return 0;

  // Works with RoboLink ranges -100..100 or -255..255.
  if (abs(value) <= 100) {
    return map(value, -100, 100, -255, 255);
  }

  return value;
}

int readSpeed() {
  int raw = robolink.get("speed", 160);

  if (raw <= 9) {
    return map(raw, 0, 9, 90, 230);
  }

  return constrain(raw, 90, 230);
}

void mecanumDrive(int vx, int vy, int rot, int maxSpeed) {
  if (obstacleAvoidanceEnabled && vy > 0 && forwardBlocked) {
    stopAll();
    return;
  }

  long fl = vy + vx + rot;
  long fr = vy - vx - rot;
  long br = vy + vx - rot;
  long bl = vy - vx + rot;

  long maxAbs = max(max(abs(fl), abs(fr)), max(abs(br), abs(bl)));
  if (maxAbs > 255) {
    fl = fl * 255 / maxAbs;
    fr = fr * 255 / maxAbs;
    br = br * 255 / maxAbs;
    bl = bl * 255 / maxAbs;
  }

  fl = fl * maxSpeed / 255;
  fr = fr * maxSpeed / 255;
  br = br * maxSpeed / 255;
  bl = bl * maxSpeed / 255;

  drive(fl, fr, br, bl);
}

void setup() {
  Serial.begin(115200);
  bluetooth.begin(9600);

  pinMode(TRIG_PIN, OUTPUT);
  pinMode(ECHO_PIN, INPUT);

  if (!shield.begin()) {
    Serial.println("Motor shield not found");
    while (1);
  }

  robolink.begin(bluetooth);
  robolink.setTimeoutMs(700);
  robolink.setSensor("distance", -1);
  robolink.setSensor("blocked", 0);
  robolink.setSensor("sonar", 1);
  robolink.setSendInterval(TELEMETRY_INTERVAL_MS);

  stopAll();
  Serial.println(F("Mecanum firmware 1.5.1 ready"));
}

void loop() {
  robolink.update();

  if (robolink.hasReceivedData()) {
    obstacleAvoidanceEnabled = robolink.get("sonar", 1) != 0;
  }

  if (millis() - lastSonarAt >= SONAR_INTERVAL_MS) {
    lastSonarAt = millis();
    updateObstacleState();
  }

  if (millis() - lastDebugAt >= DEBUG_INTERVAL_MS) {
    lastDebugAt = millis();
    Serial.print(F("sonar_raw_cm="));
    Serial.print(rawDistanceCm);
    Serial.print(F(" filtered_cm="));
    Serial.print(distanceCm);
    Serial.print(F(" blocked="));
    Serial.print(forwardBlocked ? 1 : 0);
    Serial.print(F(" near_hits="));
    Serial.print(obstacleHits);
    Serial.print(F(" auto_stop="));
    Serial.println(obstacleAvoidanceEnabled ? 1 : 0);
  }

  if (!robolink.hasReceivedData() || robolink.isTimedOut()) {
    stopAll();
    return;
  }

  int vx = normalizeAxis(robolink.get("steer", 0));
  int vy = normalizeAxis(robolink.get("throttle", 0));

  int rot = 0;
  int speedValue = readSpeed();

  if (robolink.get("rot_left", 0) != 0) {
    rot = -255;
  } else if (robolink.get("rot_right", 0) != 0) {
    rot = 255;
  }

  mecanumDrive(vx, vy, rot, speedValue);
}
