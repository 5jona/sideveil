import type { LogEntry } from '@pochita/ui';
import type { DaemonStatus } from '../../shared/types.ts';

export function parseDaemonLogLines(lines: readonly string[]): LogEntry[] {
  return lines.map((line, idx) => {
    const level: LogEntry['level'] = /\[(E|ERROR)\]/i.test(line)
      ? 'error'
      : /\[(W|WARN|WARNING)\]/i.test(line)
        ? 'warn'
        : /\[(D|DEBUG)\]/i.test(line)
          ? 'debug'
          : 'info';
    const timePart = line.slice(0, 12).trim() || '--:--:--';
    return {
      id: `dlog-${idx}-${timePart}`,
      timestamp: timePart,
      level,
      message: line.length > 13 ? line.slice(13).trim() : line,
    };
  });
}

export function parseDaemonLogsStdout(stdout: string): LogEntry[] | null {
  const trimmed = stdout.trim();
  if (!trimmed) return null;
  try {
    const parsed = JSON.parse(trimmed) as { result?: { lines?: string[] } };
    return Array.isArray(parsed.result?.lines) ? parseDaemonLogLines(parsed.result.lines) : null;
  } catch {
    return null;
  }
}

export function parseDaemonStatusStdout(stdout: string): DaemonStatus | null {
  const trimmed = stdout.trim();
  if (!trimmed) return null;
  try {
    return JSON.parse(trimmed) as DaemonStatus;
  } catch {
    return null;
  }
}
