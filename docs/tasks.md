# Scheduled and Asynchronous Tasks

You can define server tasks that occur on a regular basis, as well as tasks that need to run asynchronously.

## Scheduled Tasks

Tasks need an identifier that stays consistent and a pattern for when they should be run.

```kotlin
object Server : ServerPathGroup(ServerPath.root) {
    val scheduled = schedule("scheduled-task", Duration.ofMinutes(15)) {
        println("This occurs every 15 minutes")
    }
    val daily = schedule("scheduled-task-2", LocalTime.NOON) {
        println("This occurs every day at noon server-time.")
    }
}
```

## Async Tasks

Tasks that need to be run asynchronously can be created as follow.

```kotlin
object Server : ServerPathGroup(ServerPath.root) {
    val testTask = task("test-task") { input: Int ->
        println("async task got $input")
    }
    val endpoint = path("test-task-start").get.handler {
        testTask(42)
        HttpResponse.plainText("OK")
    }
}
```

To call your async task, simply treat it like a function.

Tasks can have any input type that is serializable.

Note that if you are deploying to AWS Lambda that you need to be aware of time-limits for the execution of your function.
