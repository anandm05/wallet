#!/usr/bin/env bash
# Idempotent 2GB swapfile setup for the free-tier 1GB-RAM host.
# Run once on the EC2 instance before the first `docker compose up`.
set -euo pipefail

SWAPFILE=/swapfile

if swapon --show=NAME --noheadings | grep -q "^${SWAPFILE}$"; then
  echo "swap already active on ${SWAPFILE}, skipping"
else
  if [ ! -f "$SWAPFILE" ]; then
    sudo fallocate -l 2G "$SWAPFILE" 2>/dev/null || sudo dd if=/dev/zero of="$SWAPFILE" bs=1M count=2048
    sudo chmod 600 "$SWAPFILE"
    sudo mkswap "$SWAPFILE"
  fi
  sudo swapon "$SWAPFILE"
  grep -q "^${SWAPFILE} " /etc/fstab || echo "${SWAPFILE} none swap sw 0 0" | sudo tee -a /etc/fstab > /dev/null
fi

# Prefer RAM over swap; only spill under real pressure.
echo "vm.swappiness=10" | sudo tee /etc/sysctl.d/60-swappiness.conf > /dev/null
sudo sysctl --system > /dev/null

free -h
