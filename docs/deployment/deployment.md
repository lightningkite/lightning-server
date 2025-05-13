# Deployment

The final piece of building a server is running that code in production.

## Where can I run a Lightning Server project in a production environment?

There are currently two fully supported methods deploying your project.

- AWS Lambda, a Serverless infrastructure created and maintained by AWS.
- Ktor for running on ANY dedicated hardware or VM. As long as the machine has the JVM 17 or newer you can run your
  server on it.
- A third out of date option is Azure Functions, which is a Serverless infrastructure by Microsoft. The code hasn't been
  used or touched in several years, but could be brought back to life if necessary. It was functional before.

Both AWS Lambda and Ktor fully support every feature in Lightning Server. There are some limitation enforced in the AWS
environment, such as request and response size limits, or run times for requests, task, and schedules, but these
limitations are external to the Lightning Server's Core library, and you will need to adjust for them accordingly.

### Why do we support these two?

The Ktor backend seems like an odd one. Ktor is itself a feature complete server framework. Why not use it directly?

The Ktor support comes from the early development process of Lightning Server. At Lightning Kite we began to develop
using the Ktor framework, and we built an entirely separate library called Ktor Batteries which added much of the
features we have today, to a Ktor code base. However, as a software agency with our own clients, we were ask to deploy a
server into a serverless environment. Ktor is not built for or officially support a serverless environment, though you
could jank your way into it. Since we didn't want to abandon our new server tools we recently created, and since all 
the ideas, concepts, and features in Ktor Batteries were mostly isolated away from Ktor already, all we needed to do was 
cut those direct connections with an abstraction layer called an Engine, and we can now deploy Lightning Server projects 
to any location we wish, as long as you're willing to create the required Engine. Thus, our two fully supported 
deployment methods are born.

Since we forked from the old Ktor Batteries and into Lightning Server, we have not moved to a lower level HTTP or Socket
framework but remained with that original Ktor Engine that we initially built. One day we may create out own engine that
is much smaller, but it works fine for now, JetBrains maintains Ktor so we only need to manage the Engine.

### How do I deploy these two methods?

You will find the  full deployment process for each method below

- [Ktor](ktor.md)
- [AWS Lambda](awsLambda.md)