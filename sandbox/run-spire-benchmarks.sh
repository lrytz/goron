#!/usr/bin/env bash
#
# Run spire JMH benchmarks with and without goron optimization.
#
# Usage:
#   ./run-spire-benchmarks.sh [JMH benchmark filter] [JMH extra args...]
#
# Examples:
#   ./run-spire-benchmarks.sh                          # all benchmarks, default settings
#   ./run-spire-benchmarks.sh AddBenchmarks            # just AddBenchmarks
#   ./run-spire-benchmarks.sh AddBenchmarks -f 3       # 3 forks
#   ./run-spire-benchmarks.sh -l                       # list available benchmarks
#
# The script will:
#   1. Clone spire (if not already present)
#   2. Compile JMH benchmarks via sbt
#   3. Build goron (if needed)
#   4. Run goron on all Scala jars
#   5. Run JMH twice (stock, then goron-optimized)
#   6. Print a comparison table
#
# Results are saved to sandbox/spire-goron-test/{stock,goron}-results.csv

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SPIRE_DIR="$SCRIPT_DIR/spire"
WORK_DIR="$SCRIPT_DIR/spire-goron-test"
GORON_JAR="$PROJECT_DIR/target/scala-2.13/goron.jar"

# Default JMH settings — override with JMH_OPTS env var
# These are conservative defaults; increase for production runs
: "${JMH_OPTS:=-f 2 -wi 5 -i 5 -w 3 -r 3}"

# --- Parse arguments ---

BENCHMARK_FILTER=""
EXTRA_ARGS=()
for arg in "$@"; do
  if [[ -z "$BENCHMARK_FILTER" && ! "$arg" =~ ^- ]]; then
    BENCHMARK_FILTER="$arg"
  else
    EXTRA_ARGS+=("$arg")
  fi
done

# --- Helper functions ---

log() { echo ">>> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

# --- Step 1: Clone spire if needed ---

if [[ ! -d "$SPIRE_DIR" ]]; then
  log "Cloning spire..."
  git clone https://github.com/typelevel/spire.git "$SPIRE_DIR"
else
  log "Using existing spire checkout at $SPIRE_DIR"
fi

# --- Step 2: Compile JMH benchmarks ---

log "Compiling spire JMH benchmarks..."
(cd "$SPIRE_DIR" && sbt "benchmark/Jmh/compile" 2>&1 | tail -3)

# --- Step 3: Extract classpath ---

log "Extracting classpath..."
FULL_CP=$(cd "$SPIRE_DIR" && sbt "export benchmark/Jmh/fullClasspath" 2>&1 | grep -v "^\[" | tr -d '\n')

if [[ -z "$FULL_CP" ]]; then
  die "Failed to extract classpath from sbt"
fi

# --- Step 4: Build goron if needed ---

if [[ ! -f "$GORON_JAR" ]]; then
  log "Building goron..."
  (cd "$PROJECT_DIR" && sbt assembly 2>&1 | tail -3)
fi

if [[ ! -f "$GORON_JAR" ]]; then
  die "Goron jar not found at $GORON_JAR"
fi

# --- Step 5: Prepare goron input jars ---

mkdir -p "$WORK_DIR"

# Classify classpath entries into "optimize with goron" vs "keep as-is"
GORON_INPUTS=()    # Scala bytecode jars/dirs -> fed to goron
PASSTHROUGH=()     # JMH runtime + Java-only libs -> kept on classpath unchanged

IFS=':' read -ra CP_ENTRIES <<< "$FULL_CP"
for entry in "${CP_ENTRIES[@]}"; do
  basename_entry="$(basename "$entry")"
  case "$basename_entry" in
    # JMH runtime — never optimize
    jmh-*|jopt-simple-*|asm-*)
      PASSTHROUGH+=("$entry")
      ;;
    # Java-only benchmark dependencies — no Scala bytecode to optimize
    apfloat-*|jscience-*|commons-math3-*|javolution-*)
      PASSTHROUGH+=("$entry")
      ;;
    # Everything else is Scala bytecode: spire modules, scala-library, etc.
    *)
      GORON_INPUTS+=("$entry")
      ;;
  esac
done

# Jar up class directories (goron needs jars, not directories)
GORON_INPUT_JARS=()
for entry in "${GORON_INPUTS[@]}"; do
  if [[ -d "$entry" ]]; then
    # It's a classes directory — jar it up
    jar_name=$(echo "$entry" | sed "s|$SPIRE_DIR/||; s|/target/scala-[^/]*/classes||; s|[/.]|-|g")
    jar_path="$WORK_DIR/${jar_name}.jar"
    jar cf "$jar_path" -C "$entry" .
    GORON_INPUT_JARS+=("$jar_path")
  else
    # Already a jar file
    GORON_INPUT_JARS+=("$entry")
  fi
done

log "Goron inputs: ${#GORON_INPUT_JARS[@]} jars"
log "Passthrough: ${#PASSTHROUGH[@]} jars"

