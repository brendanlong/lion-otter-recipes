# Cloud Sync Setup (PowerSync + Supabase)

Cloud sync is **optional** — the app works fully offline without it. To enable syncing recipes and meal plans across devices, you need to set up Supabase (backend) and PowerSync (real-time sync).

## Overview

| Component | Role |
|---|---|
| **Supabase** | Postgres database, authentication (Google Sign-In), file storage |
| **PowerSync** | Real-time streaming sync between Postgres and local SQLite |
| **Room** | Local SQLite database (unchanged — PowerSync shares the same DB) |

## 1. Create a Supabase Project

1. Go to [supabase.com](https://supabase.com) and create a new project
2. Note your **Project URL** and **publishable key** from Settings > API

## 2. Set Up the Postgres Schema

Run the following SQL in the Supabase SQL Editor (or via migrations):

### Tables

```sql
CREATE TABLE recipes (
    id TEXT PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES auth.users(id),
    name TEXT NOT NULL,
    source_url TEXT,
    story TEXT,
    servings INTEGER,
    prep_time TEXT,
    cook_time TEXT,
    total_time TEXT,
    ingredient_sections_json TEXT NOT NULL DEFAULT '[]',
    instruction_sections_json TEXT NOT NULL DEFAULT '[]',
    equipment_json TEXT NOT NULL DEFAULT '[]',
    tags_json TEXT NOT NULL DEFAULT '[]',
    image_url TEXT,
    source_image_url TEXT,
    original_html TEXT,
    is_favorite BOOLEAN NOT NULL DEFAULT false,
    created_at BIGINT NOT NULL,    -- epoch millis
    updated_at BIGINT NOT NULL
);

CREATE TABLE meal_plans (
    id TEXT PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES auth.users(id),
    recipe_id TEXT NOT NULL,
    recipe_name TEXT NOT NULL,
    recipe_image_url TEXT,
    date TEXT NOT NULL,            -- ISO-8601 yyyy-MM-dd
    meal_type TEXT NOT NULL,
    servings REAL NOT NULL DEFAULT 1.0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
```

### Row Level Security

```sql
ALTER TABLE recipes ENABLE ROW LEVEL SECURITY;
ALTER TABLE meal_plans ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can CRUD own recipes" ON recipes
    FOR ALL USING (auth.uid() = owner_id);
CREATE POLICY "Users can CRUD own meal plans" ON meal_plans
    FOR ALL USING (auth.uid() = owner_id);
```

### Indexes

```sql
CREATE INDEX idx_recipes_owner ON recipes(owner_id);
CREATE INDEX idx_recipes_updated ON recipes(updated_at);
CREATE INDEX idx_meal_plans_owner ON meal_plans(owner_id);
CREATE INDEX idx_meal_plans_date ON meal_plans(date);
```

### LWW Upsert Functions

These server-side functions handle last-write-wins conflict resolution by comparing `updated_at` timestamps:

```sql
CREATE OR REPLACE FUNCTION upsert_recipe(p_data jsonb)
RETURNS void AS $$
BEGIN
    INSERT INTO recipes (id, owner_id, name, source_url, story, servings,
        prep_time, cook_time, total_time, ingredient_sections_json,
        instruction_sections_json, equipment_json, tags_json,
        image_url, source_image_url, original_html,
        is_favorite, created_at, updated_at)
    VALUES (
        p_data->>'id', (p_data->>'owner_id')::uuid, p_data->>'name',
        p_data->>'source_url', p_data->>'story', (p_data->>'servings')::int,
        p_data->>'prep_time', p_data->>'cook_time', p_data->>'total_time',
        p_data->>'ingredient_sections_json', p_data->>'instruction_sections_json',
        p_data->>'equipment_json', p_data->>'tags_json',
        p_data->>'image_url', p_data->>'source_image_url', p_data->>'original_html',
        (p_data->>'is_favorite')::boolean,
        (p_data->>'created_at')::bigint, (p_data->>'updated_at')::bigint
    )
    ON CONFLICT (id) DO UPDATE SET
        name = EXCLUDED.name,
        source_url = EXCLUDED.source_url,
        story = EXCLUDED.story,
        servings = EXCLUDED.servings,
        prep_time = EXCLUDED.prep_time,
        cook_time = EXCLUDED.cook_time,
        total_time = EXCLUDED.total_time,
        ingredient_sections_json = EXCLUDED.ingredient_sections_json,
        instruction_sections_json = EXCLUDED.instruction_sections_json,
        equipment_json = EXCLUDED.equipment_json,
        tags_json = EXCLUDED.tags_json,
        image_url = EXCLUDED.image_url,
        source_image_url = EXCLUDED.source_image_url,
        original_html = EXCLUDED.original_html,
        is_favorite = EXCLUDED.is_favorite,
        updated_at = EXCLUDED.updated_at
    WHERE recipes.updated_at < EXCLUDED.updated_at;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION upsert_meal_plan(p_data jsonb)
RETURNS void AS $$
BEGIN
    INSERT INTO meal_plans (id, owner_id, recipe_id, recipe_name,
        recipe_image_url, date, meal_type, servings, created_at, updated_at)
    VALUES (
        p_data->>'id', (p_data->>'owner_id')::uuid, p_data->>'recipe_id',
        p_data->>'recipe_name', p_data->>'recipe_image_url',
        p_data->>'date', p_data->>'meal_type',
        (p_data->>'servings')::real,
        (p_data->>'created_at')::bigint, (p_data->>'updated_at')::bigint
    )
    ON CONFLICT (id) DO UPDATE SET
        recipe_id = EXCLUDED.recipe_id,
        recipe_name = EXCLUDED.recipe_name,
        recipe_image_url = EXCLUDED.recipe_image_url,
        date = EXCLUDED.date,
        meal_type = EXCLUDED.meal_type,
        servings = EXCLUDED.servings,
        updated_at = EXCLUDED.updated_at
    WHERE meal_plans.updated_at < EXCLUDED.updated_at;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
```

## 3. Set Up Google Authentication

### Google Cloud Console

1. Go to [Google Cloud Console](https://console.cloud.google.com/) > APIs & Credentials
2. Create an **OAuth consent screen** (external, if needed)
3. Create two OAuth 2.0 client IDs:
   - **Web application** — note the client ID (this is used as `GOOGLE_WEB_CLIENT_ID`)
   - **Android** — use package name `com.lionotter.recipes` and your signing key's SHA-1 fingerprint

### Supabase Dashboard

1. Go to Authentication > Providers > Google
2. Enable the Google provider
3. Enter the **Web** client ID and client secret from Google Cloud Console
4. The authorized redirect URI is provided by Supabase — add it to the Web OAuth client's redirect URIs in Google Cloud Console

**Important:** The app uses the **Web** client ID in `googleNativeLogin(serverClientId = ...)`, not the Android client ID. The Android client ID just needs to be registered in Google Cloud Console for Credential Manager to work.

## 4. Set Up PowerSync

1. Go to [powersync.com](https://www.powersync.com/) and create an instance (or self-host)
2. Connect it to your Supabase Postgres database (connection details are in Supabase > Settings > Database)
3. Add the following sync rules:

### Sync Rules (`sync-rules.yaml`)

```yaml
bucket_definitions:
  user_data:
    parameters: SELECT request.user_id() AS user_id
    data:
      - SELECT id, name, source_url, story, servings, prep_time, cook_time,
          total_time, ingredient_sections_json, instruction_sections_json,
          equipment_json, tags_json, image_url, source_image_url,
          original_html, is_favorite, created_at, updated_at
        FROM recipes
        WHERE owner_id = bucket.user_id
      - SELECT id, recipe_id, recipe_name, recipe_image_url, date,
          meal_type, servings, created_at, updated_at
        FROM meal_plans
        WHERE owner_id = bucket.user_id
```

**Note:** `owner_id` is excluded from the SELECT — it exists only on the server. PowerSync filters by the authenticated user's ID via the bucket parameter.

## 5. (Optional) Set Up Supabase Storage

If you plan to sync recipe images between devices:

1. Go to Supabase > Storage and create a bucket called `recipe-images` (private)
2. Add RLS policies:

```sql
CREATE POLICY "Users can upload own images"
ON storage.objects FOR INSERT
WITH CHECK (
    bucket_id = 'recipe-images'
    AND (storage.foldername(name))[1] = auth.uid()::text
);

CREATE POLICY "Users can read own images"
ON storage.objects FOR SELECT
USING (
    bucket_id = 'recipe-images'
    AND (storage.foldername(name))[1] = auth.uid()::text
);

CREATE POLICY "Users can delete own images"
ON storage.objects FOR DELETE
USING (
    bucket_id = 'recipe-images'
    AND (storage.foldername(name))[1] = auth.uid()::text
);
```

**Note:** Image sync is not yet implemented in the app (planned for Phase 4).

## 6. Configure the App

Set these environment variables before building:

```bash
export SUPABASE_URL="https://your-project.supabase.co"
export SUPABASE_PUBLISHABLE_KEY="sb_publishable_..."
export POWERSYNC_URL="https://your-instance.powersync.com"
export GOOGLE_WEB_CLIENT_ID="123456789-abc.apps.googleusercontent.com"
```

These are read at build time via `buildConfigField` in `app/build.gradle.kts`. They are **public client-side values** protected by RLS — not secrets.

If these are left empty (the default), sync is simply disabled and the app works in local-only mode.

## What Syncs vs. What Stays Local

| Data | Syncs? | Reason |
|---|---|---|
| Recipes | Yes | Core user data |
| Meal plans | Yes | Core user data |
| Pending imports | No | Device-local work queue |
| Import debug logs | No | Device-local diagnostics |
| Anthropic API key | No | Encrypted per-device (Tink AEAD) |
| User preferences | No | Device-specific settings |

## Architecture

The sync layer is designed so the app works identically whether signed in or not:

- **Room DAOs** handle all reads and writes (unchanged from local-only mode)
- **PowerSync** shares the same SQLite database via `RoomConnectionPool`
- When signed in, PowerSync streams changes from Postgres and queues local writes for upload
- The `RecipeSupabaseConnector` uploads changes via the LWW upsert functions
- The `SyncManager` connects/disconnects PowerSync based on auth state
- When signed out, PowerSync is disconnected and the app works purely locally
