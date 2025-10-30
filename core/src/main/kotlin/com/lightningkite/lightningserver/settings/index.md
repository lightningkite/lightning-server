# Settings Package

The `settings` package provides the infrastructure for managing server configuration with a two-phase lifecycle: configuration and ready.

## Package Files

### Core Classes

#### `ServerSettings.kt`
The main settings manager class that handles the configuration lifecycle. Maintains two registries:
- **serializable**: Raw configuration values from files or programmatic setup
- **goal**: Transformed runtime values after applying getter functions

Key methods:
- `set()` / `setStatic()`: Configure settings during the configuration phase
- `ready()`: Validate and transform all settings for runtime use
- `get()`: Retrieve transformed setting values during runtime
- `allSerializable()` / `allGoals()`: Bulk access to settings state

#### `ServerSettings.ext.kt`
Extension functions for `ServerSettings`:
- Context-aware `set()` and `setStatic()` operations
- `loadFromFile()`: Main entry point for loading configuration from JSON or properties files
  - Supports file auto-generation with defaults
  - Handles encryption/decryption via environment variable
  - Validates required settings and generates suggested files for missing values

#### `SettingsSerializer.kt`
Custom KotlinX Serialization serializer that enables settings to be saved/loaded from files:
- Dynamically builds descriptors based on registered settings
- Supports the special `defaults` property for chained configuration files
- Handles both JSON and properties formats for both main and chained files
- Implements circular dependency detection to prevent infinite loops
- Resolves relative paths in `defaults` property relative to the parent file's directory
- Provides clear error messages for missing or invalid defaults files

### Exception Classes

#### `IncompleteSettingsException.kt`
- **`IncompleteSettingsException`**: Thrown when required settings are missing; auto-generates a suggested file
- **`MissingSettingFile`**: Thrown when the settings file doesn't exist; auto-generates it with defaults

### Utility Classes

#### `OpenSsl.kt`
Internal utility for decrypting OpenSSL-encrypted settings files:
- Supports both modern PBKDF2 and legacy EVP_BytesToKey formats
- **PBKDF2-HMAC-SHA256**: Modern OpenSSL format (10,000 iterations)
- **EVP_BytesToKey with SHA-256**: Legacy format for compatibility
- Automatically detects encryption format at runtime
- Activated via `LIGHTNING_SERVER_SETTINGS_DECRYPTION` environment variable
- Compatible with `openssl enc -aes-256-cbc -pbkdf2` and legacy encryption commands

## Typical Usage

```kotlin
// 1. Create settings manager
val settings = ServerSettings(Server.build().settings)

// 2. Load from file (configuration phase)
settings.loadFromFile(KFile("settings.json"), internalSerializersModule)

// 3. Mark as ready (validates and transforms)
with(serverRuntime) {
    settings.ready()

    // 4. Access settings at runtime
    val value = settings.get(Server.someSetting)
}
```

## Key Concepts

### Two-Phase Lifecycle
1. **Configuration Phase**: Settings can be modified via `set()`, `include()`, or `loadFromFile()`
2. **Ready Phase**: Settings are frozen, validated, and transformed; only `get()` operations are allowed

### Optional vs Required Settings
- **Required settings** (`optional = false`): Must be provided in configuration or an exception is thrown
- **Optional settings** (`optional = true`): Use default values if not provided

### Transformation
Settings can specify a `getter` function that transforms the serializable value into a runtime value:
```kotlin
val hashedPassword = setting("password", "default", getter = { hash(it) })
```

The transformation occurs once during `ready()` and results are cached.

### File Format Detection
- Files containing `.properties` in the name use Java properties format
- All other files use JSON format

### Encryption Support
Set `LIGHTNING_SERVER_SETTINGS_DECRYPTION` environment variable to decrypt OpenSSL-encrypted settings files automatically.

### Configuration Chaining
Use the `defaults` property to inherit settings from a base configuration:
```json
{
  "defaults": "~/base-config.json",
  "webUrl": "http://localhost:8080"
}
```

**Features:**
- Works with both JSON and properties files
- Supports relative paths (resolved relative to parent file's directory)
- Multiple levels of chaining supported
- Automatic circular dependency detection
- Clear error messages for missing files

## Error Handling

The settings system provides helpful error messages and auto-remediation:

1. **Missing file**: Auto-generates with defaults, throws `MissingSettingFile`
2. **Incomplete settings**: Auto-generates suggested file with missing keys, throws `IncompleteSettingsException`
3. **Invalid values**: Collected during `ready()` and reported with detailed error messages

## See Also

- [Settings Documentation](../../../../../docs/settings.md) - User guide for working with settings
- `ServerSetting` class - Individual setting definition
- `ServerBuilder` class - Server definition with settings registration
