# Deploying To AWS Lambda

### The TLDR is this:

#### There is no TLDR
We've done our best to simplify this, but the process is still complicated. You need to read the whole thing.

## The _LOOONG_ way around

Let's start with what I will NOT be covering.

I will not explain:

- How to purchase a domain.

Unlike the Ktor deployments, most of the manual things I didn't cover there will actually be handled by this deployment
process.


Now what will I cover:

- Preparing a distribution file for terraform.
- What will the terraform do for you.
- How to generate the terraform.
- Filling out the required terraform variables for your environment.
- Running Terraform
- How tasks and schedules are handled in AWS Lambda

### Preparing a Distribution File

We need to create a deployment package that can be used by AWS. The documentation for that can be found 
[here](https://docs.aws.amazon.com/lambda/latest/dg/java-package.html). However, we already have a gradle task you can 
add to your build.gradle file which will do what's necessary for you.

```
tasks.create("lambda", Sync::class.java) {
    group = "deploy"
    this.destinationDir = project.buildDir.resolve("dist/lambda")
    val jarTask = tasks.getByName("jar")
    dependsOn(jarTask)
    val output = jarTask.outputs.files.find { it.extension == "jar" }!!
    from(zipTree(output))
    into("lib") {
        from(configurations.runtimeClasspath) {
            var index = 0
            rename { s -> (index++).toString() + s }
        }
    }
}
```

Every time you want to deploy your server, you MUST run the new custom `lambda` gradle task. This will produce an output
in `build/dist/lamba` that will be gathered by the terraform script and uploaded to an S3 bucket to be used by AWS 
lambda.


### AWS Terraform

Lightning Server utilizes terraform for deploying a server to an AWS Lambda environment. If you wish to deploy this
yourself manually, or though your own terraform you may do so. Using our auto generated terraform is NOT required for
deploying to AWS Lambda, but is a large convenience for it. If you don't like our Lambda Engine you may write your
own as well.

The auto generated terraform is designed to be run repeatedly to keep the deployment up to date as your server evolves. 
Though, if you only want to run it once then manually manage the terraform yourself after wards, you can do that.

What is Terraform? Terraform is an infrastructure as code system. [Here](https://developer.hashicorp.com/terraform) is 
the home page for Terraform. 

Terraform creates resources and records these resources in its state. This allows you to run terraform multiple times
and it will only create/update changes. For us, consecutive runs mean pushing a new distribution file and new lambda 
deployment with the new code. All the other resources will remain the same. 


The auto generated terraform will create around 80 separate AWS resources in the deployment process. That is a good 
example of how much is required for lambda. 

#### What External Resources does AWS terraform support?

External resources that a Lightning Server may user include: cache, file storage, email server, and databases. The 
auto generated terraform supports creating and managing many of these resources.

- Creating CloudWatch Metrics tracking
- Creating a DynamoDB cache
- Creating an Elasticache instance
- Creating S3 buckets as a file storage solution
- Creating SES configurations for an email solution
- Creating MongoDB databases hosted in MongoDB Atlas, (Free, Flex, and Dedicated)

#### What AWS resources can/will the terraform create?

These are a few resources the terraform will create.

- DNS records under a Route53 hosted zone.
- Certificates
- CloudWatch usage alarms
- A Virtual Private Cloud (optional)
- A Nat Gateway (optional)
- API Gateways
- Security policies
- I AM Roles

#### How much manual work is required to use the terraform?

If you use only supported external resources, the terraform will handle nearly everything for you. The only thing you 
need to do is setup your AWS organization, optionally a MongoDB Atlas account, and optionally create a Hosted Zone in 
Route53 by buying a domain in AWS, or delegating an existing domain to route53. That is it. Every other resource 
required for deployment is created and managed by the auto generated terraform. 

If you use external resource is not support by our terraform, you will need to create that yourself and supply the 
settings for connections/use in the .tfvars file.

### Generating the Terraform

#### AWS Adapter

To generate the Terraform we must first create an instance of the Lightning Server AWSAdapter.

```
import com.lightningkite.lightningserver.aws.AwsAdapter


class AwsHandler : AwsAdapter() {
    init {
        // Instantiate Your Server code here
        <Instantiate Server>
        <Manual Declarations>
        loadSettings()
    }
}
```
The AWS adapter is the entry point used by the Lambda environment. The Engine is already implemented in the abstract 
class, all we need to do is create a concrete instance.

Unfortunately at this time the AWS jvm does not support introspecting, so any available handlers to settings need to be 
declared/referenced explicitly. This will look like this.


```
package com.example.server
import com.lightningkite.lightningserver.aws.AwsAdapter


class AwsHandler : AwsAdapter() {
    init {
        // Instantiate Your Server code here
        <Instantiate Server>
        
        S3FileSystem
        DynamoDbCache
        CloudwatchMetrics
        
        loadSettings()
    }
}
```

These classes will register themselves as a handler for a specific setting type once they are referenced. If you do not
reference one of these, but your files settings uses the schema "s3://", then loading your settings will fail.

#### Call createTerraform

There is a single function you must call to create the terraform. This is not a gradle task, you must call this at 
runtime.

```
fun main() {
    // Instantiate Your Server code here
    <Instantiate Server>

    createTerraform("com.example.server.AwsHandler", "<ProjectNameHere>", File("path/to/desired/output/folder"))
}
```

You must instantiate your server before calling create terraform because it will produce terraform files for your 
settings and schedules.

When this runs the first time it will create an example folder and fill it with files.

```
--Output Folder
    --example
        --*.tf
        --project.json
        --*.tf
```

There are a variety of files in here. There is a file for each main resource to be created in AWS. There will also be 
files for each Setting required by the server, and a file for each Schedule used by the server.

We could use these files, but it is not recommended. We will create another folder in the output directory with the 
name of the environment we want to deploy and copy the `project.json` file from the example folder into hear and rerun
the creation function. The `createTerraform` function will look for all subdirectories in the output folder that have a
`project.json` file in it, and will produce a set of terraform files in that folder. 

#### project.json

The `project.json` file holds a set of configurations for the deployment. The `createTerraform` function is designed to 
be rerun many times. Any configuration settings in `project.json` will not be overwritten, but used to create the other 
.tf files. You will also see a `terraform.tfvars` files as well. This file will never be overwritten either after the 
initial creation.

```
--Output Folder
    --example
        --*.tf
        --project.json
        --*.tf
    --production
        --*.tf
        --project.json
        --terraform.tfvars
        --*.tf
```

What is in the `project.json` file

```
{
    "projectName": "ExampleServer",
    "bucket": "<terraform state bucket>", 
    "bucketPathOverride": <state file path>, 
    "region": "us-west-2", 
    "availabilityZones": [
        "us-west-2a",
        "us-west-2b",
        "us-west-2c"
    ],
    "core": "Lambda", 
    "vpc": true,
    "existingVpc": false,  
    "domain": true, 
    "profile": "default", 
    "createBeforeDestroy": false, 
    "handlers": {
        "cache": "DynamoDB",
        "secretBasis": "Standard",
        "exceptions": "Direct",
        "general": "Standard",
        "database": "MongoDB Dedicated",
        "logging": "Direct",
        "files": "S3",
        "metrics": "Cloudwatch",
        "email": "SMTP through SES",
    }
}
```

#### Property Explanations

- bucket: The S3 bucket that will hold your terraform state. We do not support any other terraform backends at this time.
- bucketPathOverride: The path and file name of the terraform state in your terraform bucket
- region: The AWS region you want to deploy to
- core: The type of deployment in AWS. Currently only "lambda" is supported. In the future we may add other options such 
  as creating an EC2 instance and running with a Ktor engine.
- vpc: If you want your server to be behind a Virtual Private Cloud. Strongly recommended for production environments
- existingVpc: If you have an existing VPC you want to create these resources in, you declare it here. You will provide 
  the VPC ID in the .tfvars
- domain: If you want the terraform to create DNS records for you.
- profile: This is the aws profile name. This should correlate to the profile you have added in your .aws/credentials 
  file in your home folder.
- createBeforeDestroy: This option alters order of operations in the later deployments. If true, the old resource won't 
  be destroyed until the new one is successfully created.
- handlers: Each key in this json object correlates to one of your servers declared Setting Requirement. The value will 
  determine how that Setting Requirement will be met. 
  - The value "Direct" mean you will provide the value in the .tfvars or *.auto.tfvars file. 
  - A value of "Standard" means terraform has a way to create and manage these values, though you may need to provide 
    some input in the .tfvars file.
  - Any other value calls out a specific implementation supported by terraform to manage. The best example is the 
    "database" setting. You can provide multiple mongodb values here. "MongoDB Dedicated", "MongoDB Flex", or 
    "MongoDB Free". Each will create a database in Mongo Atlas, but using a different type of Database they offer. These
    may require variables that will be provided in the .tfvars file.

Each of your Setting Requirements are represented by their own .tf file with the variables for the value. If you add a 
new Requirement, you must regenerate your terraform.

Any time you modify the `project.json` file you MUST rerun `createTerraform`.

#### terraform.tfvars

After you have configured your `project.json` and regenerated terraform, you will need to fill out your `terraform.tfvars`
file. The file will have all the required variables already created with their default values that you will replace. 
This file will go in your version control. Anything sensitive files will go in a *.auto.tfvars file.

Any server specific setting we won't be able to cover here, but the common aws variables you need to fill out are:

- deployment_location (aws region to deploy in)
- domain_name_zone (your aws hosted zone)
- domain_name (the domain/sub-domain under the hosted zone to create records for)
- emergencyContact (email of manager/lead developer of this project/environment)
- lambda_memory_size (available [ram]("https://docs.aws.amazon.com/lambda/latest/dg/configuration-memory.html) to your lambda VM)
- lambda_timeout (the max [run time](https://docs.aws.amazon.com/lambda/latest/dg/configuration-timeout.html) of a lambda invocation)
- lambda_snapstart (whether to use [Lambda Snap Start](https://docs.aws.amazon.com/lambda/latest/dg/snapstart.html) for jvm deployment)


If you have any sensitive values, credentials, or private keys you have as a requirement, you want to add those to a
*.auto.tfvars file, usually a single local.auto.tfvars file will work. You must then add this auto file to your 
.gitignore. All auto.tfvars files will be loaded by terraform.

### Running Terraform

After generating terraform, filling out the configurations, and filling out the terraform variables, we are ready to 
run terraform. 

Open up a terminal in the new folder you created. The `createTerraform` function does produce a couple helper scripts
called `tf` and `tf.ps1`. These handle exporting the AWS_PROFILE and mongo credentials, before passing on any arguments
to the terraform command.

```
#!/bin/bash
export AWS_PROFILE=default
terraform "$@"
```

For this example though we will run our commands manually. 

1. First lets export our AWS_PROFILE

`export AWS_PROFILE=default`

2. Run terraform init

`terraform init`

This will download terraform dependencies, download an existing state, and minor evaluations of your tf files.

(Optional): run `terraform plan`. This will produce a plan and print this to the console, allowing you to see and verify
every step terraform will take.

3. Run terraform apply

`terraform apply`

This will spit out a plan, then as for verification to run the plan.

If all goes correct, you will have a fully functional Lambda server ready to take request immediately after the 'apply' 
completes.

### Tasks and Schedules

Schedules defined in your server will result in individual terraform files. The Terraform will create EventBridge rules
for each scheduler. These rules will Trigger a lambda invocation with the purpose of running that Schedule.

Tasks will invoke a new instance of the Lambda with the provided input. 

Both Schedules and Tasks will result in an invocation of the Lambda.

Because Lambda has hard cutoff times on an invocation, this means you cannot have a long running task or schedule. You 
can invoke a new instance of a task from within the task, but this can be problematic and explosive if not handled 
correctly, as well AWS can detected nested calls and will terminate these chains if you reach a nested count of 16.


### Some other notes on Lambda Deployments

Serverless means the infrastructure will manage running and scaling for you. Lambda will only send a single request to
an instance at a time. If there are multiple requests at once, they each go to their own invocation. This means there
can be hundreds of instances of your code running at the same time.

These instances can start up and shut down whenever AWS pleases. This means you cannot store any information locally in 
ram. Any information that needs a longer lifespan than a single request MUST go in a cache or a database.

The settings.json file will be produced by terraform, encrypted, then inserted into the distribution files before 
uploading to the S3 bucket. The decryption key for the settings file will be an Environment Variable for Lambda, and the 
AWSAdapter will retrieve the key, decrypt the file and load in the settings.