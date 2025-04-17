# Deploying With Ktor

### The TLDR is this:

```
import com.lightningkite.lightningserver.ktor.runServer
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.engine.LocalEngine
import com.lightningkite.lightningserver.settings.loadSettings

fun main(){
    // Instantiate Your Server code here
    <Instantiate Server>
    loadSettings(File("Path/to/your/settings.json"))
    runServer(LocalPubSub, LocalCache)
}

```

- Run the gradle task 'distribution:distZip'. 
- Move the new <filename-here>.zip to your deployment machine and preferred folder. 
- Unzip the file in this location 
- Run the shell script at ./<distribution-folder>/bin/<shell-script-here>

The code snippet above and the shell script is the minimum you need to do to start up a server that is ready to response 
to Http Requests or run asynchronous tasks and schedules on a single machine. The code snippet will look very similar to 
the local testing environment we described previously. That is why you should read the rest of this document where I 
explain all the other parts that may be necessary for your deployment environment.

## The _LOOONG_ way around

Let's start with what I will NOT be covering. 

I will not explain:

- How to purchase a domain.
- How to set up DNS records to point to your new server.
- How to set up a reverse proxy such as Nginx, Apache, in front of your server.
- How to create a virtual machine.
- How to install an OS, a firewall, or any other networking beyond port binding for ktor.
- Setting up a process manager like systemd or supervisor.
- Setting up a Cache or Pub/Sub service.
- How to move the compiled jars to your machine.

These topics can be found in great detail on the internet already, or are completely unique to your setup such that I 
can't even start.


Now what will I cover:

- What a Pup/Sub and Cache are, and why the Ktor Engine needs them.
- How Lightning Server Tasks and Schedules run in a Ktor environment.
- What settings are used by Ktor and what they do.
- Creating the distribution of your server.
- Finally Running your distribution


### Pub/Sub

You can read up on what a Pub/Sub is and why a server might need it [here](../pubsub.md)

Even if you do not declare a need for a Pub/Sub in your Settings, you will need to provide a Pub/Sub instance to the 
Ktor Engine. If you have only a single Instance, you can use LocalPubSub. 

Ktor using the Pub/Sub for Websockets, just like the example explained [here](../pubsub.md). Even if you do not use 
websockets in your server, you must provide a Pub/Sub, it will just go unused.

If you do declare a Pub/Sub in your settings requirements you can reuse this instance in runServer if you wish, 
otherwise you can create any Pub/Sub object you wish and pass it in to the runServer function. Just make sure you do not 
commit any credentials to your code, and they are passed in dynamically. That method will be left up to you.

### Cache

You can read up on what a Cache is and why a server might need it [here](../cache.md)

Even if you do not declare a need for a Cache in your Settings, you will need to provide a Cache instance to the Ktor
Engine. If you have only a single Instance, you can use LocalCache. 

Ktor using the cache for Schedules. In order to only have a single instance of a schedule running at a time, Ktor uses 
to cache to create lock keys. Even if you never declare a schedule in your server, you must still provide a Cache 
object, it will just go unused.

If you do declare a cache in your settings requirements you can reuse this instance in runServer if you wish, otherwise 
you can create any Cache object you wish and pass it in to the runServer function. Just make sure you do not commit
any credentials to your code, and they are passed in dynamically. That method will be left up to you.

### Tasks & Schedules

#### Schedules

If you're unfamiliar you can review Schedules [here](../tasks.md)

The Ktor Engine handles Schedules by creating new GlobalScope coroutines at launch time for each schedule defined. In 
the coroutine it calculates the next time it must run the action, then sleeps until that time, run the actions, and 
starts again.

Schedules are run in the same process as the rest of the server. There is no way to enable or disable Schedules in a 
Ktor Engine. 


#### Tasks

If you're unfamiliar you can review Tasks [here](../tasks.md)

The Ktor Engine handles Tasks by creating new GlobalScope coroutines when a task is invoked. It will run the provided 
action, and then that scope will close.

Tasks are run in the same process as the rest of the server. There is no way to enable or disable Tasks in a 
Ktor Engine. 

### Settings Ktor Uses

There are several values from the GeneralServerSettings that Ktor will utilize when running. They are:
- host
- port
- realIpHeader
- cors

The "host" determines what network interface the application will bind to. If you use the value "0.0.0.0", it will bind to 
all network interfaces. If you use "127.0.0.1" it will only bind to the loop back interface, meaning your server cannot 
be access from an external machine. If you have multiple network interfaces you can bind to only a single one. 

The "port" determines what port on the network interface the application will bind to.

the "realIpHeader" value is an interesting one. If you have a reverse proxy in front of your server, then the request ip 
address the server sees may not be accurate, as it will reflect your reverse proxy ip. This value allows you to 
configure your reverse proxy to forward the requests actual ip address as a header, and the ktor engine will be able to 
use this value instead when logging the request ip address.

The "cors" value is used by the Ktor Engine for adding Cors headers to requests. If your server will be accessed from a 
browser, and the website domain is different from the server domain, then you will need to provide a cors configuration.


### Creating a distribution

In your build.gradle file you must declare the `application` plugin and your main class

```
plugins {
    //...
    application
    //...
}

application {
    mainClass.set("com.lightningserver.MainKt") // Your package goes here
}
```

Once you have added these, there will be new gradle group called distribution. All the options will compile and produce
the project and dependency jars, and a script to run the server. Most often you will use the compressed options which
allow quick transferring to the proper machine.


### Running Your Distribution File

Since this is a Kotlin project compiling to the JVM, you need a Java Runtime Environment on your machine, and 
specifically a minimum of JRE 17. If you use a JRE/JDK greater than 17 it should work, but I won't provide any 
guarantees.

Once you have your server distribution in the proper place, and you have a JRE installed, you will find auto created 
scripts in the ./<distribution-folder>/bin folder. There will be a shell script and a .bat script(for Windows). Running 
either of these scripts will launch your application process. Any arguments you provide to these scripts will be passed 
into your application.

One practice I __STRONGLY__ recommend is defining a location for your settings.json file as an argument to your
application. This will allow you to place it anywhere, and more importantly NOT in the distribution folders. The reason
I suggest you do not let the settings file lie in your distribution folders, is most often when deploying, you simply
delete/move the existing distribution folder, and replace it with the new one, then restart your application process.
Keeping the settings file away from these folders makes it harder to accidentally delete this file.