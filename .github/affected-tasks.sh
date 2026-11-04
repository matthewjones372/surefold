#!/usr/bin/env bash
# The Gradle tasks a pull request needs, from the files it changes against $1 (its base: HEAD^1 on GitHub's merge
# commit, origin/main on a branch):
#
# - SpecsSuite alone, when every change is under specs/, since it is the one test that reads them;
# - each module a changed file is in, built with every module whose build file names it as a `project(":…")`, directly
#   or through another, so a change to surefold-actor-remote builds and tests surefold-actor-remote, surefold-cluster
#   and so on, and nothing else. Not Gradle's `buildDependents`: a module and its `-test` module (surefold-otel and
#   surefold-otel-test) each name the other, and Gradle refuses that walk as a circular dependency;
# - each module whose build file names a changed module's task (`":surefold-compiler:publish..."`, as a TestKit test
#   does to resolve it), tested, since a task is not a `project(":…")`;
# - each module whose build file names a changed file outside the modules, such as a guide it holds to a compiled
#   example, tested;
# - surefold-app's tests always, since its suites check the whole repository: specs, docs, module lists, names.
#
# A change that can reach every module (the root build, settings, the wrapper, this script or the workflow, or what
# the root build puts in every module's tests: surefold-junit and what it runs on) prints `build`, the full build. So
# does a pull request whose base cannot be found. A push to main always runs the full build in the workflow, so a test
# this narrowing skips still runs before anything is released.
set -euo pipefail

base="${1:?usage: affected-tasks.sh <base ref>}"
cd "$(git rev-parse --show-toplevel)"

if ! changed=$(git diff --name-only "$base"...HEAD 2>/dev/null); then
  echo build
  exit 0
fi

reaches_everything='^(settings\.gradle\.kts|build\.gradle\.kts|gradle\.properties|gradlew|gradlew\.bat|gradle/|buildSrc/|\.github/workflows/build\.yml|\.github/affected-tasks\.sh)'
if grep -Eq "$reaches_everything" <<<"$changed"; then
  echo build
  exit 0
fi

if ! grep -qv '^specs/' <<<"$changed"; then
  echo ":surefold-app:test --tests *SpecsSuite*"
  exit 0
fi

# Every module as "group/name", from settings.gradle.kts's module(group, name) calls.
modules=$(grep -oE '^module\("[^"]+", "[^"]+"\)' settings.gradle.kts | sed -E 's/module\("([^"]+)", "([^"]+)"\)/\1\/\2/')

# The root build gives every other module's tests surefold-junit, so these reach every module.
runs_on=$(grep -A1 '^val surefoldJunitRunsOn' build.gradle.kts | grep -oE '"[^"]+"' | tr -d '"')
if [[ -z "$runs_on" ]]; then
  echo build
  exit 0
fi

# A module and every module that depends on it, as ":name:build", following `project(":…")` through the build files.
builds=$(git ls-files '*/*/build.gradle.kts')
with_dependents() {
  local seen=" " queue=("$1") name dependent
  while ((${#queue[@]})); do
    name="${queue[0]}"
    queue=("${queue[@]:1}")
    [[ "$seen" == *" $name "* ]] && continue
    seen+="$name "
    echo ":$name:build"
    while IFS= read -r dependent; do
      [[ -n "$dependent" ]] && queue+=("$(basename "$(dirname "$dependent")")")
    done < <(xargs grep -lF -- "project(\":$name\")" <<<"$builds" 2>/dev/null || true)
  done
}

tasks=(":surefold-app:test")
while IFS= read -r file; do
  [[ -z "$file" ]] && continue
  owner=""
  while IFS= read -r module; do
    if [[ "$file" == "$module/"* ]]; then
      owner="$module"
      break
    fi
  done <<<"$modules"
  if [[ -n "$owner" ]]; then
    name="${owner#*/}"
    if grep -qxF -- "$name" <<<"$runs_on"; then
      echo build
      exit 0
    fi
    while IFS= read -r task; do tasks+=("$task"); done < <(with_dependents "$name")
    # Modules that reach this one through a task, not a dependency.
    while IFS= read -r build; do
      [[ -n "$build" ]] && tasks+=(":$(basename "$(dirname "$build")"):test")
    done < <(git ls-files '*/*/build.gradle.kts' | xargs grep -lF -- "\":$name:" 2>/dev/null || true)
  else
    # Outside every module: the modules whose build file names it read it in a test.
    while IFS= read -r build; do
      [[ -n "$build" ]] && tasks+=(":$(basename "$(dirname "$build")"):test")
    done < <(git ls-files '*/*/build.gradle.kts' | xargs grep -lF -- "\"$file\"" 2>/dev/null || true)
  fi
done <<<"$changed"

printf '%s\n' "${tasks[@]}" | sort -u | tr '\n' ' '
echo
