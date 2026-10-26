# SafeDrop – AirDrop-style sharing + parental monitoring (Android)

Two apps built from this repo (GitHub Actions > latest run > Artifacts):
- **SafeDrop-Kids-apk**: install on the child's phone.
- **SafeDrop-Parent-apk**: install on the parent's phone.

## One-time Firebase setup (free, works from a phone browser)
1. Go to console.firebase.google.com, sign in, **Create a project** (turn Google Analytics off).
2. Left menu: **Build > Realtime Database > Create database**. Pick a location, choose **locked mode**.
3. Open the **Rules** tab, replace everything with the text below, tap **Publish**:

```
{ "rules": { "pairs": { "$code": { ".read": true, ".write": true } } } }
```
   Only someone who knows the long random pairing code can read or write a family's data, and nobody can list the codes.
4. On the **Data** tab, copy the database address (looks like `https://xxxx-default-rtdb.firebaseio.com`).

## Pairing
1. Parent phone: open SafeDrop Parent, paste the address, enter the child's name, tap *Create pairing code*.
2. Child phone: open SafeDrop Kids > Setup, enter the same address, the pairing code, the child's name and a **parent PIN**.
3. On the child phone, tap *Turn on* for each item (location, usage access, device admin).

## What the parent sees and controls
- Home: last check-in, battery, location (and *Locate now*), screen time today, *Lock phone now*, warnings if a permission is off.
- Files: approve or reject every AirDrop-style send/receive remotely. Optional instant alerts.
- Activity: log of connections, blocked files, approvals, lock events, unpairing.
- Apps: usage per app today, installed apps, block individual apps.
- Rules: daily screen-time limit, bedtime, file types, max size, trusted devices.

## Honest limits
- The child always sees a permanent notification and a "what your parent can see" list. There is no hidden mode.
- Not collected: messages, photos, passwords, keystrokes, screen contents, browsing history.
- Blocking an app, bedtime and the daily limit work by locking the screen (device admin). A child can unlock and try again; it will re-lock within about 15 seconds.
- Device admin makes uninstall a two-step process (turn off admin first); the parent sees "device admin was turned off" in Activity. It is not tamper-proof. Pair it with Google Family Link for a hard block.
- Data goes through your own Firebase database. Anyone with the code can read it, so keep the code private.
- Debug builds only; not published to Google Play.
