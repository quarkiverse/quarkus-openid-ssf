#!/usr/bin/env bash
# Runs the test modules of a receiver plan in the conformance suite one after another, each after a
# key press: starts the module in the suite, lets the receiver under test (this module, see
# README.md "Manual runs") play the scenario the module expects via POST /cts/runs/auto, and
# reports the result the suite logged.
set -euo pipefail

suite="${CTS_SUITE_URL:-https://localhost.emobix.co.uk:8443}"
receiver="${CTS_RECEIVER_URL:-https://localhost:9443}"
create=""
filters=()
pause=true
auto=""
default_auto=5
plan=""

usage() {
  cat <<USAGE
Usage: $(basename "$0") [options] [<plan id | plan-detail URL>]

Runs the test modules of a receiver plan one after another, waiting for a key press before each.
Without a plan, the plan of the module currently running in the suite is used.

Options:
  -c, --create <config.json>  create a new plan from a suite configuration (easyssf-test-conformance's suite-config/*.json)
  -m, --module <text>         only modules whose name contains <text> (repeatable)
  -a, --auto [seconds]        continue automatically after a pause (default $default_auto seconds), unless a
                              key is pressed in the meantime
  -y, --yes                   do not wait for key presses at all
  -s, --suite <url>           the suite (default: \$CTS_SUITE_URL or $suite)
  -r, --receiver <url>        the receiver under test (default: \$CTS_RECEIVER_URL or $receiver)
  -h, --help                  this help

Keys: Enter or space runs the module, s skips it, q quits, a continues automatically from here on.
USAGE
}

while (($# > 0)); do
  case "$1" in
    -c|--create) create="$2"; shift 2 ;;
    -m|--module) filters+=("$2"); shift 2 ;;
    -a|--auto)
      if [[ "${2-}" =~ ^[0-9]+$ ]]; then auto="$2"; shift 2; else auto="$default_auto"; shift; fi ;;
    -y|--yes) pause=false; shift ;;
    -s|--suite) suite="${2%/}"; shift 2 ;;
    -r|--receiver) receiver="${2%/}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "Unknown option $1" >&2; usage >&2; exit 2 ;;
    *) plan="$1"; shift ;;
  esac
done

# request <method> <url> [json body]: prints the response body, fails on HTTP >= 400
request() {
  local method="$1" url="$2" body="${3-}" out status
  local args=(-sk -X "$method" -H 'Accept: application/json' -w $'\n%{http_code}')
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' --data-binary "$body")
  out=$(curl "${args[@]}" "$url") || { echo "Cannot reach $url" >&2; return 1; }
  status="${out##*$'\n'}"
  out="${out%$'\n'*}"
  if ((status >= 400)); then
    echo "$method $url returned HTTP $status: $out" >&2
    return 1
  fi
  printf '%s' "$out"
}

uri() { jq -rn --arg v "$1" '$v | @uri'; }

module_status() { request GET "$suite/api/info/$1" | jq -r '.status'; }

# wait_for <module id> <status>...: waits until the module has one of the statuses or ended
wait_for() {
  local id="$1" status deadline=$((SECONDS + 600))
  shift
  while ((SECONDS < deadline)); do
    status=$(module_status "$id")
    if [[ "$status" != "$last_status" ]]; then
      printf '  suite: %s\n' "$status" >&2
      last_status="$status"
    fi
    for wanted in "$@" FINISHED INTERRUPTED; do
      [[ "$status" == "$wanted" ]] && { printf '%s' "$status"; return 0; }
    done
    sleep 2
  done
  echo "Timed out waiting for module $id" >&2
  return 1
}

# prompt <text>: sets $answer to run, skip or quit; in auto mode the answer is run once $auto seconds
# passed without a key press
prompt() {
  local key text="$1" rc
  answer=run
  $pause || return 0
  while true; do
    key=""
    if [[ -n "$auto" ]]; then
      read -rsn1 -t "$auto" -p "$text (continues in ${auto}s) " key </dev/tty && rc=0 || rc=$?
      echo >/dev/tty
      ((rc > 128)) && return 0
    else
      read -rsn1 -p "$text " key </dev/tty
      echo >/dev/tty
    fi
    case "$key" in
      ""|" ") return 0 ;;
      s|S) answer=skip; return 0 ;;
      q|Q) answer=quit; return 0 ;;
      a|A) auto="${auto:-$default_auto}"; echo "  continuing automatically, ${auto}s between modules" >/dev/tty; return 0 ;;
    esac
  done
}

stop_receiver_run() {
  curl -sk -o /dev/null -X POST "$receiver/cts/runs/current/stop" || true
}

# --- the plan ---------------------------------------------------------------------------------

