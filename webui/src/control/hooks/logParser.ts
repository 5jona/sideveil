import type { LogEntry } from '@pochita/ui';

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
