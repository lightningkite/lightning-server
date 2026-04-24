# Settings

Last updated October 29, 2025 (`version-5`)

Settings in Lightning Server are defined programmatically, are fully customizable, and are strictly typed. They leverage
the KotlinX serialization system, and are usually placed into some form of `settings.json` file.

Settings follow a two-phase lifecycle:

1. **Configuration Phase**: Settings are loaded from files or set programmatically
2. **Ready Phase**: Settings are validated, transformed, and made available for runtime use

## Defining a Setting

At the top of your server object, define a new setting by using the following syntax:

```kotlin
// Server.kt
object Server : ServerBuilder() {
    val settingName = setting("settingName", "defaultValue")
    //...
}
```

The given name will be the property name in the `settings.json` file. Settings can be of any type; the default value
above defines the type as being a `String`.

We can then access the value of the setting by accessing the value and adding `()` to the end, like so:

```kotlin
// Server.kt
object Server : ServerBuilder() {
    //...
    val seeSampleSetting = path.path("print-setting").get bind HttpHandler {
        HttpResponse.plainText(settingName())
    }
}
```

Now, rerun your application and you'll see that it does not run and gives you the following error:

```output
Settings were incorrect.  Suggested updates are inside settings.suggested.json.
```

Now, take a look at that generated file and you'll see the setting is now there populated to its default value. You will
thus always be forced to define *every* setting before the application will run.

Copy from `settings.suggested.json` into `settings.json` and run again, and your server will be up again!

As mentioned in the previous section, *it is considered an important Lightning Server principal to ensure your
application works out of the box with the generated `settings.json`.*  Make sure you establish good, working defaults
for every setting in your system. Mock external services as necessary.

## File Formats

Lightning Server supports two settings file formats:

### JSON Format

The default and recommended format. Files are detected as JSON unless they contain `.properties` in the filename.

```json
{
  "webUrl": "http://localhost:8080",
  "database": {
    "host": "localhost",
    "port": 5432
  }
}
```

### Properties Format

Java properties format is also supported for simpler configurations:

```properties
# This is a comment
webUrl=http://localhost:8080
database.host=localhost
database.port=5432

# Empty lines and lines starting with # are ignored
# Inline comments are also supported
setting=value # comment
```

**Properties format features:**

- Comments start with `#` (both full-line and inline)
- Empty lines are automatically ignored
- Nested properties use dot notation (e.g., `database.host`)

## Advanced Features

### Encrypted Settings Files

You can encrypt your settings files using OpenSSL for added security. Both modern and legacy OpenSSL encryption formats
are supported:

**Modern OpenSSL (recommended):**

```bash
openssl enc -aes-256-cbc -pbkdf2 -in settings.json -out settings.json.enc
```

**Legacy OpenSSL:**

```bash
openssl enc -aes-256-cbc -md sha256 -in settings.json -out settings.json.enc
```

To use encrypted settings, set the `LIGHTNING_SERVER_SETTINGS_DECRYPTION` environment variable to your encryption
password. Lightning Server will automatically:

1. Detect the encryption format (PBKDF2 or EVP_BytesToKey)
2. Decrypt the file using the appropriate method
3. Load the settings normally

**Supported encryption formats:**

- **PBKDF2-HMAC-SHA256** (OpenSSL 1.1.1+ default with `-pbkdf2` flag)
    - 10,000 iterations for key derivation
    - More secure against brute-force attacks
- **EVP_BytesToKey with SHA-256** (legacy format with `-md sha256`)
    - Single-round SHA-256 key derivation
    - Compatible with older OpenSSL versions

The format is automatically detected at runtime, so no configuration is needed.

### Chained Configuration Files

You can reference a defaults file from your main configuration using the special `defaults` property:

```json
{
  "defaults": "~/shared-config.json",
  "webUrl": "http://localhost:8080"
}
```

**Key features:**

- Settings in the main file override settings from the defaults file
- Both JSON and properties formats are supported for defaults files
- The tilde (`~`) expands to your home directory
- Relative paths are resolved relative to the parent file's directory
- Multiple levels of chaining are supported (defaults can have their own defaults)
- Circular dependencies are automatically detected and reported with clear error messages

**Example with relative paths:**

```
/config/
  ├── base.json         # Base configuration
  └── environments/
      └── prod.json     # References "../base.json"
```

```json
// environments/prod.json
{
  "defaults": "../base.json",
  "database": {
    "host": "prod-db.example.com"
  }
}
```

### Optional Settings

Mark settings as optional to allow them to be omitted from configuration files:

```kotlin
val optionalSetting = setting("optionalSetting", "default", optional = true)
```

Optional settings that are missing from the configuration file will use their default values without generating errors.

### Transformed Settings

Settings can include a transformation function that converts the serialized value into a different runtime value:

```kotlin
val transformedSetting = setting(
    "password",
    "default",
    getter = { plaintext -> hashPassword(plaintext) }
)
```

The transformation happens once during the ready phase and is cached for subsequent accesses.

## Settings in Tests

Back in [setup](setup.md), you may remember the `TestSettings` object. We centrally define one set of settings for unit
tests. If you wish to override the default value of a setting for your unit test, make the following modification:

```kotlin
// ServerTest.kt
object TestSettings {
    init {
        //...
        
        // Set up our settings for the test environment
        Settings.populateDefaults(mapOf(Server.settingName.name to "Unit Test"))
        // .......................^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^...

        //...
    }
}
```

To demonstrate that it worked, we can add a test:

```kotlin
class ServerTest {
    //...
    @Test
    fun testSetting(): Unit = runBlocking {
        val response = Server.seeSampleSetting.test()
        assertEquals("Unit Test", response.body!!.text())
    }
}
```

Give the test a run and you'll see it passes!

NEXT: [Endpoints](endpoints.md)