# Observability dashboard

The native dashboard remains local-only and read-only apart from the existing connection close
actions.

- The home sparkline keeps up to 60 live traffic ticks in the UI process. It is memory-only and
  resets with the activity/process; no Binder method or on-disk history is added.
- Connection grouping is derived from the current live snapshot by app, host/SNI, or outbound
  chain. Closing one connection remains available only in the raw live view, while “close all”
  continues to target the underlying raw snapshot.
- Log filtering is a derived view of the existing stream/file messages. The live source remains
  bounded by `LogcatCache`; no second log store is created.

## Rule hit test follow-up

The current native APIs expose dashboard summaries, proxy groups, and observed connection rules,
but they do not expose a safe query that asks the kernel to match an arbitrary host or URL. A rule
hit tester is therefore deferred until the core provides a stable read-only match API that can be
bridged without broadening `IClashManager` solely for speculative UI behavior.

No data from these views is uploaded, and this feature adds no new backup or privacy-policy scope.
