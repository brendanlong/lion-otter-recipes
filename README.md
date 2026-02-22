# Lion+Otter Recipes

A modern Android app for importing, organizing, and cooking with recipes from any website. Uses AI (Claude) to extract structured recipe data from web pages.

## Features

- **AI-Powered Import**: Paste any recipe URL and let Claude extract ingredients, instructions, and metadata
- **Smart Organization**: Recipes are automatically tagged (breakfast, dessert, gluten-free, etc.)
- **Grouped Sections**: Supports complex recipes with multiple sections (e.g., cake + frosting)
- **Ingredient Scaling**: Adjust serving sizes with automatic quantity recalculation
- **Cloud Sync**: Bidirectional sync across devices via Firebase Firestore with Google Sign-In
- **Offline First**: All recipes stored locally; works without internet after import
- **Clean UI**: Material 3 design with a warm Lion+Otter color theme

## Screenshots

*Coming soon*

## Requirements

- Android 8.0 (API 26) or higher
- [Anthropic API key](https://console.anthropic.com/) for recipe import

## Building

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK with API 35

### Build from Command Line

```bash
# Clone the repository
git clone https://github.com/your-username/lion-otter-recipes.git
cd lion-otter-recipes

# Build debug APK
./gradlew assembleDebug

# Run tests
./gradlew testDebugUnitTest

# Run lint
./gradlew lintDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Release Builds

Release builds use the same debug keystore as an upload key for Google Play App Signing. Google re-signs the final app with their own managed key before distributing to users, so the upload key credentials don't need to be secret — they just need to be consistent.

```bash
# Build a release AAB locally
./gradlew bundleRelease
```

The AAB will be at `app/build/outputs/bundle/release/app-release.aab`.

CI automatically builds a signed release AAB on pushes to `main` (after lint, tests, and debug build pass). If you have `DEBUG_KEYSTORE_BASE64` set as a GitHub secret, CI will use it for both debug and release builds. Otherwise it falls back to the default Android debug keystore.

### Build from Android Studio

1. Open the project in Android Studio
2. Wait for Gradle sync to complete
3. Select `Build > Build Bundle(s) / APK(s) > Build APK(s)`

## Installation

### From APK

1. Enable "Install from unknown sources" in Android settings
2. Transfer the APK to your device
3. Open the APK file to install

### From Android Studio

1. Connect your Android device via USB (with USB debugging enabled)
2. Click the "Run" button or press `Shift+F10`

## Firebase Setup (Cloud Sync)

Cloud sync is optional — the app works fully offline without it. To enable syncing recipes across devices, you need to set up a Firebase project:

1. Go to the [Firebase Console](https://console.firebase.google.com/) and create a new project (or use an existing one)
2. Add an Android app with package name `com.lionotter.recipes`
3. Enable **Authentication** in the Firebase Console:
   - Go to **Authentication > Sign-in method**
   - Enable the **Google** provider (this automatically creates the required OAuth web client)
4. Re-download `google-services.json` (it now includes the OAuth client) and place it at `app/google-services.json`
   - Verify that the `oauth_client` array is **not empty** — it should contain an entry with `"client_type": 3` (web client). This is required for Google Sign-In.
5. Enable **Cloud Firestore**:
   - Go to **Firestore Database** and create a database
   - Set the security rules to restrict access per user:
     ```
     rules_version = '2';
     service cloud.firestore {
       match /databases/{database}/documents {
         match /users/{userId}/{document=**} {
           allow read, write: if request.auth != null && request.auth.uid == userId;
         }
       }
     }
     ```

6. Enable **Cloud Storage for Firebase**:
   - Go to **Storage** and click "Get started"
   - Use the existing bucket (e.g., `gs://lion-otter-recipes.firebasestorage.app`) or create one
   - Set the security rules to restrict access per user and validate image uploads:
     ```
     rules_version = '2';
     service firebase.storage {
       match /b/{bucket}/o {
         match /users/{userId}/images/{fileName} {
           allow read: if request.auth != null && request.auth.uid == userId;
           allow write: if request.auth != null
             && request.auth.uid == userId
             && request.resource.contentType.matches('image/.*')
             && request.resource.size < 5 * 1024 * 1024;
         }
       }
     }
     ```

Data is stored at `users/{userId}/recipes/{recipeId}` and `users/{userId}/mealPlans/{mealPlanId}`, so each user's data is fully isolated. Recipe images are stored in Firebase Storage at `users/{userId}/images/{filename}`.

## App Setup

1. Launch the app
2. Go to **Settings** (gear icon in top right)
3. Enter your Anthropic API key
4. Optionally select your preferred Claude model:
   - **Claude Opus 4.5**: Best quality (default)
   - **Claude Sonnet 4**: Balanced speed/quality
   - **Claude 3.5 Haiku**: Fastest, lowest cost
5. Optionally enable **Cloud Sync** to sync recipes across devices via Google Sign-In

## Usage

### Import a Recipe

1. Tap the **+** button on the home screen
2. Paste a recipe URL (e.g., `https://example.com/chocolate-cake`)
3. Wait for the AI to parse the recipe
4. View your imported recipe with structured ingredients and instructions

### View Recipes

- Browse all recipes on the home screen
- Use the search bar to find recipes by name
- Tap tag chips to filter by category
- Swipe left on a recipe to delete it

### Cook with a Recipe

1. Open a recipe
2. Use the scale controls to adjust serving size
3. Scroll through ingredients and instructions
4. All quantities update automatically when scaled

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose with Material 3
- **Architecture**: MVVM with Clean Architecture
- **DI**: Hilt
- **Database**: Room
- **Networking**: Ktor
- **Cloud Sync**: Firebase Firestore + Firebase Auth
- **Preferences**: DataStore
- **Image Loading**: Coil

## Project Structure

```
app/src/main/kotlin/com/lionotter/recipes/
├── data/           # Data layer
│   ├── local/      # Room database, DataStore
│   ├── remote/     # Anthropic API, web scraping
│   └── repository/ # Repository implementations
├── domain/         # Business logic
│   ├── model/      # Data models
│   └── usecase/    # Use cases
├── ui/             # Presentation layer
│   ├── screens/    # Compose screens
│   ├── navigation/ # Navigation graph
│   └── theme/      # Material theme
└── di/             # Hilt modules
```

See [DESIGN.md](DESIGN.md) for detailed architecture documentation.

## License

*TBD*

## Contributing

*TBD*
