# Claude Skills for Lightning Server

This directory contains Claude Code skills for working with the Lightning Server framework.

## Available Skills

### lightning-server.md
**Purpose:** Comprehensive guide to building applications with Lightning Server.

**Covers:**
- ServerBuilder pattern and project structure
- Endpoint definition (simple and typed)
- Database operations with type-safe query DSL
- Authentication & authorization setup
- File handling and storage
- WebSocket implementation
- Background tasks and scheduled jobs
- Caching patterns
- Testing strategies
- Deployment (Ktor, AWS Lambda, etc.)
- Best practices and anti-patterns

**Use when:**
- Building new Lightning Server endpoints
- Setting up authentication
- Working with databases
- Implementing real-time features
- Creating background tasks
- Writing tests
- Deploying your application
- Learning Lightning Server concepts

**Usage:**
```
Use the lightning-server skill to help me build an API endpoint
How do I set up email authentication in Lightning Server?
Show me how to use the database query DSL
```

## Installation

To install these skills system-wide (available in all projects):

```bash
./scripts/install-claude-skill.sh
```

This copies the skills to `~/.claude/skills/` where Claude Code can find them globally.

## What are Claude Skills?

Claude skills are markdown files that provide context and guidance to Claude Code for specific frameworks, tools, or tasks. They help Claude:

1. Understand framework-specific patterns and conventions
2. Generate code that follows best practices
3. Provide accurate, context-aware suggestions
4. Remember common pitfalls and anti-patterns
5. Reference framework documentation effectively

## Using Skills

Once installed, skills are automatically available in Claude Code. You can:

1. **Reference explicitly:** "Use the lightning-server skill to..."
2. **Ask framework questions:** "How do I... in Lightning Server?"
3. **Let Claude choose:** Claude will automatically use relevant skills based on context

## Skill Structure

Each skill typically includes:

- **Overview:** What the framework/tool does
- **Core Concepts:** Fundamental patterns and principles
- **Common Patterns:** Frequently used code structures
- **Best Practices:** Recommended approaches
- **Anti-Patterns:** What to avoid
- **Examples:** Concrete code samples
- **References:** Where to find more information

## Benefits of Local Skills

Having skills in your project repository:

✅ Version-controlled guidance for your team
✅ Project-specific conventions documented
✅ Easy to update as the framework evolves
✅ Sharable across team members
✅ Can be customized for your use cases

## Creating New Skills

To create a skill for a new Lightning Server feature:

1. Create a new `.md` file in this directory
2. Follow the structure of `lightning-server.md`
3. Focus on one specific area (e.g., `lightning-server-websockets.md`)
4. Include practical examples
5. Document gotchas and edge cases
6. Update this README

## Tips for Effective Skills

**Do:**
- ✅ Be specific and actionable
- ✅ Include code examples
- ✅ Explain the "why" behind patterns
- ✅ Document common mistakes
- ✅ Reference official docs

**Don't:**
- ❌ Be too generic or vague
- ❌ Just copy documentation
- ❌ Include outdated information
- ❌ Forget to update when framework changes

## Maintenance

Skills should be updated when:
- Lightning Server has a major release
- Common patterns change
- New features are added
- Team conventions evolve
- Issues are discovered in existing guidance

## Learn More

- [Lightning Server Documentation](../docs/)
- [Claude Code Skills Documentation](https://docs.claude.com/claude-code/skills)
- [Project Setup Guide](../docs/setup.md)