curl -sk -o /dev/null "$receiver/" || { echo "The receiver under test is not running at $receiver" >&2; exit 1; }

if [[ -n "$create" ]]; then
  [[ -f "$create" ]] || { echo "No such configuration: $create" >&2; exit 2; }
  name=$(basename "$create" .json)
  plan_name="openid-ssf-receiver-test-plan"
  [[ "$name" == *caepiop* ]] && plan_name="openid-ssf-receiver-caep-test-plan"
  delivery="push"
  [[ "$name" == *poll* ]] && delivery="poll"
  variant=$(jq -cn --arg d "$delivery" \
    '{client_auth_type: "client_secret_basic", ssf_auth_mode: "dynamic", ssf_delivery_mode: $d}')
  plan=$(request POST "$suite/api/plan?planName=$plan_name&variant=$(uri "$variant")" "$(cat "$create")" | jq -r '.id')
  echo "Created plan $plan_name ($delivery) from $create"
elif [[ -z "$plan" ]]; then
  for id in $(request GET "$suite/api/runner/running" | jq -r '.[]'); do
    plan=$(request GET "$suite/api/info/$id" | jq -r '.planId // empty')
    [[ -n "$plan" ]] && break
  done
  [[ -n "$plan" ]] || { echo "No module is running in the suite, name a plan (see --help)" >&2; exit 2; }
else
  plan="${plan##*plan=}"
  plan="${plan%%&*}"
fi

plan_json=$(request GET "$suite/api/plan/$plan")
alias=$(jq -r '.config.alias' <<<"$plan_json")
issuer="$suite/test/a/$alias"
echo "Plan $(jq -r '.planName' <<<"$plan_json") ($(jq -r '.variant.ssf_delivery_mode' <<<"$plan_json") delivery), alias $alias"
echo "  $suite/plan-detail.html?plan=$plan"
echo "  receiver $receiver, transmitter issuer $issuer"

modules=$(jq -c '.modules[]' <<<"$plan_json")
if ((${#filters[@]} > 0)); then
  modules=$(for f in "${filters[@]}"; do jq -c --arg f "$f" 'select(.testModule | contains($f))' <<<"$modules"; done | sort -u)
fi
total=$(wc -l <<<"$modules" | tr -d ' ')
[[ -n "$modules" ]] || { echo "No module of the plan matches" >&2; exit 2; }

# --- the modules -------------------------------------------------------------------------------

summary=()
failed=0
current=""
last_status=""
trap 'echo; stop_receiver_run; [[ -n "$current" ]] && echo "Module $current is left running in the suite"; exit 130' INT

i=0
while IFS= read -r module; do
  i=$((i + 1))
  name=$(jq -r '.testModule' <<<"$module")
  echo
  prompt "[$i/$total] $name - Enter runs, s skips, q quits, a continues automatically"
  case "$answer" in
    skip) summary+=("$name  SKIPPED"); continue ;;
    quit) break ;;
  esac
  $pause || echo "[$i/$total] $name"

  last_status=""
  current=$(request POST "$suite/api/runner?test=$(uri "$name")&plan=$plan&variant=$(uri "$(jq -c '.variant' <<<"$module")")" | jq -r '.id')
  echo "  $suite/log-detail.html?log=$current"
  status=$(wait_for "$current" CONFIGURED WAITING)
  if [[ "$status" == CONFIGURED ]]; then
    request POST "$suite/api/runner/$current" >/dev/null
    status=$(wait_for "$current" WAITING)
  fi
  if [[ "$status" == WAITING ]]; then
    if run=$(request POST "$receiver/cts/runs/auto?issuer=$(uri "$issuer")"); then
      echo "  receiver: run $(jq -r '.run.id' <<<"$run") plays $(jq -r '.run.scenario' <<<"$run")"
      wait_for "$current" >/dev/null
      echo "  receiver: $(request GET "$receiver/cts/runs/current" | jq -r '.status')"
    else
      echo "  receiver: could not start the run, see above" >&2
    fi
  fi

  info=$(request GET "$suite/api/info/$current")
  result=$(jq -r '.result // "NONE"' <<<"$info")
  echo "  result: $result"
  if [[ "$result" != PASSED ]]; then
    request GET "$suite/api/log/$current" \
      | jq -r '.[] | select(.result == "FAILURE" or .result == "WARNING" or .result == "REVIEW")
               | "    \(.result) \(.src // "-"): \(.msg // "")"'
    [[ "$result" == FAILED ]] && failed=$((failed + 1))
  fi
  summary+=("$name  $result")
  current=""
done <<<"$modules"

echo
echo "Summary of plan $plan ($suite/plan-detail.html?plan=$plan):"
printf '  %s\n' "${summary[@]}"
((failed == 0))
