#!/usr/bin/env bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KODKOD_SCRIPT="${SCRIPT_DIR}/run.sh"

# Detect user's login shell
case "$(basename "$SHELL")" in
  zsh)  SHELL_RC="$HOME/.zshrc" ;;
  bash) SHELL_RC="$HOME/.bashrc" ;;
  *)
    echo "Unknown shell: $SHELL. Please add this alias manually:"
    echo "alias kodkod='${KODKOD_SCRIPT}'"
    exit 1
    ;;
esac

# Check if alias already exists
if grep -q "alias kodkod=" "$SHELL_RC" 2>/dev/null; then
  echo "Alias 'kodkod' already exists in $SHELL_RC"
  echo "Current definition:"
  grep "alias kodkod=" "$SHELL_RC"
  read -p "Do you want to update it? (y/N) " -n 1 -r
  echo
  if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Keeping existing alias"
    exit 0
  fi
  # Remove old alias
  sed -i.bak '/alias kodkod=/d' "$SHELL_RC"
fi

# Add alias
echo "alias kodkod='${KODKOD_SCRIPT}'" >> "$SHELL_RC"

echo "✓ Alias 'kodkod' added to $SHELL_RC"
echo "Run: source $SHELL_RC"
echo "Or restart your terminal"
