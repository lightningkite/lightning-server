# Settings

Settings in Lightning Server are defined programmatically, are fully customizable, and are strictly typed.  They leverage the KotlinX serialization system, and are usually placed into some form of `settings.json` file.

## Defining a Setting

At the top of your server object, define a new setting by using the following syntax: 

```kotlin
// Server.kt
object Server : ServerPathGroup(ServerPath.root) {
    val settingName = setting(name = "settingName", default = "defaultValue")
    //...
}
```

The given name will be the property name in the `settings.json` file.  Settings can be of any type; the default value above defines the type as being a `String`.

We can then access the value of the setting by accessing the value and adding `()` to the end, like so:

```kotlin
// Server.kt
object Server : ServerPathGroup(ServerPath.root) {
    //...
    val seeSampleSetting = path("print-setting").get.handler {
        HttpResponse.plainText(settingName())
    }
}
```

Now, rerun your application and you'll see that it does not run and gives you the following error:

```output
Settings were incorrect.  Suggested updates are inside settings.suggested.json.
```

Now, take a look at that generated file and you'll see the setting is now there populated to its default value.  You will thus always be forced to define *every* setting before the application will run.

Copy from `settings.suggested.json` into `settings.json` and run again, and your server will be up again!

As mentioned in the previous section, *it is considered an important Lightning Server principal to ensure your application works out of the box with the generated `settings.json`.*  Make sure you establish good, working defaults for every setting in your system.  Mock external services as necessary.

## Settings in Tests

Back in [setup](setup.md), you may remember the `TestSettings` object.  We centrally define one set of settings for unit tests.  If you wish to override the default value of a setting for your unit test, make the following modification:

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