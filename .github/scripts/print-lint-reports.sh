#!/usr/bin/env bash
set -euo pipefail

# Print every Android Lint text/XML report so CI logs include the full issue list,
# not only Gradle's "First failure" summary.

shopt -s nullglob globstar

reports=(
  **/build/reports/lint-results-*.txt
  **/build/intermediates/lint_intermediate_text_report/**/lint-results-*.txt
  **/build/reports/lint-results-*.xml
)

found=0
for report in "${reports[@]}"; do
  # Skip duplicates when both reports/ and intermediates/ exist for the same file name.
  [[ -f "$report" ]] || continue
  found=1
  echo
  echo "===== lint report: $report ====="
  # Cap extremely large files so the job log stays usable, but keep enough for all issues.
  if [[ $(wc -c <"$report") -gt 2000000 ]]; then
    echo "(report larger than 2MB; showing first 4000 lines)"
    head -n 4000 "$report"
  else
    cat "$report"
  fi
  echo "===== end: $report ====="
done

if [[ "$found" -eq 0 ]]; then
  echo "No lint report files were produced."
  # Non-fatal: the original lint step already failed/succeeded on its own.
  exit 0
fi
