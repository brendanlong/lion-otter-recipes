# Privacy Policy

**Last updated:** October 12, 2026

Lion+Otter Recipes ("the app") is an open-source Android application for
importing, organizing, and cooking with recipes. This privacy policy explains
what data the app collects, how it is used, and your options for managing your
data.

## No Ads or Tracking

The app contains **no advertisements**, **no analytics**, and **no third-party
tracking**. We do not collect behavioral data or location information. The only
diagnostic data the app sends is crash and error reports, plus an
app-start event used to calculate how often the app crashes (see
[Crash Reporting](#crash-reporting-firebase-crashlytics) below).

## Offline Use Without an Account

The app does **not** require an account. By default, all recipes and data are
stored locally on your device. You can use the app entirely offline without
signing in.

## Google Sign-In (Optional)

If you choose to sign in with a Google account, the app enables cloud sync so
your recipes and meal plans are available across devices. When you sign in:

- **Firebase Authentication** receives your Google credentials to create or
  identify your account.
- Your data is stored in Firebase under a **Firebase user ID**, not your email
  address. Firebase itself may store your email as part of the authentication
  record.
- The app does not read, store, or share your email address, contacts, or any
  other Google account data beyond what is needed for authentication.

## Data We Store

### Local (on your device)

- Recipes and meal plans
- App settings and preferences
- Your Anthropic API key (encrypted with AES-256-GCM via Android Keystore)
- A local image cache for recipe photos
- Optional import debug logs (disabled by default; enabled in Settings)

### Cloud (Firebase, only when signed in)

- Recipes (name, ingredients, instructions, tags, notes, timestamps)
- Meal plans (date, meal type, recipe reference)
- Recipe images (stored in Firebase Storage under your user ID)

All cloud data is isolated to your account via Firebase security rules. No
other user can access your data through the app.

## External Services

### Anthropic API (Claude AI)

When you import a recipe, the app sends the **recipe web page content** (HTML
or extracted text) to the Anthropic API for parsing. When you edit a recipe,
the content of the recipe is re-sent for processing. Your API key is sent with
these requests for authentication.

### Recipe Websites

When you import a recipe by URL, the app fetches the web page directly.

### Firebase (Google)

If signed in, the app uses Firebase Firestore and Firebase Storage to sync
your recipes, meal plans, and recipe images. See Google's privacy policy for
details on how Firebase handles data:
https://firebase.google.com/support/privacy

### Crash Reporting (Firebase Crashlytics)

Release builds of the app send crash and error reports to Firebase
Crashlytics so we can find and fix bugs. A report contains:

- The error type, stack trace and error message (which can sometimes include
  the address of a web page being imported; text from recipes is removed)
- Device model, Android version, app version, available memory and disk space,
  and whether the app was in the foreground
- A random installation identifier created by Firebase, used to count how many
  installs are affected by a problem

Each time the app starts, it also sends a small session event (the installation
identifier, app version and device information above) so Crashlytics can
calculate the share of sessions that crash.

Reports do **not** include your recipes, notes, Anthropic API key, email
address, or Firebase user ID. Crashlytics retains reports for 90 days. See
Google's description of the data Crashlytics collects:
https://firebase.google.com/support/privacy#crash-stored-info

## Data Retention

- **Local data** remains on your device until you delete it or uninstall the
  app.
- **Cloud data** persists until you delete individual items or delete your
  account.
- **Import debug logs** are stored locally and can be cleared from Settings.

## Deleting Your Data

### In the App

You can delete your account and all associated data from **Settings > Delete
Account and Data**. This permanently removes:

- All recipes and meal plans from Firebase
- All recipe images from Firebase Storage
- Your Firebase authentication account
- Local image cache

### By Request

If you are unable to use the in-app deletion feature, you can request data
deletion by opening an issue at:
https://github.com/brendanlong/lion-otter-recipes/issues/new

## Children's Privacy

The app is not directed at children under 13. We do not knowingly collect
personal information from children.

## Changes to This Policy

Updates to this policy will be posted in this file in the project repository.
The "Last updated" date at the top will reflect the most recent revision.

## Contact

For questions about this privacy policy, please open an issue at:
https://github.com/brendanlong/lion-otter-recipes/issues/new
