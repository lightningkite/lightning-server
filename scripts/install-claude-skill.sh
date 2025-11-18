#!/bin/bash
# Install Lightning Server Claude skills to the user's system-wide Claude directory

set -e

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}=== Lightning Server Claude Skill Installer ===${NC}"
echo ""

# Determine the Claude skills directory
CLAUDE_DIR="$HOME/.claude/skills"

# Check if we're in the right directory
if [ ! -d "./.claude/skills" ]; then
    echo -e "${RED}Error: .claude/skills directory not found${NC}"
    echo "Please run this script from the lightning-server project root directory."
    exit 1
fi

echo -e "${BLUE}Source directory:${NC} ./.claude/skills"
echo -e "${BLUE}Target directory:${NC} $CLAUDE_DIR"
echo ""

# Create the skills directory if it doesn't exist
if [ ! -d "$CLAUDE_DIR" ]; then
    echo -e "${YELLOW}Creating Claude skills directory...${NC}"
    mkdir -p "$CLAUDE_DIR"
fi

# Count skills to install
SKILL_COUNT=$(find ./.claude/skills -name "*.md" -type f | wc -l | tr -d ' ')

if [ "$SKILL_COUNT" -eq 0 ]; then
    echo -e "${YELLOW}No skills found to install${NC}"
    exit 1
fi

echo -e "${BLUE}Found $SKILL_COUNT skill(s) to install${NC}"
echo ""

# Install each skill
for skill_file in ./.claude/skills/*.md; do
    if [ -f "$skill_file" ]; then
        skill_name=$(basename "$skill_file")

        # Skip README
        if [ "$skill_name" = "README.md" ]; then
            continue
        fi

        echo -e "${BLUE}Installing:${NC} $skill_name"
        cp "$skill_file" "$CLAUDE_DIR/$skill_name"

        if [ -f "$CLAUDE_DIR/$skill_name" ]; then
            echo -e "${GREEN}  ✓ Installed${NC}"
        else
            echo -e "${RED}  ✗ Failed${NC}"
        fi
    fi
done

echo ""
echo -e "${GREEN}=== Installation Complete ===${NC}"
echo ""
echo -e "${BLUE}Installed skills:${NC}"
ls -1 "$CLAUDE_DIR"/*.md 2>/dev/null | xargs -n1 basename | grep -v "^README.md$" || echo "  (none)"
echo ""
echo -e "${BLUE}Skills location:${NC} $CLAUDE_DIR"
echo ""
echo -e "${BLUE}Usage in Claude Code:${NC}"
echo "  You can now invoke these skills in any project:"
echo "  • 'Use the lightning-server skill to help me build an endpoint'"
echo "  • 'How do I set up auth in Lightning Server?'"
echo "  • Or reference the skill directly in your prompts"
echo ""
echo -e "${GREEN}Happy coding with Lightning Server!${NC}"
