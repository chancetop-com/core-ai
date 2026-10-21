"""Apply local fixes to the Terminal-Bench 2.1 task tests.

The upstream task definitions have two contract bugs that reject valid solutions:

* configure-git-webserver tells the agent that login setup is out of scope, but its
  verifier assumes sshd and a `user` account already exist. The verifier now provides
  the SSH service and authorizes the key for the actual login user.
* git-multibranch's expect script assumes the clone always prompts for a password;
  a valid setup that also accepts a key makes the script abort on a closed spawn.
  The expect blocks now accept either authentication path.

Run after cloning or updating the dataset:
    python core-ai-benchmark/harbor/patch_dataset.py [dataset-dir]
"""

import sys
from pathlib import Path

DEFAULT_DATASET = Path(__file__).resolve().parent / ".datasets" / "terminal-bench-2-1"

CONFIGURE_ORIGINAL = """# Setup SSH auth: generate a key pair and authorize it for the git user
mkdir -p ~/.ssh
ssh-keygen -t ed25519 -f ~/.ssh/id_ed25519 -N "" -q
mkdir -p /home/user/.ssh
cp ~/.ssh/id_ed25519.pub /home/user/.ssh/authorized_keys
chown -R user:user /home/user/.ssh
chmod 700 /home/user/.ssh
chmod 600 /home/user/.ssh/authorized_keys
"""

CONFIGURE_PATCHED = """# The task tells the agent that login setup is out of scope, so the verifier
# provides sshd and authorizes the key for the actual login user.
DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openssh-server > /dev/null
SSH_USER="$(awk -F: '$3 >= 1000 && $3 < 65534 {print $1; exit}' /etc/passwd)"
SSH_HOME="$(getent passwd "$SSH_USER" | cut -d: -f6)"
mkdir -p "$SSH_HOME/.ssh" /run/sshd
ssh-keygen -A > /dev/null 2>&1
mkdir -p ~/.ssh
ssh-keygen -t ed25519 -f ~/.ssh/id_ed25519 -N "" -q
cp ~/.ssh/id_ed25519.pub "$SSH_HOME/.ssh/authorized_keys"
chown -R "$SSH_USER:$SSH_USER" "$SSH_HOME/.ssh"
chmod 700 "$SSH_HOME/.ssh"
chmod 600 "$SSH_HOME/.ssh/authorized_keys"
pgrep -x sshd > /dev/null || /usr/sbin/sshd
"""

CONFIGURE_CLONE_ORIGINAL = "git clone user@localhost:/git/server server-test"
CONFIGURE_CLONE_PATCHED = 'git clone "$SSH_USER@localhost:/git/server" server-test'

GIT_MULTIBRANCH_CLONE_ORIGINAL = """spawn git clone git@localhost:/git/project project-test
expect {
    "yes/no" { send "yes\\\\r"; exp_continue }
    "password:" { send "password\\\\r" }
}
expect eof
"""

GIT_MULTIBRANCH_CLONE_PATCHED = """spawn git clone git@localhost:/git/project project-test
expect {
    "yes/no" { send "yes\\\\r"; exp_continue }
    "password:" { send "password\\\\r"; exp_continue }
    eof
}
"""

GIT_MULTIBRANCH_PUSH_ORIGINAL = """spawn bash -c "cd project-test && git push -u origin %s"
expect "password:"
send "password\\\\r"
expect eof
"""

GIT_MULTIBRANCH_PUSH_PATCHED = """spawn bash -c "cd project-test && git push -u origin %s"
expect {
    "password:" { send "password\\\\r"; exp_continue }
    eof
}
"""


def patch_text(path: Path, original: str, patched: str, marker: str) -> bool:
    with path.open("r", encoding="utf-8", newline="") as f:
        text = f.read().replace("\r\n", "\n")
    if marker in text:
        return False
    if original not in text:
        print(f"skip (pattern not found): {path}")
        return False
    with path.open("w", encoding="utf-8", newline="\n") as f:
        f.write(text.replace(original, patched, 1))
    print(f"patched: {path}")
    return True


def main() -> int:
    dataset = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_DATASET
    if not (dataset / "tasks").is_dir():
        print(f"dataset not found: {dataset}")
        return 1

    configure = dataset / "tasks" / "configure-git-webserver" / "tests" / "verify.sh"
    patch_text(configure, CONFIGURE_ORIGINAL, CONFIGURE_PATCHED, "login setup is out of scope")
    patch_text(configure, CONFIGURE_CLONE_ORIGINAL, CONFIGURE_CLONE_PATCHED, '"$SSH_USER@localhost')

    multibranch = dataset / "tasks" / "git-multibranch" / "tests" / "test_outputs.py"
    patch_text(multibranch, GIT_MULTIBRANCH_CLONE_ORIGINAL, GIT_MULTIBRANCH_CLONE_PATCHED, 'exp_continue }\n    eof')
    patch_text(multibranch, GIT_MULTIBRANCH_PUSH_ORIGINAL % "main", GIT_MULTIBRANCH_PUSH_PATCHED % "main", 'origin main"\n    expect {')
    patch_text(multibranch, GIT_MULTIBRANCH_PUSH_ORIGINAL % "dev", GIT_MULTIBRANCH_PUSH_PATCHED % "dev", 'origin dev"\n    expect {')
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
