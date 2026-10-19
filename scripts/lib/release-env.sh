# Bash 3.2 compatible. Call with a dotenv path; exported environment values take precedence.
load_release_env() {
  local key value complete
  complete=0
  command -v python3 >/dev/null || { printf 'python3 is required to read release env settings.\n' >&2; return 1; }
  while IFS= read -r -d '' key && IFS= read -r -d '' value; do
    if [ "$key" = __RELEASE_ENV_DONE__ ]; then complete=1; break; fi
    if ! declare -p "$key" >/dev/null 2>&1; then export "$key=$value"; fi
  done < <(python3 "$ROOT/scripts/lib/release-env.py" "$1")
  [ "$complete" = 1 ]
}