# --- Step 6: Run goron ---

log "Running goron optimization..."
GORON_CMD=(java -Xmx4g -jar "$GORON_JAR")
for jar in "${GORON_INPUT_JARS[@]}"; do
  GORON_CMD+=(--input "$jar")
done
GORON_CMD+=(--output "$WORK_DIR/optimized.jar" --no-dce --verbose)

"${GORON_CMD[@]}" 2>&1
echo

# --- Step 7: Handle -l (list only) ---

if [[ " ${EXTRA_ARGS[*]:-} " =~ " -l " ]]; then
  log "Listing benchmarks:"
  PASSTHROUGH_CP=$(IFS=:; echo "${PASSTHROUGH[*]}")
  java -cp "$WORK_DIR/optimized.jar:$PASSTHROUGH_CP" org.openjdk.jmh.Main -l
  exit 0
fi

# --- Step 8: Run stock benchmark ---

JMH_ARGS=($JMH_OPTS "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}")
if [[ -n "$BENCHMARK_FILTER" ]]; then
  JMH_ARGS+=("$BENCHMARK_FILTER")
fi

log "Running STOCK benchmarks..."
log "  JMH args: ${JMH_ARGS[*]}"
java -cp "$FULL_CP" org.openjdk.jmh.Main \
  "${JMH_ARGS[@]}" \
  -rff "$WORK_DIR/stock-results.csv" -rf csv \
  2>&1 | tee "$WORK_DIR/stock-output.txt"
echo

# --- Step 9: Run goron-optimized benchmark ---

PASSTHROUGH_CP=$(IFS=:; echo "${PASSTHROUGH[*]}")
GORON_CP="$WORK_DIR/optimized.jar:$PASSTHROUGH_CP"

log "Running GORON benchmarks..."
log "  JMH args: ${JMH_ARGS[*]}"
java -cp "$GORON_CP" org.openjdk.jmh.Main \
  "${JMH_ARGS[@]}" \
  -rff "$WORK_DIR/goron-results.csv" -rf csv \
  2>&1 | tee "$WORK_DIR/goron-output.txt"
echo

# --- Step 10: Compare results ---

log "Comparing results..."
echo
python3 - << 'PYEOF'
import csv, sys, os

work_dir = os.environ.get("WORK_DIR", "sandbox/spire-goron-test")
stock_file = os.path.join(work_dir, "stock-results.csv")
goron_file = os.path.join(work_dir, "goron-results.csv")

def read_results(path):
    results = {}
    with open(path) as f:
        reader = csv.reader(f)
        header = next(reader)
        # Find param columns (columns after "Unit")
        unit_idx = header.index("Unit") if "Unit" in header else 6
        param_cols = header[unit_idx + 1:]
        for row in reader:
            params = tuple(row[unit_idx + 1:]) if len(row) > unit_idx + 1 else ()
            key = (row[0], params)
            results[key] = (float(row[4]), float(row[5]))  # score, error
    return results, param_cols

stock, param_cols = read_results(stock_file)
goron, _ = read_results(goron_file)

# Format param header
param_hdr = param_cols[0] if param_cols else ""
has_params = any(p != () and any(v for v in p) for p in
                 set(k[1] for k in stock) | set(k[1] for k in goron))

hdr_fmt = "{:<55s} " + ("{:>10s} " if has_params else "") + "{:>12s} {:>12s} {:>8s}"
row_fmt = "{:<55s} " + ("{:>10s} " if has_params else "") + "{:>12.1f} {:>12.1f} {:>8s}"

hdr_args = ["Benchmark"]
if has_params:
    hdr_args.append(param_hdr)
hdr_args += ["Stock (us)", "Goron (us)", "Change"]
print(hdr_fmt.format(*hdr_args))
print("-" * (100 if has_params else 90))

improvements = []
for key in sorted(stock.keys()):
    if key not in goron:
        continue
    s_score, s_err = stock[key]
    g_score, g_err = goron[key]
    pct = (g_score - s_score) / s_score * 100
    improvements.append(pct)

    name = key[0].replace("spire.benchmark.", "")
    sign = "+" if pct > 0 else ""
    change_str = f"{sign}{pct:.1f}%"

    row_args = [name]
    if has_params:
        param_val = key[1][0] if key[1] else ""
        row_args.append(param_val)
    row_args += [s_score, g_score, change_str]
    print(row_fmt.format(*row_args))

if improvements:
    print()
    avg = sum(improvements) / len(improvements)
    sign = "+" if avg > 0 else ""
    better = sum(1 for x in improvements if x < -0.5)
    worse = sum(1 for x in improvements if x > 0.5)
    neutral = len(improvements) - better - worse
    print(f"Summary: {len(improvements)} benchmarks, "
          f"{better} faster, {neutral} neutral, {worse} slower, "
          f"avg change: {sign}{avg:.1f}%")
PYEOF
