#!/usr/bin/env bash
# One-command deploy to the free-tier EC2 host. Handles everything that CAN
# be automated; refuses to guess or bypass anything that genuinely needs a
# human (an expired AWS session, a security-group change) rather than
# hanging or silently working around it.
#
# Usage:
#   infra/deploy.sh              # check + deploy only if the server is behind HEAD
#   infra/deploy.sh --force      # deploy even if the server already matches HEAD
#   infra/deploy.sh --push       # also `git push origin main` first (off by default,
#                                #   matches CLAUDE.md's "push only when explicitly asked")
#   infra/deploy.sh --check-only # do every check + diff, but never deploy
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

INSTANCE_ID="i-0cc0601d0e320c3d8"
SECURITY_GROUP_ID="sg-0751ef115d9eb66f4"
SSH_KEY="$HOME/.ssh/anand-ssh-key.pem"
REMOTE_USER="ubuntu"
REMOTE_DIR="/home/ubuntu/wallet"
REMOTE_MARKER="$REMOTE_DIR/.deployed_commit"

FORCE=false; DO_PUSH=false; CHECK_ONLY=false
for arg in "$@"; do
  case "$arg" in
    --force) FORCE=true ;;
    --push) DO_PUSH=true ;;
    --check-only) CHECK_ONLY=true ;;
    *) echo "unknown flag: $arg" >&2; exit 2 ;;
  esac
done

echo "== 1. Local build/test verification =="
mvn -q compile test-compile
mvn -q test -Dtest=HashChainServiceTest -DfailIfNoTests=false
echo "compile, test-compile, and the Docker-free unit test all pass."

echo
echo "== 2. AWS auth =="
if ! aws sts get-caller-identity > /dev/null 2>&1; then
  echo "AWS session expired or not logged in. This step needs a human -" >&2
  echo "run 'aws login' yourself, then re-run this script." >&2
  exit 1
fi
echo "authenticated."

echo
echo "== 3. Instance state =="
STATE=$(aws ec2 describe-instances --instance-ids "$INSTANCE_ID" \
  --query 'Reservations[0].Instances[0].State.Name' --output text)
PUBLIC_IP=$(aws ec2 describe-instances --instance-ids "$INSTANCE_ID" \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
echo "instance $INSTANCE_ID is $STATE at $PUBLIC_IP"
if [ "$STATE" != "running" ]; then
  echo "Instance isn't running. Start it yourself (aws ec2 start-instances --instance-ids $INSTANCE_ID)" >&2
  echo "or via the console, then re-run this script." >&2
  exit 1
fi

echo
echo "== 4. Security-group reachability =="
# The security group allow-lists a single IPv4 /32, not a CIDR range wide
# enough to survive a dynamic-IP change - this WILL drift over time.
# Deliberately never auto-modified here: changing a security group is a
# security-settings change and stays a human decision, every time.
MY_IP=$(curl -4 -s --max-time 8 ifconfig.me || echo "")
ALLOWED_IP=$(aws ec2 describe-security-groups --group-ids "$SECURITY_GROUP_ID" \
  --query 'SecurityGroups[0].IpPermissions[?FromPort==`22`].IpRanges[0].CidrIp' --output text)
if [ -z "$MY_IP" ] || [ "${ALLOWED_IP%/32}" != "$MY_IP" ]; then
  echo "Your current IP ($MY_IP) doesn't match the security group's allow-listed" >&2
  echo "IP for port 22 ($ALLOWED_IP). SSH will time out. Fix it yourself with:" >&2
  echo "  aws ec2 revoke-security-group-ingress --group-id $SECURITY_GROUP_ID --protocol tcp --port 22 --cidr $ALLOWED_IP" >&2
  echo "  aws ec2 authorize-security-group-ingress --group-id $SECURITY_GROUP_ID --protocol tcp --port 22 --cidr ${MY_IP}/32" >&2
  echo "(repeat for port 8080 if you also need direct API access), then re-run this script." >&2
  exit 1
fi
echo "current IP ($MY_IP) matches the security group allow-list."

SSH_CMD=(ssh -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 -i "$SSH_KEY" "$REMOTE_USER@$PUBLIC_IP")

echo
echo "== 5. What's actually deployed right now vs. local HEAD =="
LOCAL_HEAD=$(git rev-parse HEAD)
DEPLOYED=$("${SSH_CMD[@]}" "cat $REMOTE_MARKER 2>/dev/null || true")
if [ -z "$DEPLOYED" ]; then
  echo "no prior deploy tracked on the server (first deploy, or marker predates this script)."
elif [ "$DEPLOYED" = "$LOCAL_HEAD" ]; then
  echo "server already runs $LOCAL_HEAD - nothing to deploy."
  if [ "$FORCE" != true ]; then
    $CHECK_ONLY && exit 0
    echo "(use --force to redeploy anyway)"
    exit 0
  fi
else
  echo "server runs $DEPLOYED, local HEAD is $LOCAL_HEAD. Commits not yet deployed:"
  git log --oneline "$DEPLOYED..$LOCAL_HEAD" 2>/dev/null || echo "  (deployed commit not found in local history - can't diff, deploying HEAD anyway)"
fi

if $CHECK_ONLY; then
  echo; echo "--check-only: stopping here, no push or deploy."
  exit 0
fi

if $DO_PUSH; then
  echo
  echo "== 6. Pushing to GitHub =="
  git push origin main
fi

echo
echo "== 7. Deploying to $PUBLIC_IP =="
"${SSH_CMD[@]}" bash -s <<REMOTE_SCRIPT
set -euo pipefail
if [ ! -d "$REMOTE_DIR/.git" ]; then
  git clone https://github.com/anandm05/wallet.git "$REMOTE_DIR"
fi
cd "$REMOTE_DIR"
git fetch origin
git checkout main
git reset --hard origin/main
test -f .env || { echo "$REMOTE_DIR/.env is missing on the server - create it first (see CLAUDE.md), then re-run." >&2; exit 1; }
docker compose build app
docker compose up -d app
echo "$LOCAL_HEAD" > "$REMOTE_MARKER"
echo "deployed commit \$(git rev-parse HEAD) at \$(date -u +%FT%TZ)"
REMOTE_SCRIPT

echo
echo "Done. Deployed $LOCAL_HEAD to $PUBLIC_IP."
