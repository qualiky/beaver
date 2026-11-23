# Installing Beaver without a paid developer account

You can run Beaver on your own iPhone without a paid Apple Developer Program membership. Apple's free Apple ID signing is enough. The trade-off is that the app expires and must be signed again every 7 days.

There are two options.

## Option A: Xcode direct install (free Apple ID)

Use this if you have a Mac with Xcode.

1. Copy the example config:

   ```sh
   cp Config/Local.xcconfig.example Config/Local.xcconfig
   ```

2. Edit `Config/Local.xcconfig`:
   - Set `BEAVER_BUNDLE_ID` to an ID only you use, such as `com.yourname.beaver`.
   - Set the development team to the Apple ID team that Xcode shows for your account.

3. Generate the project and open it:

   ```sh
   xcodegen generate
   open Beaver.xcodeproj
   ```

4. In Xcode, add your Apple ID under Settings → Accounts if it is not there yet.
5. Connect your iPhone, select it as the run destination, and run the `Beaver` scheme.
6. On the iPhone, trust your developer profile if iOS asks you to.

**Expiry:** an app signed with a free Apple ID stops launching after 7 days. Connect the phone to the Mac and run the app from Xcode again to reinstall it. Your data is kept only if the bundle ID is the same and you did not delete the app (see "Protect your data" below).

## Option B: SideStore or AltStore with the unsigned .ipa

Use this if you do not want to keep a Mac connected. SideStore and AltStore re-sign the app with your free Apple ID and refresh it in the background, so it usually does not expire.

1. Build the unsigned `.ipa` on a Mac:

   ```sh
   scripts/build-ipa.sh
   ```

   The file is written to `build/Beaver.ipa`. It is not signed, so it cannot be installed as it is. SideStore or AltStore signs it when you add it.

2. Install SideStore or AltStore on your iPhone. Follow the official setup guide for the current version:
   - SideStore: https://sidestore.io
   - AltStore: https://altstore.io

   SideStore needs a one-time pairing file made from a computer. Follow that project's current instructions for this step. The steps change between releases, so this guide does not repeat them.

3. Add `build/Beaver.ipa` to SideStore or AltStore and install it.

**Refresh:** SideStore and AltStore refresh the app every 7 days. Keep the app store running on the phone, or on a computer on the same network, so the refresh can happen.

**Free Apple ID limits:** a free Apple ID allows:

- 3 active sideloaded apps at a time
- 10 new app IDs per 7 days

If you hit these limits, remove an app you no longer use, or wait for the 7-day window to reset.

## Protect your data

Beaver keeps all its data on the device. If the app expires and is removed, or you delete it, its data is removed too. Protect yourself:

- **Export a JSON backup regularly.** In Beaver, go to Settings → Backup and export the file. Do this before a long trip, and at least once a week. Beaver shows a reminder on the Week tab when your last export is more than 7 days old.
- **Keep the bundle ID the same between installs.** A different bundle ID is treated as a different app, so it has an empty data store. Use the same `BEAVER_BUNDLE_ID` every time you build or sign.
- **Store the backup file somewhere safe,** such as Files or a computer you control. It contains your financial data, so do not post it online.
- **Test a restore once.** Import your backup into a fresh install to be sure it works before you need it.

If the app expires and you lose data, you can restore it from the latest backup with Settings → Backup → Import, or from onboarding's "Restore from backup" option.
