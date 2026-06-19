Docs feedback: 

- The Lightning Server should strongly encourage * imports.
- Quick start code should include the imports.
- You do need to include the information on how to actually _run_ a lightning server somewhere - the fact that you have to run it in an engine is kind of important, and totally ignored.
- site/typed-endpoints/#sdk-generation is weak.  Don't make up some gradle task that magically works without configuration - instead, focus on the code below that generates the output.
- There are "meta comment" throughout that aren't useful to end-users, usually about "drift checking".  Make them actual comments, not quoted notes.
- Lots of the content contains more technical information than is sensible for end users - for example, the `PathSpec` types are a detail worth noting, but after you've demonstrated use.  After all, the code doesn't actually require you to write out that type.
- Worth noting the 'meta' endpoints in the typed endpoints section - Lightning Server generates docs for you, and that's a huge value.
- Important with services - if you want to use a certain service backend that isn't in the default set (which you will for all real servers, default sets include testing and RAM based mechanisms), you MUST reference its object early!  For example, `MongoDatabase` must be referenced before settings using it can load!
- The pattern recommended for making the companion object a PrincipalType is bad - usually we have separate modules for models and server, and so we won't have the server dependencies needed to define PrincipalType there.  Separate them instead - I usually do something like `object UserAuth: PrincipalType<>`.
- Proof/session needs a dedicated page to itself
- Auth caching keys need included in the Auth doc page.
- Websockets, tasks, and schedules are not covered.
- An overall, "what is a server made of?" page describing the components, terminology, and philosophy of Lightning Server (endpoints, websockets, tasks, schedules, services, etc.) would be very helpful.

Not a bad start!