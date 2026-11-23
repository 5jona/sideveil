/** Minimum interval between visibility-triggered refreshes to prevent rapid `ksu.exec` bursts. */
export const VISIBILITY_REFRESH_COOLDOWN_MS = 5000;

/**
 * Returns whether background `setInterval` polling is safe for the active bridge environment.
 * Disabled under `ksu` because periodic `ksu.exec` spawns `libksud.so` and triggers ColorOS
 * `oplus_secure_guard` security warning modals.
 */
export function shouldPollControlInBackground(env: string): boolean {
  return env !== 'ksu';
}

/** Returns `true` when enough time has elapsed since the previous visibility-triggered refresh. */
export function shouldRefreshOnVisibility(
  lastRefreshAtMs: number,
  nowMs: number,
  cooldownMs: number = VISIBILITY_REFRESH_COOLDOWN_MS,
): boolean {
  return nowMs - lastRefreshAtMs >= cooldownMs;
}
