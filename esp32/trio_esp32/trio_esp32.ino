/*
  LOCUS Trio node for ESP32 boards. The board advertises and scans at the same time and shares its readings
  with the other devices (phones, laptop, other ESP32s) using the same 4+N byte packet:
      'L' 'T' <device index + 1> <reading of A> ... <reading of the last device> <counter>
  Readings are signed dBm, 0 = unknown. Phones and the laptop node draw the layout; the ESP32 only measures and shares.

  Board package: "esp32 by Espressif" version 2.0.17 (Tools > Board > Boards Manager). The BLE API changed in 3.x.
  Set NODE and MESH_SIZE below, flash, power the board. The serial monitor (115200) prints what it hears.

  NOT TESTED on hardware yet.
*/
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEScan.h>
#include <BLEAdvertising.h>

const int NODE = 3;          // this device: 0 = A, 1 = B, 2 = C, 3 = D, 4 = E, 5 = F
const int MESH_SIZE = 4;     // devices in the mesh, 3..6. Must be the same on every device.

const unsigned long MAX_AGE_MS = 5000;
const int WIN = 24;
float rssiVal[6][WIN];
unsigned long rssiT[6][WIN];
int rssiHead[6];
int rssiCount[6];
uint8_t counter = 0;
BLEAdvertising *advertising;
BLEScan *scan;

void addSample(int from, int rssi) {
  rssiVal[from][rssiHead[from]] = rssi;
  rssiT[from][rssiHead[from]] = millis();
  rssiHead[from] = (rssiHead[from] + 1) % WIN;
  if (rssiCount[from] < WIN) rssiCount[from]++;
}

// Median of the readings of device `from` that are younger than MAX_AGE_MS; returns 0 when there is none.
int medianReading(int from) {
  float v[WIN];
  int c = 0;
  unsigned long now = millis();
  for (int i = 0; i < rssiCount[from]; i++) if (now - rssiT[from][i] <= MAX_AGE_MS) v[c++] = rssiVal[from][i];
  if (c == 0) return 0;
  for (int i = 1; i < c; i++) { float x = v[i]; int j = i - 1; while (j >= 0 && v[j] > x) { v[j + 1] = v[j]; j--; } v[j + 1] = x; }
  float m = (c % 2) ? v[c / 2] : (v[c / 2 - 1] + v[c / 2]) / 2.0f;
  return (int)lroundf(m);
}

class OnResult : public BLEAdvertisedDeviceCallbacks {
  void onResult(BLEAdvertisedDevice dev) {
    if (!dev.haveManufacturerData()) return;
    std::string d = dev.getManufacturerData();           // first two bytes are the company id (0xFFFF, little endian)
    if (d.size() < 2 + 4 + 3 || d.size() > 2 + 4 + 6) return;
    if ((uint8_t)d[0] != 0xFF || (uint8_t)d[1] != 0xFF || d[2] != 'L' || d[3] != 'T') return;
    int n = (int)d.size() - 2 - 4;
    int from = (uint8_t)d[4] - 1;
    if (n != MESH_SIZE || from < 0 || from >= MESH_SIZE || from == NODE) return;
    addSample(from, dev.getRSSI());
  }
};

void advertise() {
  std::string md;
  md += (char)0xFF; md += (char)0xFF;                    // company id 0xFFFF
  md += 'L'; md += 'T'; md += (char)(NODE + 1);
  for (int k = 0; k < MESH_SIZE; k++) md += (char)(k == NODE ? 0 : medianReading(k));
  md += (char)counter++;
  BLEAdvertisementData ad;
  ad.setFlags(0x06);
  ad.setManufacturerData(md);
  advertising->stop();
  advertising->setAdvertisementType(ADV_TYPE_NONCONN_IND);
  advertising->setAdvertisementData(ad);
  advertising->start();
}

void setup() {
  Serial.begin(115200);
  BLEDevice::init("");
  advertising = BLEDevice::getAdvertising();
  scan = BLEDevice::getScan();
  scan->setAdvertisedDeviceCallbacks(new OnResult(), true);   // true = report repeated packets from the same device
  scan->setActiveScan(false);
  scan->setInterval(100);
  scan->setWindow(99);
  advertise();
  Serial.printf("LOCUS Trio node %c of %d\n", 'A' + NODE, MESH_SIZE);
}

unsigned long lastAdv = 0, lastPrint = 0;

void loop() {
  scan->start(1, false);        // scan for 1 second, then handle the results
  scan->clearResults();
  unsigned long now = millis();
  if (now - lastAdv >= 700) { lastAdv = now; advertise(); }
  if (now - lastPrint >= 2000) {
    lastPrint = now;
    Serial.print("readings:");
    for (int k = 0; k < MESH_SIZE; k++) if (k != NODE) Serial.printf("  %c %d dBm", 'A' + k, medianReading(k));
    Serial.println();
  }
}
