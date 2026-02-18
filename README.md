# Lion+Otter Recipes

A modern Android app for importing, organizing, and cooking with recipes from any website. Uses AI (Claude) to extract structured recipe data from web pages.

## Features

- **AI-Powered Import**: Paste any recipe URL and let Claude extract ingredients, instructions, and metadata
- **Smart Organization**: Recipes are automatically tagged (breakfast, dessert, gluten-free, etc.)
- **Grouped Sections**: Supports complex recipes with multiple sections (e.g., cake + frosting)
- **Ingredient Scaling**: Adjust serving sizes with automatic quantity recalculation
- **Cloud Sync**: Real-time sync across devices via PowerSync + Supabase with Google Sign-In
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

# Build release APK
./gradlew assembleRelease

# Run tests
./gradlew testDebugUnitTest

# Run lint
./gradlew lintDebug
```

The APKs will be in:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release-unsigned.apk`

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

## Cloud Sync Setup (PowerSync + Supabase)

Cloud sync is optional — the app works fully offline without it. To enable syncing recipes and meal plans across devices, see [docs/sync-setup.md](docs/sync-setup.md) for full setup instructions covering:

- Supabase project creation (Postgres, Auth, Storage)
- Google OAuth configuration
- PowerSync instance setup and sync rules
- Environment variable configuration

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
- **Cloud Sync**: PowerSync + Supabase (Auth, Postgres, Storage)
- **Preferences**: DataStore
- **Image Loading**: Coil

## Project Structure

```
app/src/main/kotlin/com/lionotter/recipes/
├── data/           # Data layer
│   ├── local/      # Room database, DataStore
│   ├── remote/     # Anthropic API, web scraping
│   ├── repository/ # Repository implementations
│   └── sync/       # PowerSync + Supabase sync
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
