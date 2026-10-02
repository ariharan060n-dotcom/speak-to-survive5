# Speak to Survive - Android app (Phase 2)

Hands-free emergency alerts. Triggers:
- Secret voice phrase (offline voice recognition, nothing leaves the phone)
- Power button pressed 5 times quickly (changeable 3-8 in Settings)
- "Hold to send SOS now" button

## How the alert travels (automatic, in this order)

1. **Internet available:** a message with a Google Maps location link is sent to each contact
   THROUGH THE APP (push notification on the contact's phone, using the free ntfy.sh service).
2. **No internet:** an SMS with the location link is sent.
3. **No signal at all:** Bluetooth mesh. Nearby phones that have this app pick up your SOS,
   show it, pass it on to other phones nearby (up to 6 hops), and any of them that has internet or
   mobile signal forwards it to your contacts. The alert also keeps updating your location every minute.

The app does NOT call anyone by default. Auto-call can still be switched on in Settings.
"I'M SAFE" stops everything and tells your contacts (and the mesh) that you are safe.

IMPORTANT for the in-app (internet) route: every person who should receive in-app alerts must
install the app, switch protection on, and type THEIR OWN phone number in Settings > About you.
Contacts without the app get nothing from route 1, so switch on "Also send SMS when online"
if some of your contacts do not have the app.

Not in this version: live-tracking web page (Firebase).

## Get the APK without installing anything (about 15 minutes)

1. Create a free account at https://github.com
2. Click **+** (top right) > **New repository**. Name it `speak-to-survive`. Choose **Public**. Click **Create repository**.
3. Click **uploading an existing file**.
4. Unzip this project on your computer. Open the unzipped folder and drag EVERYTHING inside it
   (the `app` folder, `.github` folder, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `.gitignore`, `README.md`)
   into the GitHub page. Wait until all files finish uploading, then click **Commit changes**.
   - If the hidden `.github` folder did not upload (it is hidden on Mac/Windows):
     on GitHub click **Add file > Create new file**, type the name `.github/workflows/build.yml`
     (typing the slashes creates the folders), paste the contents of that file, and commit.
5. Click the **Actions** tab. If asked, click the green button to enable workflows.
   The build starts by itself. If it does not, click **Build APK** on the left, then **Run workflow**.
6. Wait about 5-10 minutes for a green tick.
7. Click the finished run, scroll to **Artifacts**, and download **SpeakToSurvive-APK** (a zip). Unzip it to get `app-debug.apk`.
8. Send `app-debug.apk` to your phone (WhatsApp, Drive, USB cable).
9. On the phone, open the APK. Allow "Install unknown apps" when asked. Ignore the Play Protect warning (tap "Install anyway").

If the build shows a red cross, open the failed run, copy the error text and send it to Claude.

## First run on the phone

1. Contacts tab: add 1-3 trusted contacts (friends or family. Never use 112 for testing).
2. Settings tab: enter your name, YOUR OWN phone number, and a secret phrase (2-4 common English words).
3. Settings > "Keep it working in the background": allow Display over other apps and Unrestricted battery.
   On Xiaomi/Oppo/Vivo/Realme also turn on Autostart for the app in the phone's own settings.
4. Home tab: tap the big button, allow all permissions (including Nearby devices), and wait for "Listening".
5. Tap "Send test alert". Your contacts get an alert marked TEST ALERT (in the app if online, else SMS). No call is made.
6. Then try the real triggers with a friend as the contact.

## Notes

- Voice model: the build downloads a small offline Indian-English model automatically.
  Tamil is not supported by this engine; use English words.
- After restarting the phone, open the app and tap the big button again (Android does not allow
  microphone services to start by themselves after a reboot).
- The power-button trigger counts screen on/off changes. Turn off the phone's own
  Emergency SOS / camera power-button shortcuts so they do not clash.
- Android may delay or limit calls started when the screen is off. Allowing "Display over other apps" helps.

## Testing the Bluetooth mesh (needs 2 or 3 phones with the app)

1. Install the app on every phone. Switch protection on, allow "Nearby devices", and keep Location (GPS) switched on.
2. Phone A = the person in danger, with A's contact being a friend's number.
   Phone B = a helper nearby with protection on.
3. Put phone A in airplane mode (or remove the SIM) so there is no internet and no signal. Turn Bluetooth back on.
4. Trigger SOS on phone A. After about 10 seconds the Home tab / notification shows it is searching for nearby phones.
5. Phone B should show "SOS from a nearby phone" with a map link. If phone B has internet, it forwards the alert to A's contacts.

## Privacy and limits

- The in-app route uses the public ntfy.sh service. Each phone number maps to a hard-to-guess topic, but anyone who
  knows the number AND this app's formula could read or send to it. Fine for a prototype; for real use add a secret
  pairing code between contacts.
- Mesh relays share your contacts' numbers with nearby phones only while an SOS is being relayed.
- The mesh needs Google Play services and keeps Bluetooth/Wi-Fi busy, so it uses extra battery. Turn it off in Settings if needed.
- Range is about 10-100 m per hop, so the mesh helps only when other app users are nearby.
