# Bulb Notification Control

Android app that makes your Wipro smart bulb blink whenever you receive a notification.

## Features

- Listens for all notifications on your Android device
- Automatically connects to your Wipro BLE bulb via MAC address
- Sends blink command to the bulb when a notification is received

## Setup

### Prerequisites

- Android device running Android 5.0 (API 21) or higher
- Wipro smart BLE bulb (tested with Bluetooth module v1.2, MCU module v1.2)
- BLE (Bluetooth Low Energy) capability

### Installation

1. **Clone and Build:**
   ```bash
   git clone <repo-url>
   cd bulb
   ./gradlew assembleDebug
   ```

2. **Install APK:**
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

3. **Enable Notification Listener:**
   - Open the app
   - Tap "Enable Notification Listener"
   - Grant permission to the app in Notification Listener settings

4. **Pair your Bulb:**
   - Make sure your Wipro bulb is paired with your phone via Bluetooth
   - Power on the bulb and press its button to make it discoverable

## Configuration

Edit `BulbController.kt` to change the bulb's MAC address:

```kotlin
companion object {
    private const val BULB_MAC_ADDRESS = "DC:23:51:0C:BD:F4"
}
```

## Troubleshooting

If the bulb doesn't blink:

1. Check that the bulb's MAC address is correct
2. Ensure the bulb is powered on and in range
3. Press the bulb's power button to wake it from sleep mode
4. Check logcat for errors: `adb logcat | grep BulbController`

## Command Customization

The blink command is defined in `BulbController.kt`:

```kotlin
val blinkCommand = byteArrayOf(0x01, 0x01, 0xFF)
```

You may need to adjust these bytes based on your bulb's protocol. Check `logcat` to see which services/characteristics the app discovers.

## GitHub Actions

This repo includes GitHub Actions to automatically build the APK on each push/PR.

## License

MIT
